"""Message service layer — store, fetch, dismiss, action."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeliveryStatus, Device, Message, Pairing, Plugin


DEFAULT_RETENTION = timedelta(days=30)
MAX_RETENTION = timedelta(days=30)


class MessageError(Exception):
    """Base class for message service errors."""


class NoActiveDeviceWithPluginError(MessageError):
    """No device for this user has this plugin installed and reachable."""


class PairingMissingError(MessageError):
    """No active pairing between sender and user."""


class PluginInactiveError(MessageError):
    """Plugin record missing or revoked, or sender_id does not match."""


class ExpiredEnvelopeError(MessageError):
    """The signed envelope's expires_at is in the past or beyond the cap."""


class NonceReplayError(MessageError):
    """The (sender_id, nonce) pair was already used."""


class MessageNotFoundError(MessageError):
    """Message does not exist for this user."""


class DeviceOwnershipError(MessageError):
    """The device_id does not belong to the authenticated user."""


async def _active_devices(db: AsyncSession, user_id: uuid.UUID) -> list[Device]:
    """Active devices (revoked_at IS NULL). Used for delivery_status fan-out."""
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


async def _plugin(
    db: AsyncSession, *, plugin_id: uuid.UUID, sender_id: uuid.UUID
) -> Plugin | None:
    """Plugin lookup that ALSO enforces sender ownership.

    Prevents sender X from sending a message via sender Y's plugin (Codex
    review finding, M5.1).
    """
    result = await db.execute(
        select(Plugin).where(
            and_(
                Plugin.id == plugin_id,
                Plugin.sender_id == sender_id,
                Plugin.revoked_at.is_(None),
            ),
        ),
    )
    return result.scalar_one_or_none()


async def store_message_v2(
    db: AsyncSession,
    *,
    payload: object,  # MessageSendRequestV2 — avoid circular import on type
) -> Message:
    """Phase 9b: persist a V2 publish payload as a Message + per-device
    DeliveryStatus rows.

    The full V2 wire (payload_ciphertext, payload_nonce,
    recipient_envelopes, recipient_directory_version,
    envelope_signature) is serialized into the
    ``Message.encrypted_body_pointer`` column via
    ``build_v2_pointer``. The inbox fetch path reconstructs the wire
    from that pointer on the way out.

    The caller (POST /v1/messages/send) has already:
    - Verified the Ed25519 envelope signature
    - Burned the payload_nonce in the nonce-replay table
    - Run the recipient-set classifier (§11.10)
    - Resolved the sender + plugin records

    so this function focuses on the persistence shape and the
    delivery_status fan-out.
    """
    # Local import to avoid module-load-time cycles (services <-> schemas).
    from app.services.envelopes_v2 import build_v2_pointer

    sender_id = payload.sender_id
    user_id = payload.user_id
    plugin_id = payload.plugin_id
    expires_at = payload.expires_at

    now = datetime.now(UTC)
    if expires_at <= now:
        raise ExpiredEnvelopeError("expires_at is not in the future")
    if expires_at > now + MAX_RETENTION:
        raise ExpiredEnvelopeError("expires_at exceeds 30-day retention cap")

    pairing = await _pairing(db, sender_id=sender_id, user_id=user_id)
    if pairing is None:
        raise PairingMissingError("no active pairing")

    plugin = await _plugin(db, plugin_id=plugin_id, sender_id=sender_id)
    if plugin is None:
        raise PluginInactiveError("plugin missing, revoked, or not owned by sender")

    devices = await _active_devices(db, user_id)
    if not devices:
        raise NoActiveDeviceWithPluginError("user has no active devices")

    pointer = build_v2_pointer(payload)
    message = Message(
        id=uuid.uuid4(),
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_pointer=pointer,
        min_plugin_version=payload.min_plugin_version,
        expires_at=expires_at,
    )
    db.add(message)
    await db.flush()

    # Delivery_status rows cover EVERY active device (same pattern as V1).
    # The per-device decrypt happens client-side using its own slot in
    # recipient_envelopes; the server doesn't filter.
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
    now = datetime.now(UTC)
    result = await db.execute(
        select(Message).where(
            and_(
                Message.id == message_id,
                Message.user_id == user_id,
                Message.expires_at > now,
            ),
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
    now = datetime.now(UTC)
    # Phase 9b §11.4 (Codex 128 #1): INNER JOIN now — V2 envelopes are
    # per-device, so a message without a DeliveryStatus row for this
    # device has no recipient_envelope for it either. The store_message_v2
    # path fans out DeliveryStatus rows for every device active at publish
    # time; devices enrolled AFTER a publish won't have a row and
    # therefore won't see undecryptable messages.
    #
    # `dismissed_at.is_(None)` still drops cross-device dismissed rows so
    # that machinery survives the join change.
    query = (
        select(Message)
        .join(
            DeliveryStatus,
            and_(
                DeliveryStatus.message_id == Message.id,
                DeliveryStatus.device_id == device_id,
            ),
        )
        .where(
            and_(
                Message.user_id == user_id,
                Message.expires_at > now,
                DeliveryStatus.dismissed_at.is_(None),
            ),
        )
        .order_by(Message.sent_at.asc())
        .limit(limit)
    )
    if since is not None:
        query = query.where(Message.sent_at > since)

    result = await db.execute(query)
    messages = list(result.scalars().all())
    next_since = messages[-1].sent_at if messages else since
    return messages, next_since


async def assert_device_belongs_to_user(
    db: AsyncSession, *, user_id: uuid.UUID, device_id: uuid.UUID
) -> Device:
    """Confirm the device exists and belongs to the authenticated user."""
    result = await db.execute(
        select(Device).where(
            and_(Device.id == device_id, Device.user_id == user_id),
        ),
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise DeviceOwnershipError(f"device {device_id} not found for this user")
    return device


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
