"""Master-key rotation service — 14-step transaction per docs/crypto-spec.md §10.8.

Three rotation modes (`reason`) per §10.1:

  - ``password_rewrap``: MK unchanged; only wrap_key + auth_salt rotate. No
    blob re-encryption, no ``key_generation`` bump, no session revocation.
  - ``root_hygiene_rotation``: MK rotates; wrap_key unchanged. Blobs
    re-encrypted under new MK with ``key_generation = old + 1``. Sessions
    stay live.
  - ``root_compromise_rotation``: MK + wrap_key + auth_salt all rotate; ALL
    sessions revoked (including initiating).

The implementation matches the spec's atomic-CAS pattern:
``UPDATE ... WHERE state_version = :observed RETURNING`` — no
check-then-update gap.

The failed-proof counter (step 4 mismatch branch) is the ONLY operation
that escapes the rotation transaction: it runs in a separately-committed
session so the increment persists even after the main rollback.
"""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from math import ceil
from uuid import UUID

from sqlalchemy import delete, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.models import (
    Device,
    EncryptedUserState,
    MasterKeyRotationAudit,
    Pairing,
    RotationChallenge,
    User,
)

ROTATION_CHALLENGE_BYTES = 32
ROTATION_CHALLENGE_TTL = timedelta(minutes=5)
ROTATION_SUCCESS_LIMIT_PER_24H = 3
ROTATION_FAIL_LIMIT_PER_HOUR = 10
ROTATION_FAIL_WINDOW_SECONDS = 3600
ROTATION_FAIL_ROUTE = "rotate_proof_fail"


class RotationError(Exception):
    """Base for rotation-flow failures that route handlers translate to HTTP."""


class RotationChallengeInvalidError(RotationError):
    """No matching unexpired challenge for (challenge, user_id, session_id)."""


class RotationProofMismatchError(RotationError):
    """current_password_proof did not match users.auth_key_hash."""

    def __init__(self, rate_limited: bool, retry_after_seconds: int | None) -> None:
        self.rate_limited = rate_limited
        self.retry_after_seconds = retry_after_seconds
        super().__init__("rotation proof mismatch")


class RotationSuccessRateLimitedError(RotationError):
    """User has already rotated ``ROTATION_SUCCESS_LIMIT_PER_24H`` times."""

    def __init__(self, retry_after_seconds: int) -> None:
        self.retry_after_seconds = retry_after_seconds
        super().__init__("rotation success limit exceeded")


class RotationKeyGenerationMismatchError(RotationError):
    """``key_generation_observed`` does not match ``users.key_generation``."""

    def __init__(self, current_key_generation: int) -> None:
        self.current_key_generation = current_key_generation
        super().__init__("key_generation_observed mismatch")


class RotationPairingSetChangedError(RotationError):
    """Active pairing set differs from request (extra/missing/duplicate id)."""

    def __init__(self, current_pairing_ids: list[UUID]) -> None:
        self.current_pairing_ids = current_pairing_ids
        super().__init__("pairing set changed")


class RotationPairingStateChangedError(RotationError):
    """At least one pairing CAS failed (state_version mismatch)."""

    def __init__(self, mismatches: list[tuple[UUID, int]]) -> None:
        # list of (pairing_id, current_state_version) for mismatched rows
        self.mismatches = mismatches
        super().__init__("pairing state changed")


class RotationUserStateChangedError(RotationError):
    """encrypted_user_state CAS failed (state_version mismatch)."""

    def __init__(self, current_state_version: int) -> None:
        self.current_state_version = current_state_version
        super().__init__("state version mismatch")


@dataclass(frozen=True)
class RotationContext:
    """Inputs the route layer collects before invoking ``perform_rotation``."""

    user_id: UUID
    session_id: UUID  # the calling device's id (per §10.6 binding)
    reason: str  # one of the three modes
    key_generation_observed: int
    rotation_challenge: bytes  # 32 bytes
    current_password_proof: bytes  # 32 bytes
    new_encrypted_master_key: bytes
    new_auth_salt: bytes | None
    new_auth_key_proof: bytes | None
    new_encrypted_user_state: tuple[int, bytes] | None  # (state_version_observed, blob)
    pairings: list[tuple[UUID, int, bytes]]  # (pairing_id, state_version_observed, new_blob)
    raw_pairing_ids: list[UUID]  # for duplicate-detection (§10.8 step 7)
    initiating_device_id: UUID | None
    ip: str | None
    user_agent: str | None


@dataclass(frozen=True)
class PairingResult:
    pairing_id: UUID
    new_state_version: int
    new_key_generation: int


@dataclass(frozen=True)
class UserStateResult:
    new_state_version: int
    new_key_generation: int


