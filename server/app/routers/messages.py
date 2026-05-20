"""Message routes — send, inbox, fetch single, dismiss.

Sender authentication for ``POST /send`` uses an Ed25519 envelope signature
verified against the registered sender's public key. Users authenticate
inbox/fetch/dismiss routes via the JWT session.
"""

from __future__ import annotations

import base64
import json
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import current_user
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Sender, User
from app.schemas import (
    MessageDetailResponse,
    MessageInboxItem,
    MessageInboxResponse,
    MessageSendRequest,
    MessageSendResponse,
    decode_base64,
)
from app.services.messages import (
    MessageNotFoundError,
    NoActiveDeviceWithPluginError,
    PairingMissingError,
    PluginInactiveError,
    get_message_for_user,
    inbox_for_device,
    mark_dismissed,
    parse_pointer,
    store_message,
)
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
    """Canonical envelope bytes for signature verification.

    The sender signs the JSON of the envelope WITHOUT the signature field,
    with sorted keys and compact separators. Server reconstructs the same
    bytes for verification.
    """
    envelope = {
        "sender_id": str(payload.sender_id),
        "user_id": str(payload.user_id),
        "plugin_id": str(payload.plugin_id),
        "encrypted_body": payload.encrypted_body,
        "nonce": payload.nonce,
        "min_plugin_version": payload.min_plugin_version or "",
    }
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


@router.post("/send", response_model=MessageSendResponse, status_code=status.HTTP_201_CREATED)
async def send_message(
    payload: MessageSendRequest,
    _: None = Depends(rate_limit("message_send")),
    db: AsyncSession = Depends(get_db),
) -> MessageSendResponse:
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

    try:
        message = await store_message(
            db,
            sender_id=payload.sender_id,
            user_id=payload.user_id,
            plugin_id=payload.plugin_id,
            encrypted_body=decode_base64(payload.encrypted_body, field_name="encrypted_body", minimum=16),
            nonce=decode_base64(payload.nonce, field_name="nonce", exact=12),
            envelope_signature=signature,
            min_plugin_version=payload.min_plugin_version,
            expires_at=payload.expires_at,
        )
    except PairingMissingError as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="no active pairing") from exc
    except PluginInactiveError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="plugin missing or revoked") from exc
    except NoActiveDeviceWithPluginError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="recipient has no active device") from exc

    await push_message_to_user_devices(db, message=message)
    return MessageSendResponse(message_id=message.id, expires_at=message.expires_at)


@router.get("/inbox", response_model=MessageInboxResponse)
async def inbox(
    since: datetime | None = Query(None),
    device_id: uuid.UUID | None = Query(None),
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> MessageInboxResponse:
    messages, next_since = await inbox_for_device(
        db,
        user_id=user.id,
        device_id=device_id or uuid.uuid4(),  # device_id is informational; inbox is per-user in V1
        since=since,
    )
    items = [_message_to_inbox_item(m) for m in messages]
    return MessageInboxResponse(messages=items, next_since=next_since)


@router.get("/{message_id}", response_model=MessageDetailResponse)
async def get_message(
    message_id: uuid.UUID,
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> MessageDetailResponse:
    try:
        message = await get_message_for_user(db, user_id=user.id, message_id=message_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="message not found") from exc
    return MessageDetailResponse(**_message_to_inbox_item(message).model_dump())


@router.post("/{message_id}/dismiss", status_code=status.HTTP_204_NO_CONTENT)
async def dismiss_message(
    message_id: uuid.UUID,
    device_id: uuid.UUID = Query(...),
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    try:
        message = await get_message_for_user(db, user_id=user.id, message_id=message_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="message not found") from exc

    try:
        await mark_dismissed(db, message_id=message_id, device_id=device_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="delivery row not found") from exc

    # Fan out the dismiss event to other devices. The plugin's dismissBehavior
    # is encoded on the device side (in the plugin manifest), but the platform
    # always sends the event — devices decide locally whether to act on it.
    await push_dismiss_to_other_devices(db, message=message, dismissing_device_id=device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


def _message_to_inbox_item(message) -> MessageInboxItem:  # noqa: ANN001
    encrypted_body, nonce, envelope_signature = parse_pointer(message.encrypted_body_pointer)
    return MessageInboxItem(
        id=message.id,
        sender_id=message.sender_id,
        plugin_id=message.plugin_id,
        min_plugin_version=message.min_plugin_version,
        encrypted_body=_b64(encrypted_body),
        nonce=_b64(nonce),
        envelope_signature=_b64(envelope_signature),
        sent_at=message.sent_at,
    )
