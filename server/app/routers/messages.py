"""Message routes — send, inbox, fetch single, dismiss.

Sender authentication for ``POST /send`` uses an Ed25519 envelope signature
verified against the registered sender's public key. Users authenticate
inbox/fetch/dismiss routes via the JWT session.
"""

from __future__ import annotations

import base64
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, current_auth_context
from app.crypto.aead import assemble_envelope
from app.crypto.nonce import get_global_registry
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Sender
from app.schemas import (
    MessageDetailResponse,
    MessageInboxItem,
    MessageInboxResponse,
    MessageSendRequest,
    MessageSendResponse,
    decode_base64,
)
from app.services.messages import (
    ExpiredEnvelopeError,
    MessageNotFoundError,
    NoActiveDeviceWithPluginError,
    NonceReplayError,
    PairingMissingError,
    PluginInactiveError,
    get_message_for_user,
    inbox_for_device,
    mark_dismissed,
    parse_pointer,
    store_message,
)
from app.services.devices import touch_device_last_seen
from app.services.events import get_event_bus
from app.services.push import push_dismiss_to_other_devices, push_message_to_user_devices
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)

router = APIRouter(tags=["messages"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _build_envelope_bytes(payload: MessageSendRequest) -> bytes:
    """Build the canonical 7-field envelope the sender signs (matches crypto-spec §4.1)."""
    if payload.expires_at is None:
        raise HTTPException(status_code=400, detail="expires_at is required")
    return assemble_envelope(
        {
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
            "plugin_id": str(payload.plugin_id),
            "min_plugin_version": payload.min_plugin_version or "",
            "expires_at": payload.expires_at.isoformat().replace("+00:00", "Z"),
            "encrypted_body": payload.encrypted_body,
            "nonce": payload.nonce,
        }
    )


@router.post("/send", response_model=MessageSendResponse, status_code=status.HTTP_201_CREATED)
async def send_message(
    payload: MessageSendRequest,
    request: Request,
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> MessageSendResponse:
    if payload.expires_at is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="expires_at is required")

    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    envelope_bytes = _build_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)

    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature")

    # Per-sender rate limit AFTER signature verification (so spammers can't
    # inflate someone else's bucket by spoofing the body sender_id).
    request.state.sender_id = str(payload.sender_id)
    request.state.user_id = str(payload.user_id)
    from app.middleware.rate_limit import check_rate_limit
    from app.middleware.rate_limit_config import RATE_LIMITS
    await check_rate_limit(db, request, RATE_LIMITS["message_send"])
    await check_rate_limit(db, request, RATE_LIMITS["message_send_user_hour"])

    # Replay check AFTER signature verification (so attackers can't OOM the
    # nonce registry by spamming junk). We check first to short-circuit,
    # then re-mark after successful store_message so failed stores don't
    # burn the nonce.
    nonce_bytes = decode_base64(payload.nonce, field_name="nonce", exact=12)
    registry = get_global_registry()
    sender_key = str(payload.sender_id).encode("utf-8")
    if registry.has(sender_key, nonce_bytes):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used")

    try:
        message = await store_message(
            db,
            sender_id=payload.sender_id,
            user_id=payload.user_id,
            plugin_id=payload.plugin_id,
            encrypted_body=decode_base64(payload.encrypted_body, field_name="encrypted_body", minimum=16),
            nonce=nonce_bytes,
            envelope_signature=signature,
            min_plugin_version=payload.min_plugin_version,
            expires_at=payload.expires_at,
        )
    except ExpiredEnvelopeError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except PairingMissingError as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="no active pairing") from exc
    except PluginInactiveError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="plugin missing, revoked, or not owned by sender") from exc
    except NoActiveDeviceWithPluginError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="recipient has no active devices") from exc
    except NonceReplayError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used") from exc

    # Mark nonce as seen ONLY after store_message succeeds; failed stores
    # don't burn the nonce.
    registry.mark(sender_key, nonce_bytes)

    await push_message_to_user_devices(db, message=message)
    # SSE hint: nudge any device the recipient has in foreground to pull
    # /v1/messages/inbox now. Devices in background rely on the FCM
    # wakeup already triggered by push_message_to_user_devices.
    await get_event_bus().publish_to_user(
        user_id=message.user_id,
        event_type="inbox.changed",
        data={"message_id": str(message.id), "sent_at": message.sent_at.isoformat()},
    )
    return MessageSendResponse(message_id=message.id, expires_at=message.expires_at)


