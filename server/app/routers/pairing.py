"""Pairing routes — sender initiates, user completes, either revokes."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import current_user
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.models import Pairing, User
from app.schemas import (
    PairingCompleteRequest,
    PairingCompleteResponse,
    PairingInitiateRequest,
    PairingInitiateResponse,
    PairingItem,
    PairingPreviewResponse,
    decode_base64,
)
from app.services.pairing import (
    PairingAlreadyExistsError,
    PairingTokenConsumedError,
    PairingTokenExpiredError,
    PairingTokenNotFoundError,
    complete_pairing,
    fingerprint_for_public_key,
    initiate_pairing,
    preview_pending,
    revoke_pairing,
    sender_metadata_for_response,
)
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)

router = APIRouter(tags=["pairing"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _b64url(value: bytes) -> str:
    """URL-safe base64 (no padding) for use in URLs without percent-encoding."""
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str) -> bytes:
    pad = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + pad)


def _initiate_envelope_bytes(payload: PairingInitiateRequest) -> bytes:
    """Canonical bytes the sender signs to authenticate the initiate call."""
    envelope = {
        "sender_id": str(payload.sender_id),
        "ttl_seconds": int(payload.ttl_seconds),
        "metadata": payload.metadata or {},
    }
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


@router.post("/initiate", response_model=PairingInitiateResponse, status_code=status.HTTP_201_CREATED)
async def initiate(
    payload: PairingInitiateRequest,
    request: Request,
    _: None = Depends(rate_limit("message_send_ip")),  # pre-auth IP bucket
    db: AsyncSession = Depends(get_db),
) -> PairingInitiateResponse:
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    signature = decode_base64(payload.signature, field_name="signature", exact=64)
    if not verify_message_envelope(sender.public_key, _initiate_envelope_bytes(payload), signature):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid sender signature")

    # Post-auth per-sender bucket so an authenticated sender can't outpace us.
    request.state.sender_id = str(sender.id)
    await check_rate_limit(db, request, RATE_LIMITS["pairing_initiate"])

    pending = await initiate_pairing(
        db,
        sender_id=sender.id,
        ttl_seconds=payload.ttl_seconds,
        metadata=payload.metadata,
    )

    base_url = str(request.base_url).rstrip("/")
    broker_url = f"{base_url}/v1/pairing/complete?token={_b64url(pending.pairing_token)}"

    return PairingInitiateResponse(
        pairing_id=pending.id,
        pairing_token=_b64url(pending.pairing_token),
        broker_url=broker_url,
        expires_at=pending.expires_at,
    )


@router.get("/preview", response_model=PairingPreviewResponse)
async def preview(
    token: str,
    db: AsyncSession = Depends(get_db),
) -> PairingPreviewResponse:
    """Non-consuming sender-identity lookup. Lets the device show fingerprint
    + name BEFORE the user confirms; only ``/complete`` consumes the token.
    """
    # Token comes URL-safe base64 (no padding); accept standard b64 too.
    try:
        raw = _b64url_decode(token) if not token.endswith("=") else base64.b64decode(token)
    except Exception as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid token encoding") from exc
    if len(raw) != 32:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="pairing token must be 32 bytes")
    try:
        pending, sender = await preview_pending(db, pairing_token=raw)
    except PairingTokenNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="pairing token not found") from exc
    except PairingTokenExpiredError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="pairing token expired") from exc
    except PairingTokenConsumedError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="pairing token already consumed") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    meta = sender_metadata_for_response(sender)
    return PairingPreviewResponse(
        sender_id=sender.id,
        sender_name=sender.name,
        sender_public_key=_b64(sender.public_key),
        sender_public_key_fingerprint=meta["fingerprint"] or "",
        sender_name_hash=meta["name_hash"] or "",
        expires_at=pending.expires_at,
    )


@router.post("/complete", response_model=PairingCompleteResponse, status_code=status.HTTP_201_CREATED)
async def complete(
    payload: PairingCompleteRequest,
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> PairingCompleteResponse:
    # Accept URL-safe + standard base64.
    raw = payload.pairing_token
    try:
        if "-" in raw or "_" in raw or not raw.endswith("="):
            token = _b64url_decode(raw)
        else:
            token = base64.b64decode(raw, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="pairing_token must be valid base64") from exc
    if len(token) != 32:
        raise HTTPException(status_code=400, detail="pairing_token must decode to 32 bytes")

    encrypted_initial_state = decode_base64(
        payload.encrypted_initial_state, field_name="encrypted_initial_state", minimum=16
    )
    try:
        pairing, sender, _ = await complete_pairing(
            db,
            user=user,
            pairing_token=token,
            encrypted_initial_state=encrypted_initial_state,
        )
    except PairingTokenNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="pairing token not found") from exc
    except PairingTokenExpiredError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="pairing token expired") from exc
    except PairingTokenConsumedError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="pairing token already consumed") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc
    except PairingAlreadyExistsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="already paired") from exc

    meta = sender_metadata_for_response(sender)
    return PairingCompleteResponse(
        pairing_id=pairing.id,
        sender_id=sender.id,
        sender_name=sender.name,
        sender_public_key=_b64(sender.public_key),
        sender_public_key_fingerprint=meta["fingerprint"] or "",
        sender_name_hash=meta["name_hash"] or "",
        paired_at=pairing.created_at,
    )


@router.post("/{pairing_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(
    pairing_id: uuid.UUID,
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    try:
        await revoke_pairing(db, user=user, pairing_id=pairing_id)
    except PairingTokenNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="pairing not found") from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("", response_model=list[PairingItem])
async def list_pairings(
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> list[PairingItem]:
    result = await db.execute(
        select(Pairing).where(Pairing.user_id == user.id).order_by(Pairing.created_at.desc()),
    )
    return [PairingItem.model_validate(p) for p in result.scalars().all()]
