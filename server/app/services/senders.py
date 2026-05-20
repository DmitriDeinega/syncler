"""Sender service layer — register, lookup, revoke."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Sender


class SenderAlreadyExistsError(Exception):
    """A sender with this public key is already registered."""


class SenderNotFoundError(Exception):
    """Sender lookup failed."""


class SenderRevokedError(Exception):
    """Sender exists but is revoked."""


async def register_sender(
    db: AsyncSession,
    *,
    public_key: bytes,
    name: str,
    contact: str | None = None,
) -> Sender:
    sender = Sender(
        id=uuid.uuid4(),
        public_key=public_key,
        name=name,
        contact=contact,
    )
    db.add(sender)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise SenderAlreadyExistsError("public key already registered") from exc

    await db.refresh(sender)
    return sender


async def get_sender(db: AsyncSession, sender_id: uuid.UUID) -> Sender:
    result = await db.execute(select(Sender).where(Sender.id == sender_id))
    sender = result.scalar_one_or_none()
    if sender is None:
        raise SenderNotFoundError(f"sender {sender_id} not found")
    return sender


async def get_active_sender(db: AsyncSession, sender_id: uuid.UUID) -> Sender:
    sender = await get_sender(db, sender_id)
    if sender.revoked_at is not None:
        raise SenderRevokedError(f"sender {sender_id} is revoked")
    return sender


async def revoke_sender(db: AsyncSession, sender_id: uuid.UUID) -> Sender:
    sender = await get_sender(db, sender_id)
    if sender.revoked_at is None:
        sender.revoked_at = datetime.now(UTC)
        await db.commit()
        await db.refresh(sender)
    return sender