@router.get("/inbox", response_model=MessageInboxResponse)
async def inbox(
    since: datetime | None = Query(None),
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> MessageInboxResponse:
    # Device identity comes from the JWT — no separate query param, and
    # the auth dependency already verified the device is not revoked.
    # Bump last_seen so Settings → Devices shows fresh activity.
    await touch_device_last_seen(db, device_id=ctx.device.id)

    messages, next_since = await inbox_for_device(
        db,
        user_id=ctx.user.id,
        device_id=ctx.device.id,
        since=since,
    )
    # Project plugin_identifier for each message in one batch query.
    plugin_ids = list({m.plugin_id for m in messages})
    identifier_by_id: dict[uuid.UUID, str] = {}
    if plugin_ids:
        from app.models import Plugin
        from sqlalchemy import select as sql_select
        rows = await db.execute(
            sql_select(Plugin.id, Plugin.plugin_identifier).where(Plugin.id.in_(plugin_ids))
        )
        identifier_by_id = {row[0]: row[1] for row in rows.all()}
    items = [_message_to_inbox_item(m, identifier_by_id.get(m.plugin_id, "")) for m in messages]
    return MessageInboxResponse(messages=items, next_since=next_since)


@router.get("/{message_id}", response_model=MessageDetailResponse)
async def get_message(
    message_id: uuid.UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> MessageDetailResponse:
    try:
        message = await get_message_for_user(db, user_id=ctx.user.id, message_id=message_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="message not found") from exc
    # Fetch plugin_identifier for this one message.
    from app.models import Plugin
    from sqlalchemy import select as sql_select
    plugin_row = await db.execute(
        sql_select(Plugin.plugin_identifier).where(Plugin.id == message.plugin_id)
    )
    identifier = plugin_row.scalar_one_or_none() or ""
    return MessageDetailResponse(**_message_to_inbox_item(message, identifier).model_dump())


@router.post("/{message_id}/dismiss", status_code=status.HTTP_204_NO_CONTENT)
async def dismiss_message(
    message_id: uuid.UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> Response:
    # Device comes from the JWT — no query param, no ownership check (the
    # auth dependency already verified the device exists, belongs to the
    # user, and is not revoked).
    try:
        message = await get_message_for_user(db, user_id=ctx.user.id, message_id=message_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="message not found") from exc

    try:
        await mark_dismissed(db, message_id=message_id, device_id=ctx.device.id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="delivery row not found") from exc

    # Fan out the dismiss event to other devices. The plugin's dismissBehavior
    # is encoded on the device side (in the plugin manifest), but the platform
    # always sends the event — devices decide locally whether to act on it.
    await push_dismiss_to_other_devices(db, message=message, dismissing_device_id=ctx.device.id)
    # SSE hint: nudge OTHER foreground devices to update their local
    # dismiss state. The dismissing device knows already; we mark its id
    # so the client-side handler can no-op for self-originated events.
    await get_event_bus().publish_to_user(
        user_id=ctx.user.id,
        event_type="dismiss",
        data={"message_id": str(message_id), "source_device_id": str(ctx.device.id)},
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _message_to_inbox_item(message, plugin_identifier: str) -> MessageInboxItem:  # noqa: ANN001
    encrypted_body, nonce, envelope_signature = parse_pointer(message.encrypted_body_pointer)
    return MessageInboxItem(
        id=message.id,
        sender_id=message.sender_id,
        plugin_id=message.plugin_id,
        plugin_identifier=plugin_identifier,
        min_plugin_version=message.min_plugin_version,
        encrypted_body=_b64(encrypted_body),
        nonce=_b64(nonce),
        envelope_signature=_b64(envelope_signature),
        sent_at=message.sent_at,
        expires_at=message.expires_at,
    )
