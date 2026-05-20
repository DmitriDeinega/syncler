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
) -> EncryptedUserState:
    """Compare-and-swap update of the user's encrypted state.

    - If the row doesn't exist and expected_state_version == 0: insert new.
    - If exists and current.state_version == expected_state_version: update.
    - Otherwise: raise StateConflictError with the current row so the client
      can pull, merge locally, and retry.
    """
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
        )
        db.add(row)
        await db.commit()
        await db.refresh(row)
        return row

    if current.state_version != expected_state_version:
        raise StateConflictError(current=current)

    new_version = current.state_version + 1
    await db.execute(
        update(EncryptedUserState)
        .where(EncryptedUserState.user_id == user_id)
        .where(EncryptedUserState.state_version == expected_state_version)
        .values(
            state_version=new_version,
            encrypted_blob=new_encrypted_blob,
            updated_at=datetime.now(UTC),
        ),
    )
    await db.commit()
    refreshed = await get_state(db, user_id)
    if refreshed is None or refreshed.state_version != new_version:
        # Lost the race between version-check and update.
        raise StateConflictError(current=refreshed or current)
    return refreshed
