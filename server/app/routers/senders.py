"""Sender registration + V1.5 bootstrap key endpoints.

Minimal V1 surface — sender public-key registration is required before
``POST /v1/messages/send`` works. Full sender lifecycle (pairing, revocation,
sender-signed revoke) is M6.

V1.5 Phase 5a-2 adds `POST /v1/senders/me/bootstrap-key` for the
automated pairing flow (see `docs/crypto-spec.md §9`).
"""

from __future__ import annotations

import base64
import hashlib

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.schemas import (
    BootstrapKeyRegisterRequest,
    BootstrapKeyRegisterResponse,
    SenderRegisterRequest,
    SenderRegisterResponse,
    decode_base64,
)
from app.services.senders import (
    SenderAlreadyExistsError,
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
    register_sender,
)

router = APIRouter(tags=["senders"])


@router.post("/register", response_model=SenderRegisterResponse, status_code=status.HTTP_201_CREATED)
async def register(
    payload: SenderRegisterRequest,
    _: None = Depends(rate_limit("signup")),
    db: AsyncSession = Depends(get_db),
) -> SenderRegisterResponse:
    try:
        sender = await register_sender(
            db,
            public_key=decode_base64(payload.public_key, field_name="public_key", exact=32),
            name=payload.name,
            contact=payload.contact,
        )
    except SenderAlreadyExistsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="public key already registered") from exc

    return SenderRegisterResponse(sender_id=sender.id, created_at=sender.created_at)


@router.post(
    "/me/bootstrap-key",
    response_model=BootstrapKeyRegisterResponse,
    status_code=status.HTTP_201_CREATED,
)
async def register_bootstrap_key(
    payload: BootstrapKeyRegisterRequest,
    request: Request,
    # Pre-auth IP bucket: cheap pre-signature DoS protection.
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> BootstrapKeyRegisterResponse:
    """V1.5 Phase 5a-2: register or rotate the sender's X25519 bootstrap
    public key. The Ed25519 signature MUST be over the literal ASCII
    string ``"syncler-v1-bootstrap-key:"`` (24 bytes) followed by the
    raw 32-byte X25519 public key (NOT its base64 representation).

    Re-calling this endpoint rotates the key (overwrites the previous
    bootstrap key + signature on the sender row). Rotation history is
    not retained in V1.5 — single current value per sender.
    """
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="sender revoked") from exc

    bootstrap_key_raw = decode_base64(payload.bootstrap_key, field_name="bootstrap_key", exact=32)
    bootstrap_sig_raw = decode_base64(
        payload.bootstrap_key_signature, field_name="bootstrap_key_signature", exact=64,
    )
    sig_input = b"syncler-v1-bootstrap-key:" + bootstrap_key_raw
    if not verify_message_envelope(sender.public_key, sig_input, bootstrap_sig_raw):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid bootstrap key signature",
        )

    # Post-signature per-sender rate limit. Mirrors plugins/publish.
    request.state.sender_id = str(sender.id)
    await check_rate_limit(db, request, RATE_LIMITS["bootstrap_key_register"])

    sender.bootstrap_key = bootstrap_key_raw
    sender.bootstrap_key_signature = bootstrap_sig_raw
    await db.commit()
    await db.refresh(sender)

    bootstrap_key_id = hashlib.sha256(bootstrap_key_raw).digest()[:16]
    return BootstrapKeyRegisterResponse(
        bootstrap_key_id=base64.b64encode(bootstrap_key_id).decode("ascii"),
    )