@dataclass(frozen=True)
class RotationResult:
    new_key_generation: int
    user_state: UserStateResult | None  # None for password_rewrap
    pairings: list[PairingResult]


def _utcnow() -> datetime:
    return datetime.now(UTC)


def _digest_proof(proof: bytes) -> bytes:
    return hashlib.sha256(proof).digest()


async def issue_challenge(
    db: AsyncSession, *, user_id: UUID, session_id: UUID,
) -> RotationChallenge:
    """Generate and persist a single-use rotation challenge.

    See ``POST /v1/account/rotate-master-key/challenge`` (§10.6). 32 random
    bytes, bound to ``(user_id, session_id)``, valid for ~5 min.
    """
    challenge_bytes = secrets.token_bytes(ROTATION_CHALLENGE_BYTES)
    row = RotationChallenge(
        challenge=challenge_bytes,
        user_id=user_id,
        session_id=session_id,
        expires_at=_utcnow() + ROTATION_CHALLENGE_TTL,
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return row


async def _increment_failed_proof_counter(
    session_factory: async_sessionmaker[AsyncSession],
    *,
    user_id: UUID,
    now: datetime,
) -> int:
    """Per-user failed-proof rate-limit increment in a SEPARATE session.

    Uses the shared ``rate_limit_events`` table with
    ``route='rotate_proof_fail'`` (§10.8 step 4 implementation note). Runs
    in its own transaction so the increment survives the rollback of the
    surrounding rotation transaction.

    Returns the post-increment count in the current 1-hour window.
    """
    window_epoch = (
        int(now.timestamp()) // ROTATION_FAIL_WINDOW_SECONDS * ROTATION_FAIL_WINDOW_SECONDS
    )
    window_start = datetime.fromtimestamp(window_epoch, UTC)

    async with session_factory() as fail_db:
        bind = fail_db.get_bind()
        if bind.dialect.name == "sqlite":
            existing = await fail_db.execute(
                text(
                    """
                    SELECT id, count FROM rate_limit_events
                    WHERE actor_type = 'user'
                      AND actor_id = :user_id
                      AND route = :route
                      AND window_start = :window_start
                    """,
                ),
                {
                    "user_id": str(user_id),
                    "route": ROTATION_FAIL_ROUTE,
                    "window_start": window_start,
                },
            )
            row = existing.first()
            if row is not None:
                row_data = row._mapping
                next_count = int(row_data["count"]) + 1
                await fail_db.execute(
                    text("UPDATE rate_limit_events SET count = :c WHERE id = :id"),
                    {"c": next_count, "id": row_data["id"]},
                )
                await fail_db.commit()
                return next_count
            max_id = await fail_db.execute(
                text("SELECT COALESCE(MAX(id), 0) + 1 FROM rate_limit_events"),
            )
            await fail_db.execute(
                text(
                    """
                    INSERT INTO rate_limit_events
                        (id, actor_type, actor_id, route, window_start, count)
                    VALUES (:id, 'user', :user_id, :route, :window_start, 1)
                    """,
                ),
                {
                    "id": int(max_id.scalar_one()),
                    "user_id": str(user_id),
                    "route": ROTATION_FAIL_ROUTE,
                    "window_start": window_start,
                },
            )
            await fail_db.commit()
            return 1

        result = await fail_db.execute(
            text(
                """
                INSERT INTO rate_limit_events
                    (actor_type, actor_id, route, window_start, count)
                VALUES ('user', :user_id, :route, :window_start, 1)
                ON CONFLICT (actor_type, actor_id, route, window_start)
                DO UPDATE SET count = rate_limit_events.count + 1
                RETURNING count
                """,
            ),
            {
                "user_id": str(user_id),
                "route": ROTATION_FAIL_ROUTE,
                "window_start": window_start,
            },
        )
        count = int(result.scalar_one())
        await fail_db.commit()
        return count


def _retry_after_for_fail_window(now: datetime) -> int:
    epoch = int(now.timestamp())
    window_end = (
        epoch // ROTATION_FAIL_WINDOW_SECONDS + 1
    ) * ROTATION_FAIL_WINDOW_SECONDS
    return max(1, ceil(window_end - now.timestamp()))


async def lock_user_row(db: AsyncSession, user_id: UUID) -> None:
    """Acquire the user-row lock that serializes against rotation.

    Public helper so other endpoints (state.py, pairing.py) can take the
    same lock as their first DB op per the §10.5 MUST clause. Uses the
    ORM ``with_for_update()`` so SQLAlchemy applies the model's UUID
    type converter — raw ``text("... :user_id")`` won't bind a UUID on
    SQLite (test events suite). On SQLite ``FOR UPDATE`` is omitted;
    its per-write serialization makes the lock implicit anyway.
    """
    bind = db.get_bind()
    stmt = select(User.id).where(User.id == user_id)
    if bind.dialect.name != "sqlite":
        stmt = stmt.with_for_update()
    await db.execute(stmt)


async def perform_rotation(
    db: AsyncSession,
    session_factory: async_sessionmaker[AsyncSession],
    *,
    ctx: RotationContext,
) -> RotationResult:
    """Run the 14-step rotation transaction. Raises one of the typed errors
    above on protocol failures; raises bare exceptions on programmer bugs.

    Caller (route layer) is responsible for translating exceptions to the
    HTTP shapes per §10.5/§10.8.
    """
    now = _utcnow()

    # Step 2 — lock user row.
    await lock_user_row(db, ctx.user_id)
    locked = await db.execute(select(User).where(User.id == ctx.user_id))
    user = locked.scalar_one_or_none()
    if user is None:
        # The caller's auth dependency should have rejected this already;
        # treat it as challenge-invalid to avoid leaking distinct error
        # shapes about user existence inside an authenticated endpoint.
        raise RotationChallengeInvalidError("user not found")

    # Step 3 — consume the rotation challenge (still under the user-row lock).
    bind = db.get_bind()
    dialect = bind.dialect.name
    challenge_stmt = select(RotationChallenge.challenge).where(
        RotationChallenge.challenge == ctx.rotation_challenge,
        RotationChallenge.user_id == ctx.user_id,
        RotationChallenge.session_id == ctx.session_id,
        RotationChallenge.expires_at > now,
    )
    if dialect != "sqlite":
        challenge_stmt = challenge_stmt.with_for_update()
    challenge_row = await db.execute(challenge_stmt)
    if challenge_row.first() is None:
        raise RotationChallengeInvalidError("rotation challenge not found or expired")

    # Step 4 — verify current_password_proof.
    expected = user.auth_key_hash
    actual = _digest_proof(ctx.current_password_proof)
    if not secrets.compare_digest(expected, actual):
        # Increment failed-proof counter in a SEPARATE session so the
        # increment survives this transaction's rollback.
        fail_count = await _increment_failed_proof_counter(
            session_factory, user_id=ctx.user_id, now=now,
        )
        rate_limited = fail_count > ROTATION_FAIL_LIMIT_PER_HOUR
        retry_after = _retry_after_for_fail_window(now) if rate_limited else None
        raise RotationProofMismatchError(
            rate_limited=rate_limited,
            retry_after_seconds=retry_after,
        )

    # Step 5 — rate-limit successful rotations (count audit rows in last 24h).
    twenty_four_hours_ago = now - timedelta(hours=24)
    success_count_row = await db.execute(
        select(MasterKeyRotationAudit.id).where(
            MasterKeyRotationAudit.user_id == ctx.user_id,
            MasterKeyRotationAudit.occurred_at > twenty_four_hours_ago,
        ),
    )
    success_count = len(success_count_row.scalars().all())
    if success_count >= ROTATION_SUCCESS_LIMIT_PER_24H:
        # 24h sliding-window retry-after — approximate from the oldest
        # qualifying row.
        oldest_row = await db.execute(
            select(MasterKeyRotationAudit.occurred_at)
            .where(
                MasterKeyRotationAudit.user_id == ctx.user_id,
                MasterKeyRotationAudit.occurred_at > twenty_four_hours_ago,
            )
            .order_by(MasterKeyRotationAudit.occurred_at.asc())
            .limit(1),
        )
        oldest = oldest_row.scalar_one_or_none()
        if oldest is None:
            retry_after_s = 24 * 3600
        else:
            # When oldest is naive (sqlite), assume UTC.
            if oldest.tzinfo is None:
                oldest = oldest.replace(tzinfo=UTC)
            retry_after_s = max(
                1, ceil((oldest + timedelta(hours=24) - now).total_seconds()),
            )
        raise RotationSuccessRateLimitedError(retry_after_seconds=retry_after_s)

    # Step 6 — verify key_generation_observed.
    if ctx.key_generation_observed != user.key_generation:
        raise RotationKeyGenerationMismatchError(
            current_key_generation=user.key_generation,
        )

    is_root = ctx.reason in {"root_hygiene_rotation", "root_compromise_rotation"}

    # Step 7 — for root_*: verify exact active pairing ID set.
    pairing_results: list[PairingResult] = []
    user_state_result: UserStateResult | None = None
    old_generation = user.key_generation
    new_generation = old_generation + (1 if is_root else 0)

    if is_root:
        active_rows = await db.execute(
            select(Pairing.id).where(
                Pairing.user_id == ctx.user_id,
                Pairing.revoked_at.is_(None),
            ),
        )
        canonical_active_ids: set[UUID] = set(active_rows.scalars().all())
        raw = ctx.raw_pairing_ids
        deduped = set(raw)
        # Duplicate-detection MUST run BEFORE set-equality (Codex consult 103).
        if len(raw) != len(deduped):
            raise RotationPairingSetChangedError(
                current_pairing_ids=sorted(canonical_active_ids, key=str),
            )
        if deduped != canonical_active_ids:
            raise RotationPairingSetChangedError(
                current_pairing_ids=sorted(canonical_active_ids, key=str),
            )

        # Step 8 — CAS each pairing.
        mismatches: list[tuple[UUID, int]] = []
        for pairing_id, observed, new_blob in ctx.pairings:
            stmt = (
                update(Pairing)
                .where(
                    Pairing.id == pairing_id,
                    Pairing.user_id == ctx.user_id,
                    Pairing.revoked_at.is_(None),
                    Pairing.state_version == observed,
                )
                .values(
                    encrypted_state=new_blob,
                    state_version=observed + 1,
                    key_generation=new_generation,
                )
                .returning(Pairing.state_version)
            )
            result = await db.execute(stmt)
            row = result.first()
            if row is None:
                current_row = await db.execute(
                    select(Pairing.state_version).where(
                        Pairing.id == pairing_id,
                        Pairing.user_id == ctx.user_id,
                    ),
                )
                current_version = current_row.scalar_one_or_none()
                mismatches.append(
                    (pairing_id, current_version if current_version is not None else -1),
                )
            else:
                pairing_results.append(
                    PairingResult(
                        pairing_id=pairing_id,
                        new_state_version=int(row[0]),
                        new_key_generation=new_generation,
                    ),
                )
        if mismatches:
            raise RotationPairingStateChangedError(mismatches=mismatches)

        # Step 9 — CAS encrypted_user_state.
        if ctx.new_encrypted_user_state is not None:
            state_observed, state_blob = ctx.new_encrypted_user_state
            stmt = (
                update(EncryptedUserState)
                .where(
                    EncryptedUserState.user_id == ctx.user_id,
                    EncryptedUserState.state_version == state_observed,
                )
                .values(
                    encrypted_blob=state_blob,
                    state_version=state_observed + 1,
                    key_generation=new_generation,
                    updated_at=now,
                )
                .returning(EncryptedUserState.state_version)
            )
            result = await db.execute(stmt)
            row = result.first()
            if row is None:
                current_row = await db.execute(
                    select(EncryptedUserState.state_version).where(
                        EncryptedUserState.user_id == ctx.user_id,
                    ),
                )
                current_version = current_row.scalar_one_or_none() or 0
                raise RotationUserStateChangedError(
                    current_state_version=current_version,
                )
            user_state_result = UserStateResult(
                new_state_version=int(row[0]),
                new_key_generation=new_generation,
            )

    # Step 10 — user-row writes.
    user.encrypted_master_key = ctx.new_encrypted_master_key
    if ctx.reason in {"password_rewrap", "root_compromise_rotation"}:
        assert ctx.new_auth_salt is not None
        assert ctx.new_auth_key_proof is not None
        user.auth_salt = ctx.new_auth_salt
        user.auth_key_hash = _digest_proof(ctx.new_auth_key_proof)
    if is_root:
        user.key_generation = new_generation

    # Step 11 — audit row.
    audit = MasterKeyRotationAudit(
        user_id=ctx.user_id,
        reason=ctx.reason,
        old_generation=old_generation,
        new_generation=new_generation,
        initiating_session_id=ctx.session_id,
        initiating_device_id=ctx.initiating_device_id,
        ip=ctx.ip,
        user_agent=ctx.user_agent,
        paired_count=len(ctx.pairings) if is_root else 0,
        occurred_at=now,
    )
    db.add(audit)

    # Step 12 — root_compromise: revoke ALL devices (the next request from
    # any of them returns 401).
    if ctx.reason == "root_compromise_rotation":
        await db.execute(
            update(Device)
            .where(Device.user_id == ctx.user_id, Device.revoked_at.is_(None))
            .values(revoked_at=now),
        )

    # Step 13 — consume the challenge.
    await db.execute(
        delete(RotationChallenge).where(
            RotationChallenge.challenge == ctx.rotation_challenge,
        ),
    )

    # Step 14 — COMMIT.
    await db.commit()

    return RotationResult(
        new_key_generation=new_generation,
        user_state=user_state_result,
        pairings=pairing_results,
    )
