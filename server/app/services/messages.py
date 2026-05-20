"""Message service layer — store, fetch, dismiss, action."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeliveryStatus, Device, Message, Pairing, Plugin


DEFAULT_RETENTION = timedelta(days=30)


class MessageError(Exception):
    """Base class for message service errors."""


class NoActiveDeviceWithPluginError(MessageError):
    """No device for this user has this plugin installed and reachable."""


class PairingMissingError(MessageError):
    """No active pairing between sender and user."""


class PluginInactiveError(MessageError):
    """Plugin record missing or revoked."""


class MessageNotFoundError(MessageError):
    """Message does not exist for this user."""


async def _active_devices(db: AsyncSession, user_id: uuid.UUID) -> list[Device]:
    result = await db.execute(
        select(Device).where(
            and_(Device.user_id == user_id, Device.revoked_at.is_(None)),
        ),
    )
    return list(result.scalars().all())


async def _pairing(db: AsyncSession, *, sender_id: uuid.UUID, user_id: uuid.UUID) -> Pairing | None:
    result = await db.execute(
        select(Pairing).where(
            and_(
                Pairing.sender_id == sender_id,
                Pairing.user_id == user_id,
                Pairing.revoked_at.is_(None),
            ),
        ),
    )
    return result.scalar_one_or_none()


async def _plugin(db: AsyncSession, plugin_id: uuid.UUID) -> Plugin | None:
    result = await db.execute(select(Plugin).where(Plugin.id == plugin_id))
    return result.scalar_one_or_none()


async def store_message(
    db: AsyncSession,
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    encrypted_body: bytes,
    nonce: bytes,
    envelope_signature: bytes,
    min_plugin_version: str | None,
    expires_at: datetime | None = None,
) -> Message:
    pairing = await _pairing(db, sender_id=sender_id, user_id=user_id)
    if pairing is None:
        raise PairingMissingError("no active pairing")

    plugin = await _plugin(db, plugin_id)
    if plugin is None or plugin.revoked_at is not None:
        raise PluginInactiveError("plugin missing or revoked")

    devices = await _active_devices(db, user_id)
    if not devices:
        raise NoActiveDeviceWithPluginError("user has no active device")

    if expires_at is None:
        expires_at = datetime.now(UTC) + DEFAULT_RETENTION

    # Encrypted body is opaque to the server; we keep it in a separate blob table or
    # inline. For V1 we keep it inline via an "encrypted_body_pointer" addressed JSON.
    pointer = _build_pointer(encrypted_body, nonce, envelope_signature)

    message = Message(
        id=uuid.uuid4(),
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_pointer=pointer,
        min_plugin_version=min_plugin_version,
        expires_at=expires_at,
    )
    db.add(message)
    await db.flush()

    for device in devices:
        db.add(
            DeliveryStatus(
                message_id=message.id,
                device_id=device.id,
            ),
        )

    await db.commit()
    await db.refresh(message)
    return message


async def get_message_for_user(
    db: AsyncSession, *, user_id: uuid.UUID, message_id: uuid.UUID
) -> Message:
    result = await db.execute(
        select(Message).where(
            and_(Message.id == message_id, Message.user_id == user_id),
        ),
    )
    message = result.scalar_one_or_none()
    if message is None:
        raise MessageNotFoundError(f"message {message_id} not found")
    return message


async def inbox_for_device(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    since: datetime | None,
    limit: int = 50,
) -> tuple[list[Message], datetime | None]:
    query = (
        select(Message)
        .where(Message.user_id == user_id)
        .order_by(Message.sent_at.asc())
        .limit(limit)
    )
    if since is not None:
        query = query.where(Message.sent_at > since)

    result = await db.execute(query)
    messages = list(result.scalars().all())
    next_since = messages[-1].sent_at if messages else since
    return messages, next_since


async def mark_dismissed(
    db: AsyncSession,
    *,
    message_id: uuid.UUID,
    device_id: uuid.UUID,
) -> DeliveryStatus:
    result = await db.execute(
        select(DeliveryStatus).where(
            and_(
                DeliveryStatus.message_id == message_id,
                DeliveryStatus.device_id == device_id,
            ),
        ),
    )
    status = result.scalar_one_or_none()
    if status is None:
        raise MessageNotFoundError("delivery_status row not found")

    if status.dismissed_at is None:
        status.dismissed_at = datetime.now(UTC)
        await db.commit()
        await db.refresh(status)
    return status


def _build_pointer(encrypted_body: bytes, nonce: bytes, envelope_signature: bytes) -> str:
    """Build the encrypted_body_pointer column value.

    V1 stores the ciphertext inline as base64 segments separated by ``|`` so the
    server never decodes it and never sees plaintext. A future migration may
    move this to blob storage; the column type stays ``TEXT``.
    """
    import base64

    encoded = base64.b64encode(encrypted_body).decode("ascii")
    nonce_encoded = base64.b64encode(nonce).decode("ascii")
    sig_encoded = base64.b64encode(envelope_signature).decode("ascii")
    return f"inline:{nonce_encoded}|{encoded}|{sig_encoded}"


def parse_pointer(pointer: str) -> tuple[bytes, bytes, bytes]:
    """Inverse of :func:`_build_pointer`."""
    import base64

    if not pointer.startswith("inline:"):
        raise ValueError("unsupported pointer scheme")
    body = pointer[len("inline:") :]
    parts = body.split("|")
    if len(parts) != 3:
        raise ValueError("malformed pointer")
    nonce = base64.b64decode(parts[0])
    encrypted_body = base64.b64decode(parts[1])
    envelope_signature = base64.b64decode(parts[2])
    return encrypted_body, nonce, envelope_signature
