"""Push delivery via Firebase Cloud Messaging (FCM).

The platform is content-blind: the FCM payload contains only opaque metadata
(``message_id``, ``plugin_id``, ``min_plugin_version``). The device wakes,
fetches the encrypted body via ``GET /v1/messages/{id}``, decrypts locally,
and asks the plugin to render the notification.

The Firebase Admin SDK is initialized lazily on first ``push_to_devices``
call. If ``FIREBASE_SERVICE_ACCOUNT_PATH`` is unset the module operates in
no-op development mode and logs delivery intent.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.models import DeliveryStatus, Device, Message

logger = logging.getLogger(__name__)

_firebase_app: Any | None = None
_initialized = False


@dataclass
class FcmResult:
    device_id: uuid.UUID
    delivered: bool
    error: str | None = None


def _ensure_firebase(settings: Settings) -> Any | None:
    """Lazy-initialize the Firebase Admin app.

    Returns the firebase app (or None in dev mode). Never raises on missing
    creds — instead degrades to dev-mode logging so local dev works without
    a Firebase project.
    """
    global _firebase_app, _initialized
    if _initialized:
        return _firebase_app

    _initialized = True
    if not settings.firebase_service_account_path:
        logger.info("FCM not configured; running in dev-mode no-op")
        return None

    try:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(settings.firebase_service_account_path)
        _firebase_app = firebase_admin.initialize_app(cred)
        return _firebase_app
    except Exception as exc:
        logger.warning("failed to initialize Firebase Admin: %s", exc)
        return None


async def push_message_to_user_devices(
    db: AsyncSession,
    *,
    message: Message,
    settings: Settings | None = None,
) -> list[FcmResult]:
    """Fan out a freshly-stored message to each device's FCM token."""
    settings = settings or get_settings()
    app = _ensure_firebase(settings)

    result = await db.execute(
        select(Device).where(
            Device.user_id == message.user_id,
            Device.revoked_at.is_(None),
            Device.fcm_token.is_not(None),
        ),
    )
    devices = list(result.scalars().all())
    if not devices:
        return []

    payload = {
        "type": "message",
        "message_id": str(message.id),
        "plugin_id": str(message.plugin_id),
        "min_plugin_version": message.min_plugin_version or "",
    }

    outcomes: list[FcmResult] = []
    for device in devices:
        outcome = _deliver(app, device, payload)
        outcomes.append(outcome)
        if not outcome.delivered and outcome.error == "invalid_token":
            device.fcm_token = None

    # Record delivery timestamps for those that succeeded
    if any(o.delivered for o in outcomes):
        delivered_at = datetime.now(UTC)
        result = await db.execute(
            select(DeliveryStatus).where(DeliveryStatus.message_id == message.id),
        )
        delivery_rows = {row.device_id: row for row in result.scalars().all()}
        for outcome in outcomes:
            if outcome.delivered and outcome.device_id in delivery_rows:
                delivery_rows[outcome.device_id].delivered_at = delivered_at

    await db.commit()
    return outcomes


async def push_card_event_to_user_devices(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    plugin_row_id: uuid.UUID,
    card_key: str,
    event_type: str,  # "card_arrived" or "card_updated"
    min_plugin_version: str | None = None,
    settings: Settings | None = None,
) -> list[FcmResult]:
    """V4 #21 — fan out a live-card lifecycle event to the user's devices.

    Server still stays content-blind. The FCM payload carries
    metadata only (plugin row id, card_key, event type); the device
    looks up the actual card via the inbox refresh that the FCM
    triggers, decrypts locally, and asks the plugin's
    `getNotification(event)` hook whether to actually post a
    notification.

    Triad 169 wake-up policy lives on the Plugin row; CALLERS of
    this helper are responsible for checking
    `plugin.notif_card_arrived` / `plugin.notif_card_updated`
    before invoking. This keeps the helper a pure delivery
    primitive.
    """
    settings = settings or get_settings()
    app = _ensure_firebase(settings)

    result = await db.execute(
        select(Device).where(
            Device.user_id == user_id,
            Device.revoked_at.is_(None),
            Device.fcm_token.is_not(None),
        ),
    )
    devices = list(result.scalars().all())
    if not devices:
        return []

    payload = {
        "type": event_type,
        "plugin_id": str(plugin_row_id),
        "card_key": card_key,
        "min_plugin_version": min_plugin_version or "",
    }
    return [_deliver(app, device, payload) for device in devices]


async def push_dismiss_to_other_devices(
    db: AsyncSession,
    *,
    message: Message,
    dismissing_device_id: uuid.UUID,
    settings: Settings | None = None,
) -> list[FcmResult]:
    """Fan out a dismiss event to other devices when plugin behavior == DISMISS_ALL."""
    settings = settings or get_settings()
    app = _ensure_firebase(settings)

    result = await db.execute(
        select(Device).where(
            Device.user_id == message.user_id,
            Device.id != dismissing_device_id,
            Device.revoked_at.is_(None),
            Device.fcm_token.is_not(None),
        ),
    )
    devices = list(result.scalars().all())
    if not devices:
        return []

    payload = {
        "type": "dismiss",
        "message_id": str(message.id),
        "plugin_id": str(message.plugin_id),
    }

    return [_deliver(app, device, payload) for device in devices]


def _deliver(app: Any | None, device: Device, payload: dict[str, str]) -> FcmResult:
    if app is None:
        logger.info(
            "FCM[dev] would deliver to device=%s payload=%s", device.id, payload
        )
        return FcmResult(device_id=device.id, delivered=True)

    try:
        from firebase_admin import messaging

        message = messaging.Message(
            data={k: str(v) for k, v in payload.items()},
            token=device.fcm_token,
            android=messaging.AndroidConfig(priority="high"),
        )
        messaging.send(message, app=app)
        return FcmResult(device_id=device.id, delivered=True)
    except Exception as exc:
        logger.warning("FCM delivery failed for device=%s: %s", device.id, exc)
        is_invalid = "registration-token-not-registered" in str(exc).lower() or "invalid-argument" in str(exc).lower()
        return FcmResult(
            device_id=device.id,
            delivered=False,
            error="invalid_token" if is_invalid else "transient",
        )
