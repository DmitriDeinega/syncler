"""Pairing service — broker-mediated sender↔user pairing."""

from __future__ import annotations

import base64
import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Pairing, PendingPairing, Sender, User
from app.services.senders import SenderRevokedError, get_active_sender

PAIRING_TOKEN_BYTES = 32
DEFAULT_TTL = timedelta(seconds=300)
MAX_TTL = timedelta(minutes=15)


class PairingError(Exception):
    """Base."""


class PairingTokenNotFoundError(PairingError):
    """No pending pairing for that token."""


class PairingTokenExpiredError(PairingError):
    """Pending pairing TTL expired."""


class PairingTokenConsumedError(PairingError):
    """Pending pairing already consumed."""


class PairingAlreadyExistsError(PairingError):
    """Active pairing exists for (user, sender)."""


def generate_pairing_token() -> bytes:
    return secrets.token_bytes(PAIRING_TOKEN_BYTES)


def fingerprint_for_public_key(public_key: bytes) -> str:
    """6 groups of 4 base32-uppercased chars over the first 8 bytes of SHA-256(public_key).

    Used for I4 anti-spoofing UX confirmation.
    """
    digest = hashlib.sha256(public_key).digest()
    encoded = base64.b32encode(digest[:8]).decode("ascii").rstrip("=").upper()
    return "-".join(encoded[i : i + 4] for i in range(0, len(encoded), 4))


async def initiate_pairing(
    db: AsyncSession,
    *,
    sender_id: uuid.UUID,
    ttl_seconds: int,
    metadata: dict[str, Any] | None,
    sender_broker_url: str | None = None,
) -> PendingPairing:
    """Persist a new pending pairing row.

    `sender_broker_url` is the V1.5 automated-pairing field — when set,
    the device POSTs the encrypted bootstrap envelope to this URL after
    the user confirms. NULL when the sender opted out of automated
    pairing (V1 manual flow). Shape validation and the
    `bootstrap_key`-must-be-registered check happen in the route layer
    before this service runs.
    """
    sender = await get_active_sender(db, sender_id)  # may raise
    ttl = timedelta(seconds=max(1, min(ttl_seconds, int(MAX_TTL.total_seconds()))))

    pending = PendingPairing(
        id=uuid.uuid4(),
        sender_id=sender.id,
        pairing_token=generate_pairing_token(),
        expires_at=datetime.now(UTC) + ttl,
        metadata_json=metadata,
        sender_broker_url=sender_broker_url,
    )
    db.add(pending)
    await db.commit()
    await db.refresh(pending)
    return pending


async def preview_pending(
    db: AsyncSession, *, pairing_token: bytes
) -> tuple[PendingPairing, Sender]:
    """Look up a pending pairing WITHOUT consuming it. Used by the device UI
    to show the sender identity before the user confirms the fingerprint.
    """
    result = await db.execute(
        select(PendingPairing).where(PendingPairing.pairing_token == pairing_token),
    )
    pending = result.scalar_one_or_none()
    if pending is None:
        raise PairingTokenNotFoundError("unknown pairing token")
    if pending.consumed_at is not None:
        raise PairingTokenConsumedError("pairing token already consumed")
    if pending.expires_at <= datetime.now(UTC):
        raise PairingTokenExpiredError("pairing token expired")
    sender = await get_active_sender(db, pending.sender_id)
    return pending, sender


async def complete_pairing(
    db: AsyncSession,
    *,
    user: User,
    pairing_token: bytes,
    encrypted_initial_state: bytes,
    key_generation: int = 1,
) -> tuple[Pairing, Sender, PendingPairing]:
    now = datetime.now(UTC)

    # Atomic claim: UPDATE consumed_at WHERE consumed_at IS NULL AND
    # expires_at > now AND pairing_token = ... RETURNING.
    # Only one concurrent request can succeed.
    from sqlalchemy import update
    claim = (
        update(PendingPairing)
        .where(
            and_(
                PendingPairing.pairing_token == pairing_token,
                PendingPairing.consumed_at.is_(None),
                PendingPairing.expires_at > now,
            ),
        )
        .values(consumed_at=now)
        .returning(PendingPairing)
    )
    result = await db.execute(claim)
    pending = result.scalar_one_or_none()
    if pending is None:
        # Distinguish causes for clearer errors
        existing = await db.execute(
            select(PendingPairing).where(PendingPairing.pairing_token == pairing_token),
        )
        row = existing.scalar_one_or_none()
        if row is None:
            await db.rollback()
            raise PairingTokenNotFoundError("unknown pairing token")
        if row.consumed_at is not None:
            await db.rollback()
            raise PairingTokenConsumedError("pairing token already consumed")
        await db.rollback()
        raise PairingTokenExpiredError("pairing token expired")

    sender = await get_active_sender(db, pending.sender_id)  # may raise SenderRevokedError

    # Re-pair-after-revoke: only an ACTIVE pairing should block. Hard
    # UNIQUE(user_id, sender_id) is dropped in migration 0003.
    existing = await db.execute(
        select(Pairing).where(
            and_(
                Pairing.user_id == user.id,
                Pairing.sender_id == sender.id,
                Pairing.revoked_at.is_(None),
            ),
        ),
    )
    if existing.scalar_one_or_none() is not None:
        await db.rollback()
        raise PairingAlreadyExistsError("user is already paired with this sender")

    pairing = Pairing(
        id=uuid.uuid4(),
        user_id=user.id,
        sender_id=sender.id,
        encrypted_state=encrypted_initial_state,
        # Phase 8 (docs/crypto-spec.md §10.4): stamp the row with the
        # generation the AAD was bound to. The caller passes the locked
        # users.key_generation read inside the user-row FOR UPDATE lock,
        # so this is race-free against concurrent rotation.
        key_generation=key_generation,
    )
    db.add(pairing)
    await db.commit()
    await db.refresh(pairing)
    return pairing, sender, pending


async def revoke_pairing(
    db: AsyncSession, *, user: User, pairing_id: uuid.UUID
) -> Pairing:
    result = await db.execute(
        select(Pairing).where(
            and_(Pairing.id == pairing_id, Pairing.user_id == user.id),
        ),
    )
    pairing = result.scalar_one_or_none()
    if pairing is None:
        raise PairingTokenNotFoundError(f"pairing {pairing_id} not found")
    if pairing.revoked_at is None:
        pairing.revoked_at = datetime.now(UTC)
        await db.commit()
        await db.refresh(pairing)
    return pairing


def sender_metadata_for_response(sender: Sender) -> dict[str, str | None]:
    """Build the data the device locks at pair-time for I4 anti-spoofing."""
    name_hash = hashlib.sha256(sender.name.encode("utf-8")).digest()
    return {
        "fingerprint": fingerprint_for_public_key(sender.public_key),
        "name_hash": base64.b64encode(name_hash).decode("ascii"),
    }
