from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Device, User


class DeviceNotFoundError(Exception):
    pass


class DeviceEncryptionKeyNotSetError(Exception):
    """Raised by the sender directory fetch path if a device row has
    no encryption_public_key registered. The server skips it from the
    active set (spec §11.10) — this exception only surfaces in code
    paths that need the X25519 key explicitly."""


async def _bump_device_directory_version(db: AsyncSession, *, user_id: UUID) -> None:
    """Phase 9b helper: increment users.device_directory_version.

    Called on enrollment, revocation, and encryption_public_key
    rotation. Spec §11.9. Must run in the same DB transaction as
    the underlying device mutation so the version bump and the
    visible device-set change commit atomically — preserving the
    strongly-consistent directory_version guarantee senders depend
    on for the publish-time recipient_directory_version check.
    """
    await db.execute(
        update(User)
        .where(User.id == user_id)
        .values(device_directory_version=User.device_directory_version + 1)
    )


async def enroll_device(
    db: AsyncSession,
    *,
    user_id: UUID,
    public_key: bytes,
    encryption_public_key: bytes,
    fcm_token: str | None,
) -> Device:
    """Phase 9b: enroll the device WITH its X25519 encryption pubkey.

    Both keys are mandatory in V2 — the V1 enroll path is gone (V0.1
    dev-mode tolerates the migration; existing devices re-enroll).
    """
    now = datetime.now(UTC)
    device = Device(
        id=uuid4(),
        user_id=user_id,
        public_key=public_key,
        encryption_public_key=encryption_public_key,
        fcm_token=fcm_token,
        created_at=now,
        # Seed last_seen at enroll so freshly-enrolled rows surface as
        # "Last seen: now" in the host's Settings list. Inbox polls bump
        # this on every call (see ``touch_device_last_seen``).
        last_seen=now,
        updated_at=now,
    )
    db.add(device)
    await _bump_device_directory_version(db, user_id=user_id)
    await db.commit()
    await db.refresh(device)
    return device


async def rotate_device_encryption_key(
    db: AsyncSession,
    *,
    user_id: UUID,
    device_id: UUID,
    encryption_public_key: bytes,
) -> Device:
    """Phase 9b: rotate the X25519 pubkey for the caller's own device.

    Auth layer guarantees the device_id matches the calling JWT's
    `did` claim — the router calls this with ``device_id=ctx.device.id``
    so the user can only rotate THEIR OWN device's key. Bumps
    `Device.updated_at` so the next sender directory fetch reflects
    the change, and `User.device_directory_version` so any
    in-progress publish gets a 409 stale_recipient_set on its next
    attempt.
    """
    result = await db.execute(
        select(Device).where(Device.id == device_id, Device.user_id == user_id)
    )
    device = result.scalar_one_or_none()
    if device is None:
        raise DeviceNotFoundError
    device.encryption_public_key = encryption_public_key
    device.updated_at = datetime.now(UTC)
    await _bump_device_directory_version(db, user_id=user_id)
    await db.commit()
    await db.refresh(device)
    return device


async def get_device_directory(
    db: AsyncSession,
    *,
    user_id: UUID,
) -> tuple[int, list[Device]]:
    """Phase 9b sender directory fetch (spec §11.9). Returns
    (directory_version, [active devices with non-NULL encryption_public_key]).

    Caller is the sender directory endpoint — it ALSO verifies the
    Pairing(sender_id, user_id) is active before invoking this.
    The single transaction guarantees the version + set are
    consistent (Codex 127 guardrail #3).
    """
    user_row = await db.execute(
        select(User.device_directory_version).where(User.id == user_id)
    )
    version = user_row.scalar_one()
    devices_row = await db.execute(
        select(Device)
        .where(
            Device.user_id == user_id,
            Device.revoked_at.is_(None),
            Device.encryption_public_key.is_not(None),
        )
        .order_by(Device.created_at.asc())
    )
    return version, list(devices_row.scalars().all())


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
    device.updated_at = device.revoked_at
    # Phase 9b: revoke shrinks the active set, so senders need to
    # see the new directory_version on their next publish. Bump in
    # the same transaction so a publish racing this revoke sees
    # either both the old or both the new state.
    await _bump_device_directory_version(db, user_id=user_id)
    await db.commit()


async def list_devices(db: AsyncSession, *, user_id: UUID) -> list[Device]:
    result = await db.execute(select(Device).where(Device.user_id == user_id).order_by(Device.created_at.asc()))
    return list(result.scalars().all())
