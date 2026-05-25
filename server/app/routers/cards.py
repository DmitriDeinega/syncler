"""Live card routes — Phase 9b V2 upsert + delete (spec §11.5, §11.6)."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Sender
from app.schemas import (
    LiveCardDeleteRequestV2,
    LiveCardInboxItemV2,
    LiveCardUpsertRequestV2,
    RecipientEnvelopeWire,
    StaleRecipientSetError,
    decode_base64,
)
from app.services.cards import (
    ExpiredEnvelopeError,
    MAX_TTL,
    PairingMissingError,
    PluginInactiveError,
    SequenceNumberRegressionError,
    delete_live_card_v2,
    upsert_live_card_v2,
)
from app.services.envelopes_v2 import (
    RecipientSetError,
    build_live_card_delete_envelope_bytes,
    build_live_card_upsert_envelope_bytes,
    classify_recipient_set,
    parse_v2_pointer,
)
from app.services.events import get_event_bus
from app.services.nonce_replay import record_nonce_or_reject
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)

router = APIRouter(tags=["cards"])


@router.post("/upsert", status_code=status.HTTP_201_CREATED, response_model=LiveCardInboxItemV2)
async def upsert(
    payload: LiveCardUpsertRequestV2,
    request: Request,
    _: None = Depends(rate_limit("card_upsert_ip")),
    db: AsyncSession = Depends(get_db),
) -> LiveCardInboxItemV2:
    """Phase 9b V2 live-card upsert (spec §11.5)."""
    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    envelope_bytes = build_live_card_upsert_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)
    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature"
        )

    request.state.sender_id = str(payload.sender_id)
    request.state.user_id = str(payload.user_id)
    request.state.card_key = payload.card_key
    from app.middleware.rate_limit import check_rate_limit
    from app.middleware.rate_limit_config import RATE_LIMITS
    await check_rate_limit(db, request, RATE_LIMITS["card_upsert"])

    payload_nonce_bytes = decode_base64(
        payload.payload_nonce, field_name="payload_nonce", exact=12
    )
    if not await record_nonce_or_reject(db, payload.sender_id, payload_nonce_bytes):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used")

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
                    "X-Stale-Directory-Version": str(classification.current_directory_version),
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
        card = await upsert_live_card_v2(db, payload=payload)
    except PluginInactiveError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=str(exc)) from exc
    except PairingMissingError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=str(exc)) from exc
    except ExpiredEnvelopeError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except SequenceNumberRegressionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    from app.models import Plugin
    from sqlalchemy import select as sql_select
    plugin_row = await db.execute(
        sql_select(Plugin.plugin_identifier).where(Plugin.id == card.plugin_id)
    )
    identifier = plugin_row.scalar_one_or_none() or ""

    fields = parse_v2_pointer(card.encrypted_body_pointer)
    item = LiveCardInboxItemV2(
        id=card.id,
        sender_id=card.sender_id,
        plugin_id=card.plugin_id,
        plugin_identifier=identifier,
        min_plugin_version=card.min_plugin_version,
        card_key=card.card_key,
        card_type=card.card_type,
        sequence_number=card.sequence_number,
        payload_nonce=fields.payload_nonce,
        payload_ciphertext=fields.payload_ciphertext,
        recipient_envelopes=[RecipientEnvelopeWire(**env) for env in fields.recipient_envelopes],
        recipient_directory_version=fields.recipient_directory_version,
        envelope_signature=fields.envelope_signature,
        updated_at=card.updated_at,
        expires_at=card.expires_at,
    )

    await get_event_bus().publish_to_user(
        user_id=card.user_id,
        event_type="card.upsert",
        data=item.model_dump(mode="json"),
    )
    return item


@router.post("/delete", status_code=status.HTTP_204_NO_CONTENT)
async def delete(
    payload: LiveCardDeleteRequestV2,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Phase 9b V2 live-card delete (spec §11.6).

    Delete envelope adds `plugin_id` over V1; the lookup now scopes by
    `(sender_id, user_id, plugin_id, card_key)` so a captured delete
    can't replay against a different plugin's card with the same
    `card_key`.
    """
    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    envelope_bytes = build_live_card_delete_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)
    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature"
        )

    # Same freshness + replay rules as the upsert path.
    now = datetime.now(UTC)
    if payload.expires_at <= now:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="expires_at is not in the future"
        )
    if payload.expires_at > now + MAX_TTL:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=(
                f"expires_at exceeds the {int(MAX_TTL.total_seconds() // 3600)}h live-card cap"
            ),
        )
    nonce_bytes = decode_base64(payload.nonce, field_name="nonce", exact=12)
    if not await record_nonce_or_reject(db, payload.sender_id, nonce_bytes):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="nonce already used"
        )

    await delete_live_card_v2(
        db,
        sender_id=payload.sender_id,
        user_id=payload.user_id,
        plugin_id=payload.plugin_id,
        card_key=payload.card_key,
    )
    # The service only commits when a row was actually deleted. The
    # nonce row we just recorded needs an explicit commit either way
    # so a replay-stalled-by-no-such-card path doesn't leak the nonce
    # back via rollback. Same close-the-replay-gap argument as V1
    # Phase 12.
    await db.commit()

    await get_event_bus().publish_to_user(
        user_id=payload.user_id,
        event_type="card.delete",
        data={
            "sender_id": str(payload.sender_id),
            "plugin_id": str(payload.plugin_id),
            "card_key": payload.card_key,
        },
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
