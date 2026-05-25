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

import json

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.schemas import (
    BootstrapKeyRegisterRequest,
    BootstrapKeyRegisterResponse,
    DeviceDirectoryItem,
    DeviceDirectoryResponse,
    SenderRegisterRequest,
    SenderRegisterResponse,
    decode_base64,
)
from app.services.devices import get_device_directory
from app.services.messages import _pairing
from app.services.senders import (
    SenderAlreadyExistsError,
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
    register_sender,
)
from pydantic import BaseModel, field_validator
from uuid import UUID

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


# --------------------------------------------------------------------------
# Phase 9b §11.9 — sender device directory
# --------------------------------------------------------------------------


class DirectoryFetchRequest(BaseModel):
    """Signed POST body for sender-side directory fetch. Spec §11.9.

    Sender signs canonical JSON of `{endpoint_kind, sender_id, user_id}`
    with their Ed25519 sender-registration key. The same key the server
    uses to verify `POST /v1/messages/send` envelopes.
    """

    sender_id: UUID
    user_id: UUID
    request_signature: str

    @field_validator("request_signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="request_signature", exact=64)
        return value


def _directory_fetch_signed_bytes(payload: DirectoryFetchRequest) -> bytes:
    """Canonical JSON the sender must sign (spec §11.9)."""
    return json.dumps(
        {
            "endpoint_kind": "directory_fetch",
            "sender_id": str(payload.sender_id),
            "user_id": str(payload.user_id),
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


@router.post("/me/devices", response_model=DeviceDirectoryResponse)
async def fetch_device_directory(
    payload: DirectoryFetchRequest,
    request: Request,
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> DeviceDirectoryResponse:
    """Phase 9b sender directory fetch (spec §11.9).

    Returns the active devices' X25519 public keys + the current
    `device_directory_version` for the named user, so the sender can
    seal a per-device HPKE envelope for each on the next publish.

    Auth: signed POST body (Ed25519 over canonical `endpoint_kind /
    sender_id / user_id`). Gated by an active `Pairing(sender_id,
    user_id)` — an un-paired sender gets 403 even with a valid
    signature.
    """
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="sender not found"
        ) from exc
    except SenderRevokedError as exc:
        raise HTTPException(
            status_code=status.HTTP_410_GONE, detail="sender revoked"
        ) from exc

    signature_bytes = decode_base64(
        payload.request_signature, field_name="request_signature", exact=64
    )
    if not verify_message_envelope(
        sender.public_key, _directory_fetch_signed_bytes(payload), signature_bytes
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid sender signature",
        )

    request.state.sender_id = str(sender.id)
    await check_rate_limit(db, request, RATE_LIMITS["message_send"])

    pairing = await _pairing(db, sender_id=sender.id, user_id=payload.user_id)
    if pairing is None:
        # No active pairing — sender cannot see this user's devices.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="not paired with user",
        )

    directory_version, devices = await get_device_directory(db, user_id=payload.user_id)
    return DeviceDirectoryResponse(
        directory_version=directory_version,
        user_id=payload.user_id,
        devices=[
            DeviceDirectoryItem(
                device_id=device.id,
                encryption_public_key=base64.b64encode(device.encryption_public_key).decode(
                    "ascii"
                ),
                updated_at=device.updated_at,
            )
            for device in devices
        ],
    )
