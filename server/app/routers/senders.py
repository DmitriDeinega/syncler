"""Sender registration endpoints.

Minimal V1 surface — sender public-key registration is required before
``POST /v1/messages/send`` works. Full sender lifecycle (pairing, revocation,
sender-signed revoke) is M6.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.schemas import SenderRegisterRequest, SenderRegisterResponse, decode_base64
from app.services.senders import SenderAlreadyExistsError, register_sender

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
