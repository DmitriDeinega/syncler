"""Live card routes — upsert, delete."""

from __future__ import annotations

import base64
import json
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.aead import assemble_envelope
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Sender
from app.schemas import (
    LiveCardDeleteRequest,
    LiveCardItem,
    LiveCardUpsertRequest,
    decode_base64,
)
from app.services.cards import (
    ExpiredEnvelopeError,
    PairingMissingError,
    PluginInactiveError,
    SequenceNumberRegressionError,
    delete_live_card,
    upsert_live_card,
)
from app.services.events import get_event_bus
from app.services.nonce_replay import record_nonce_or_reject
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)

router = APIRouter(tags=["cards"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _build_upsert_envelope_bytes(payload: LiveCardUpsertRequest) -> bytes:
    """Build the canonical envelope for live card upsert (binds metadata).

    `sequence_number` is kept as an int (not stringified) so the canonical
    form matches the SDK side, which signs `canonical_json` of a dict whose
    `sequence_number` is also int. Casting to str here would emit
    `"sequence_number":"42"` while the SDK emits `"sequence_number":42`,
    breaking signature verification on every upsert.
    """
    return assemble_envelope(
        {
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
            "plugin_id": str(payload.plugin_id),
            "card_key": payload.card_key,
            "card_type": "live",
            "expires_at": payload.expires_at.isoformat().replace("+00:00", "Z"),
            "encrypted_payload": payload.encrypted_payload,
            "nonce": payload.nonce,
            "sequence_number": payload.sequence_number,
        }
    )


@router.post("/upsert", status_code=status.HTTP_201_CREATED)
async def upsert(
    payload: LiveCardUpsertRequest,
    request: Request,
    _: None = Depends(rate_limit("card_upsert_ip")),
    db: AsyncSession = Depends(get_db),
) -> LiveCardItem:
    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    envelope_bytes = _build_upsert_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)

    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature")

    # Per-card rate limit: 1 per second per (sender, user, card_key).
    request.state.sender_id = str(payload.sender_id)
    request.state.user_id = str(payload.user_id)
    request.state.card_key = payload.card_key
    from app.middleware.rate_limit import check_rate_limit
    from app.middleware.rate_limit_config import RATE_LIMITS
    await check_rate_limit(db, request, RATE_LIMITS["card_upsert"])

    # Phase 7: replay protection for live-card upserts. Sequence-number
    # CAS in upsert_live_card defends against most replay scenarios, but
    # nothing prevents resurrecting a card with an older sequence after
    # the current row is deleted. The shared nonce registry closes that
    # gap. Same transactional semantics as messages.py — the insert
    # commits with upsert_live_card's commit, or rolls back together.
    nonce_bytes = decode_base64(payload.nonce, field_name="nonce", exact=12)
    if not await record_nonce_or_reject(db, payload.sender_id, nonce_bytes):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="nonce already used")

    try:
        card = await upsert_live_card(
            db,
            sender_id=payload.sender_id,
            user_id=payload.user_id,
            plugin_id=payload.plugin_id,
            card_key=payload.card_key,
            encrypted_payload=decode_base64(payload.encrypted_payload, field_name="encrypted_payload", minimum=16),
            nonce=nonce_bytes,
            sequence_number=payload.sequence_number,
            expires_at=payload.expires_at,
        )
    except PluginInactiveError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=str(exc)) from exc
    except PairingMissingError as exc:
        # Mirrors the messages route: no active pairing → 410 Gone. The
        # sender knows what to do (re-pair) and we don't leak whether the
        # user_id exists at all (404 vs 410 would differ).
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=str(exc)) from exc
    except ExpiredEnvelopeError as exc:
        # expires_at <= now OR exceeds the 48h server-enforced cap. 400 Bad
        # Request — the caller can fix and resubmit. Closes Codex 63 RED
        # (router-layer mapping for the service-layer TTL check).
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except SequenceNumberRegressionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    # SSE fanout
    from app.models import Plugin
    from sqlalchemy import select as sql_select
    plugin_row = await db.execute(sql_select(Plugin.plugin_identifier).where(Plugin.id == card.plugin_id))
    identifier = plugin_row.scalar_one_or_none() or ""

    item = LiveCardItem(
        id=card.id,
        sender_id=card.sender_id,
        plugin_id=card.plugin_id,
        plugin_identifier=identifier,
        card_key=card.card_key,
        encrypted_payload=_b64(card.encrypted_payload),
        nonce=_b64(card.nonce),
        sequence_number=card.sequence_number,
        updated_at=card.updated_at,
        expires_at=card.expires_at,
    )
    
    await get_event_bus().publish_to_user(
        user_id=card.user_id,
        event_type="card.upsert",
        data=item.model_dump(mode="json"),
    )
    
    return item


def _build_delete_envelope_bytes(payload: LiveCardDeleteRequest) -> bytes:
    """Canonical envelope for live card delete (Codex consultation 62 RED #5).

    Binds `user_id` so a delete signature for one user's card cannot be
    replayed against another user's card with the same (sender, card_key).
    Uses the same `assemble_envelope` canonicalization as the upsert path
    so signing libraries on the sender side stay consistent across the
    two routes.
    """
    return assemble_envelope(
        {
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
            "card_key": payload.card_key,
        }
    )


@router.post("/delete", status_code=status.HTTP_204_NO_CONTENT)
async def delete(
    payload: LiveCardDeleteRequest,
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> Response:
    try:
        sender: Sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    envelope_bytes = _build_delete_envelope_bytes(payload)
    signature = decode_base64(payload.envelope_signature, field_name="envelope_signature", exact=64)

    if not verify_message_envelope(sender.public_key, envelope_bytes, signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid envelope signature")

    await delete_live_card(
        db,
        sender_id=payload.sender_id,
        user_id=payload.user_id,
        card_key=payload.card_key,
    )

    # SSE fanout to all of the user's devices so they drop the card locally.
    # Idempotent: if the card didn't exist, delete_live_card no-ops; we still
    # publish so any stale local copy on another device clears (defense in
    # depth — the upsert path could have raced an earlier delete).
    await get_event_bus().publish_to_user(
        user_id=payload.user_id,
        event_type="card.delete",
        data={
            "sender_id": str(payload.sender_id),
            "card_key": payload.card_key,
        },
    )

    return Response(status_code=status.HTTP_204_NO_CONTENT)
