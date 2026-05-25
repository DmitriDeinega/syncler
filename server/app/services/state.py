"""Encrypted user state — opaque blob, optimistic concurrency control.

The server is content-blind to the blob. Clients decrypt locally with
their master key and apply structural merging on conflict.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import EncryptedUserState


class StateError(Exception):
    """Base."""


class StateConflictError(StateError):
    """Optimistic concurrency check failed; client must merge + retry."""

    def __init__(self, current: EncryptedUserState) -> None:
        self.current = current
        super().__init__("state_version conflict")


async def get_state(db: AsyncSession, user_id: uuid.UUID) -> EncryptedUserState | None:
    result = await db.execute(
        select(EncryptedUserState).where(EncryptedUserState.user_id == user_id),
    )
    return result.scalar_one_or_none()


async def upsert_state_cas(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    expected_state_version: int,
    new_encrypted_blob: bytes,
    key_generation: int = 1,
) -> EncryptedUserState:
    """Compare-and-swap update of the user's encrypted state.

    - If the row doesn't exist and expected_state_version == 0: insert new.
    - If exists and current.state_version == expected_state_version: update.
    - Otherwise: raise StateConflictError with the current row so the client
      can pull, merge locally, and retry.

    CAS uses ``UPDATE ... WHERE state_version = expected RETURNING`` for
    atomic check-and-set; loser receives no row (rowcount=0) and we raise
    StateConflictError without ever returning success.
    """
    from sqlalchemy.exc import IntegrityError

    current = await get_state(db, user_id)

    if current is None:
        if expected_state_version != 0:
            raise StateConflictError(
                current=EncryptedUserState(
                    user_id=user_id,
                    state_version=0,
                    encrypted_blob=b"",
                    updated_at=datetime.now(UTC),
                ),
            )
        row = EncryptedUserState(
            user_id=user_id,
            state_version=1,
            encrypted_blob=new_encrypted_blob,
            # Phase 8 §10.4: stamp the row with the locked user generation
            # so AAD lockstep holds. Caller passes the value read inside
            # the user-row FOR UPDATE lock.
            key_generation=key_generation,
        )
        db.add(row)
        try:
            await db.commit()
        except IntegrityError:
            # Initial-create race: another writer beat us. Convert the DB
            # error into a clean 409 with the current state.
            await db.rollback()
            current_now = await get_state(db, user_id)
            raise StateConflictError(
                current=current_now
                or EncryptedUserState(
                    user_id=user_id,
                    state_version=0,
                    encrypted_blob=b"",
                    updated_at=datetime.now(UTC),
                ),
            ) from None
        await db.refresh(row)
        return row

    if current.state_version != expected_state_version:
        raise StateConflictError(current=current)

    new_version = current.state_version + 1
    # RETURNING the row makes "did this UPDATE actually hit?" unambiguous —
    # no row returned = lost the race, not a silent success.
    stmt = (
        update(EncryptedUserState)
        .where(EncryptedUserState.user_id == user_id)
        .where(EncryptedUserState.state_version == expected_state_version)
        .values(
            state_version=new_version,
            encrypted_blob=new_encrypted_blob,
            # Phase 8 §10.4: restate the row's key_generation on every
            # CAS write. Rotation step 9 sets it; PUT /v1/state keeps it
            # in sync with the locked users.key_generation. Same value
            # on the steady path; defense-in-depth if anything ever
            # drifted.
            key_generation=key_generation,
            updated_at=datetime.now(UTC),
        )
        .returning(
            EncryptedUserState.user_id,
            EncryptedUserState.state_version,
        )
    )
    result = await db.execute(stmt)
    row_data = result.first()
    if row_data is None:
        # Lost the race. Pull fresh and surface conflict.
        await db.rollback()
        fresh = await get_state(db, user_id)
        raise StateConflictError(current=fresh or current)

    await db.commit()
    refreshed = await get_state(db, user_id)
    # Sanity: refreshed must report the version we wrote (else somehow yet
    # another concurrent writer flipped past us).
    if refreshed is None or refreshed.state_version != new_version:
        raise StateConflictError(current=refreshed or current)
    return refreshed
