"""Sender registration + V1.5 bootstrap key endpoints.

Minimal V1 surface — sender public-key registration is required before
``POST /v1/messages/send`` works. Full sender lifecycle (pairing, revocation,
sender-signed revoke) is M6.

V1.5 Phase 5a-2 adds `POST /v1/senders/me/bootstrap-key` for the
automated pairing flow (see `docs/crypto-spec.md §9`).
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import time
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

import json

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.models import LiveCard, Message, Pairing, Sender
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
from pydantic import BaseModel, ConfigDict, Field, field_validator, field_serializer
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


# --------------------------------------------------------------------------
# V3 Tier 2B observability endpoints (triad 162).
#
# Three read-only GETs that let a partner answer "did my sends reach the
# server?" without breaking the E2EE contract. All three reuse the same
# Ed25519-signed-header auth scheme.
#
# Auth headers on every request:
#   X-Sender-Id: <sender uuid>
#   X-Sender-Timestamp: <unix seconds>
#   X-Sender-Signature: <base64 ed25519 sig>
#
# Canonical bytes the signature covers (ASCII):
#   syncler-v1-senders-me:<endpoint>:<sender_id>:<query>:<timestamp>
# where <endpoint> ∈ {messages, cards, stats}, <query> is "limit=N" for the
# list endpoints and the empty string for stats.
#
# Timestamp window: ±300s of server time. Replay outside the window is 401.
# Within the window we don't bother with a nonce table — these endpoints
# return no plaintext, no secrets, and the sender already knows their own
# row metadata. The timestamp is mostly defense-in-depth against captured
# header replay weeks later (e.g. log scrape).
#
# Privacy posture (per gemini 162):
#   - Counts only; never plaintext.
#   - No `recipient_count` per message (it's always 1 for 1-to-1, and for
#     multi-tenant senders the sender already knows their own user list).
#   - No device counts.
#   - `encrypted_body_size` is the on-disk length of the opaque envelope
#     pointer — useful for "did my big payload truncate" debugging,
#     reveals nothing about plaintext.
# --------------------------------------------------------------------------

_SENDERS_ME_SIGNATURE_SKEW_SECONDS = 300


class SenderMessagesItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    user_id: UUID
    plugin_id: UUID
    encrypted_body_size: int
    sent_at: datetime
    expires_at: datetime

    @field_serializer("sent_at", "expires_at")
    def _iso(self, value: datetime) -> str:
        return value.isoformat()


class SenderMessagesResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[SenderMessagesItem]


class SenderCardsItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: UUID
    user_id: UUID
    plugin_id: UUID
    card_key: str
    sequence_number: int
    created_at: datetime
    updated_at: datetime
    expires_at: datetime

    @field_serializer("created_at", "updated_at", "expires_at")
    def _iso(self, value: datetime) -> str:
        return value.isoformat()


class SenderCardsResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[SenderCardsItem]


class SenderStatsResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    messages_sent_last_24h: int
    paired_users_active: int
    live_cards_in_flight: int


def _senders_me_canonical(
    endpoint: str, sender_id: UUID, query: str, timestamp: int,
) -> bytes:
    return f"syncler-v1-senders-me:{endpoint}:{sender_id}:{query}:{timestamp}".encode("ascii")


async def _authenticate_senders_me(
    request: Request,
    *,
    endpoint: str,
    query: str,
    db: AsyncSession,
) -> Sender:
    """Header-based Ed25519 verification shared by /v1/senders/me/{messages,cards,stats}.

    Returns the verified Sender row or raises HTTPException with the right
    status code. Mirrors the canonical-string discipline used by every
    other sender-signed surface (POST body signing) but lifts the
    signed material into a header tuple so GET semantics are preserved.
    """
    sender_id_header = request.headers.get("x-sender-id")
    timestamp_header = request.headers.get("x-sender-timestamp")
    signature_header = request.headers.get("x-sender-signature")
    if not sender_id_header or not timestamp_header or not signature_header:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing sender auth headers",
        )

    try:
        sender_uuid = UUID(sender_id_header)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid sender id header",
        ) from exc

    try:
        ts = int(timestamp_header)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid timestamp header",
        ) from exc

    now = int(time.time())
    if abs(now - ts) > _SENDERS_ME_SIGNATURE_SKEW_SECONDS:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="stale or future timestamp",
        )

    try:
        signature = base64.b64decode(signature_header, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="malformed signature header",
        ) from exc
    if len(signature) != 64:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="signature must decode to 64 bytes",
        )

    try:
        sender = await get_active_sender(db, sender_uuid)
    except SenderNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="sender not found",
        ) from exc
    except SenderRevokedError as exc:
        raise HTTPException(
            status_code=status.HTTP_410_GONE, detail="sender revoked",
        ) from exc

    canonical = _senders_me_canonical(endpoint, sender_uuid, query, ts)
    if not verify_message_envelope(sender.public_key, canonical, signature):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid sender signature",
        )

    request.state.sender_id = str(sender.id)
    await check_rate_limit(db, request, RATE_LIMITS["message_send"])
    return sender


@router.get("/me/messages", response_model=SenderMessagesResponse)
async def list_sender_messages(
    request: Request,
    limit: int = Query(default=20, ge=1, le=200),
    db: AsyncSession = Depends(get_db),
) -> SenderMessagesResponse:
    """Last N messages this sender produced. Metadata only — no plaintext."""
    sender = await _authenticate_senders_me(
        request, endpoint="messages", query=f"limit={limit}", db=db,
    )

    rows = (
        await db.execute(
            select(Message)
            .where(Message.sender_id == sender.id)
            .order_by(Message.sent_at.desc())
            .limit(limit)
        )
    ).scalars().all()

    items = [
        SenderMessagesItem(
            id=m.id,
            user_id=m.user_id,
            plugin_id=m.plugin_id,
            encrypted_body_size=len(m.encrypted_body_pointer or ""),
            sent_at=m.sent_at,
            expires_at=m.expires_at,
        )
        for m in rows
    ]
    return SenderMessagesResponse(items=items)


@router.get("/me/cards", response_model=SenderCardsResponse)
async def list_sender_cards(
    request: Request,
    limit: int = Query(default=20, ge=1, le=200),
    db: AsyncSession = Depends(get_db),
) -> SenderCardsResponse:
    """Last N live cards this sender produced. Metadata only — no plaintext."""
    sender = await _authenticate_senders_me(
        request, endpoint="cards", query=f"limit={limit}", db=db,
    )

    rows = (
        await db.execute(
            select(LiveCard)
            .where(LiveCard.sender_id == sender.id)
            .order_by(LiveCard.updated_at.desc())
            .limit(limit)
        )
    ).scalars().all()

    items = [
        SenderCardsItem(
            id=c.id,
            user_id=c.user_id,
            plugin_id=c.plugin_id,
            card_key=c.card_key,
            sequence_number=c.sequence_number,
            created_at=c.created_at,
            updated_at=c.updated_at,
            expires_at=c.expires_at,
        )
        for c in rows
    ]
    return SenderCardsResponse(items=items)


@router.get("/me/stats", response_model=SenderStatsResponse)
async def get_sender_stats(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> SenderStatsResponse:
    """Counts only. Sized for partner debugging, not internal observability."""
    sender = await _authenticate_senders_me(
        request, endpoint="stats", query="", db=db,
    )

    now = datetime.now(timezone.utc)
    window = now - timedelta(hours=24)

    messages_24h = (
        await db.execute(
            select(func.count(Message.id)).where(
                Message.sender_id == sender.id,
                Message.sent_at >= window,
            )
        )
    ).scalar_one()

    paired_users = (
        await db.execute(
            select(func.count(func.distinct(Pairing.user_id))).where(
                Pairing.sender_id == sender.id,
                Pairing.revoked_at.is_(None),
            )
        )
    ).scalar_one()

    live_in_flight = (
        await db.execute(
            select(func.count(LiveCard.id)).where(
                LiveCard.sender_id == sender.id,
                LiveCard.expires_at > now,
            )
        )
    ).scalar_one()

    return SenderStatsResponse(
        messages_sent_last_24h=int(messages_24h),
        paired_users_active=int(paired_users),
        live_cards_in_flight=int(live_in_flight),
    )
