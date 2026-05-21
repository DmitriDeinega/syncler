from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Device


class DeviceNotFoundError(Exception):
    pass


async def enroll_device(
    db: AsyncSession,
    *,
    user_id: UUID,
    public_key: bytes,
    fcm_token: str | None,
) -> Device:
    now = datetime.now(UTC)
    device = Device(
        id=uuid4(),
        user_id=user_id,
        public_key=public_key,
        fcm_token=fcm_token,
        created_at=now,
        # Seed last_seen at enroll so freshly-enrolled rows surface as
        # "Last seen: now" in the host's Settings list. Inbox polls bump
        # this on every call (see ``touch_device_last_seen``).
        last_seen=now,
    )
    db.add(device)
    await db.commit()
    await db.refresh(device)
    return device


async def touch_device_last_seen(db: AsyncSession, *, device_id: UUID) -> None:
    """Bump ``last_seen`` to now for an authenticated device hit.

    Called from request handlers that take a ``device_id`` parameter (currently
    the inbox poll) so the Settings tab on each phone shows the current
    device with a fresh timestamp. Silently no-ops on unknown ids so a stale
    client param doesn't crash the request.
    """
    result = await db.execute(select(Device).where(Device.id == device_id))
    device = result.scalar_one_or_none()
    if device is None:
        return
    device.last_seen = datetime.now(UTC)
    await db.commit()


async def revoke_device(db: AsyncSession, *, user_id: UUID, device_id: UUID) -> None:
    result = await db.execute(select(Device).where(Device.id == device_id, Device.user_id == user_id))
    device = result.scalar_one_or_none()
    if device is None:
        raise DeviceNotFoundError

    device.revoked_at = datetime.now(UTC)
    await db.commit()


async def list_devices(db: AsyncSession, *, user_id: UUID) -> list[Device]:
    result = await db.execute(select(Device).where(Device.user_id == user_id).order_by(Device.created_at.asc()))
    return list(result.scalars().all())
