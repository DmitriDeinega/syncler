"""Message routes ג€” send, inbox, fetch single, dismiss.

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
from app.services.nonce_replay import record_nonce_or_reject
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Sender
from app.schemas import (
    InboxFeedResponseV2,
    LiveCardInboxItemV2,
    MessageInboxItemV2,
    MessageSendRequestV2,
    MessageSendResponse,
    RecipientEnvelopeWire,
    StaleRecipientSetError,
    decode_base64,
)
from app.services.cards import get_live_cards_for_user
from app.services.envelopes_v2 import (
    RecipientSetError,
    RecipientSetOK,
    build_event_envelope_bytes,
    classify_recipient_set,
    parse_v2_pointer,
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
    store_message_v2,
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


@router.post("/send", response_model=MessageSendResponse, status_code=status.HTTP_201_CREATED)
async def send_message(
    payload: MessageSendRequestV2,
    request: Request,
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> MessageSendResponse:
    """Phase 9b V2 publish (spec §11.4). Per-recipient HPKE envelopes,
    Ed25519 signature over the full sorted envelope (§11.8),
    recipient-set classifier (§11.10), nonce-replay table on
    payload_nonce.
    """
    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    # Spec §11.8: verify the Ed25519 envelope signature BEFORE trusting any
    # envelope field for routing/storage/HPKE info reconstruction
    # (Codex 127 guardrail #4).
    envelope_bytes = build_event_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)
    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature")

    # Per-sender rate limit AFTER signature verification.
    request.state.sender_id = str(payload.sender_id)
    request.state.user_id = str(payload.user_id)
    from app.middleware.rate_limit import check_rate_limit
    from app.middleware.rate_limit_config import RATE_LIMITS
    await check_rate_limit(db, request, RATE_LIMITS["message_send"])
    await check_rate_limit(db, request, RATE_LIMITS["message_send_user_hour"])

    # Nonce-replay check on payload_nonce (the AES-GCM nonce).
    payload_nonce_bytes = decode_base64(
        payload.payload_nonce, field_name="payload_nonce", exact=12
    )
    if not await record_nonce_or_reject(db, payload.sender_id, payload_nonce_bytes):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used")

    # Recipient-set classifier (§11.10). Runs in the same transaction
    # as store_message_v2 below so the directory_version + active set
    # are consistent (Codex 127 guardrail #3).
    classification = await classify_recipient_set(
        db,
        user_id=payload.user_id,
        recipient_envelopes=payload.recipient_envelopes,
        sender_directory_version=payload.recipient_directory_version,
    )
    if isinstance(classification, RecipientSetError):
        if classification.http_status == 409:
            body = StaleRecipientSetError(
                message=classification.message,
                current_directory_version=classification.current_directory_version,
                missing_device_ids=classification.missing_device_ids,
            )
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=body.model_dump(mode="json"),
                headers={
                    "X-Stale-Directory-Version": str(
                        classification.current_directory_version
                    ),
                },
            )
        raise HTTPException(
            status_code=classification.http_status,
            detail={
                "error": classification.code,
                "message": classification.message,
                "current_directory_version": classification.current_directory_version,
            },
        )

    try:
        message = await store_message_v2(db, payload=payload)
    except ExpiredEnvelopeError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except PairingMissingError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="no active pairing") from exc
    except PluginInactiveError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="plugin missing, revoked, or not owned by sender") from exc
    except NoActiveDeviceWithPluginError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="recipient has no active devices") from exc
    except NonceReplayError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used") from exc

    # V4 #21 — fire FCM only if the plugin opted in via
    # ``notif_message``. The default is True so existing plugins keep
    # firing notifications; a plugin can publish with notif_message
    # = false to silence the FCM wake-up entirely.
    from app.models import Plugin
    from sqlalchemy import select as sql_select
    plugin_row = (await db.execute(
        sql_select(Plugin).where(Plugin.id == message.plugin_id)
    )).scalar_one_or_none()
    if plugin_row is None or plugin_row.notif_message:
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


@router.get("/inbox", response_model=InboxFeedResponseV2)
async def inbox(
    since: datetime | None = Query(None),
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> InboxFeedResponseV2:
    """Phase 9b V2 inbox feed (spec §11.4 + §11.5). Returns the FULL
    V2 envelope for each message / live card so the device can verify
    the Ed25519 signature, pick its own recipient_envelope by
    device_id, HPKE-open the CEK, and AES-GCM-decrypt the payload.
    """
    await touch_device_last_seen(db, device_id=ctx.device.id)

    messages, next_since = await inbox_for_device(
        db,
        user_id=ctx.user.id,
        device_id=ctx.device.id,
        since=since,
    )

    live_cards = await get_live_cards_for_user(db, user_id=ctx.user.id)

    plugin_ids = list({m.plugin_id for m in messages} | {c.plugin_id for c in live_cards})
    identifier_by_id: dict[uuid.UUID, str] = {}
    if plugin_ids:
        from app.models import Plugin
        from sqlalchemy import select as sql_select
        rows = await db.execute(
            sql_select(Plugin.id, Plugin.plugin_identifier).where(Plugin.id.in_(plugin_ids))
        )
        identifier_by_id = {row[0]: row[1] for row in rows.all()}

    items: list[MessageInboxItemV2 | LiveCardInboxItemV2] = []

    for m in messages:
        items.append(_message_to_inbox_item_v2(m, identifier_by_id.get(m.plugin_id, "")))

    # V3 #16: fetch persisted patches for the live cards in
    # this response so devices can apply them in order before
    # rendering. Bulk query keyed by (plugin_row_id, card_id,
    # base_seq) — patches with base_seq != current card_seq
    # are obsolete and intentionally filtered out (the next
    # cards.upsert purges them too).
    patches_by_card: dict = {}
    if live_cards:
        from app.models import CardPatch
        from sqlalchemy import select as sql_select, and_, or_
        ors = [
            and_(
                CardPatch.plugin_row_id == c.plugin_id,
                CardPatch.card_id == c.id,
                CardPatch.base_seq == c.sequence_number,
            )
            for c in live_cards
        ]
        patches_query = sql_select(CardPatch).where(or_(*ors)).order_by(
            CardPatch.card_id, CardPatch.patch_seq
        )
        patch_rows = (await db.execute(patches_query)).scalars().all()
        for p in patch_rows:
            patches_by_card.setdefault(p.card_id, []).append(p)

    for c in live_cards:
        items.append(
            _live_card_to_inbox_item_v2(
                c,
                identifier_by_id.get(c.plugin_id, ""),
                patches=patches_by_card.get(c.id, []),
            )
        )

    return InboxFeedResponseV2(items=items, next_since=next_since)


@router.get("/{message_id}", response_model=MessageInboxItemV2)
async def get_message(
    message_id: uuid.UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> MessageInboxItemV2:
    try:
        message = await get_message_for_user(db, user_id=ctx.user.id, message_id=message_id)
    except MessageNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="message not found") from exc
    from app.models import Plugin
    from sqlalchemy import select as sql_select
    plugin_row = await db.execute(
        sql_select(Plugin.plugin_identifier).where(Plugin.id == message.plugin_id)
    )
    identifier = plugin_row.scalar_one_or_none() or ""
    return _message_to_inbox_item_v2(message, identifier)


@router.post("/{message_id}/dismiss", status_code=status.HTTP_204_NO_CONTENT)
async def dismiss_message(
    message_id: uuid.UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> Response:
    # Device comes from the JWT ג€” no query param, no ownership check (the
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
    # always sends the event ג€” devices decide locally whether to act on it.
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


def _message_to_inbox_item_v2(message, plugin_identifier: str) -> MessageInboxItemV2:  # noqa: ANN001
    """Phase 9b V2 inbox projection (spec §11.4). Reconstructs the full
    V2 envelope from the stored pointer; device gets every recipient's
    envelope (full fanout) so signature verification can reconstruct
    the canonical signed envelope bytes byte-for-byte.
    """
    fields = parse_v2_pointer(message.encrypted_body_pointer)
    return MessageInboxItemV2(
        id=message.id,
        sender_id=message.sender_id,
        plugin_id=message.plugin_id,
        plugin_identifier=plugin_identifier,
        min_plugin_version=message.min_plugin_version,
        payload_nonce=fields.payload_nonce,
        payload_ciphertext=fields.payload_ciphertext,
        recipient_envelopes=[
            RecipientEnvelopeWire(**env) for env in fields.recipient_envelopes
        ],
        recipient_directory_version=fields.recipient_directory_version,
        envelope_signature=fields.envelope_signature,
        sent_at=message.sent_at,
        expires_at=message.expires_at,
    )


def _live_card_to_inbox_item_v2(
    card,  # noqa: ANN001
    plugin_identifier: str,
    patches: list | None = None,  # noqa: ANN001
) -> LiveCardInboxItemV2:
    """Phase 9b V2 live-card projection (spec §11.5).

    V3 #16: optional `patches` argument carries CardPatch rows
    for the current card_seq so the device can catch up after
    being offline during the live broadcast. Spec:
    docs/live-card-patch.md "Catch-up surface".
    """
    fields = parse_v2_pointer(card.encrypted_body_pointer)
    from app.schemas import LiveCardPatchInboxItem
    patch_items = [
        LiveCardPatchInboxItem(
            base_seq=p.base_seq,
            patch_seq=p.patch_seq,
            envelope_json=p.envelope_json,
        )
        for p in (patches or [])
    ]
    return LiveCardInboxItemV2(
        id=card.id,
        sender_id=card.sender_id,
        plugin_id=card.plugin_id,
        plugin_identifier=plugin_identifier,
        min_plugin_version=card.min_plugin_version,
        card_key=card.card_key,
        card_type=card.card_type,
        sequence_number=card.sequence_number,
        payload_nonce=fields.payload_nonce,
        payload_ciphertext=fields.payload_ciphertext,
        recipient_envelopes=[
            RecipientEnvelopeWire(**env) for env in fields.recipient_envelopes
        ],
        recipient_directory_version=fields.recipient_directory_version,
        envelope_signature=fields.envelope_signature,
        updated_at=card.updated_at,
        expires_at=card.expires_at,
        patches=patch_items,
    )
