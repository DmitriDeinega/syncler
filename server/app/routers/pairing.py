"""Pairing routes — sender initiates, user completes, either revokes."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, current_auth_context
from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.models import Pairing
from app.schemas import (
    PairingCompleteRequest,
    PairingCompleteResponse,
    PairingInitiateRequest,
    PairingInitiateResponse,
    PairingItem,
    PairingPreviewResponse,
    PairingStateResponse,
    decode_base64,
)
from app.services.key_generation import lock_user_and_gate
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
    """Canonical bytes the sender signs to authenticate the initiate call.

    `sender_broker_url` is conditionally included: when None (V1 manual
    flow), the envelope keeps the V1 3-field shape so existing senders
    that don't supply a broker keep verifying. When non-None (V1.5
    automated flow), it's bound into the signature so the syncler
    server can't silently substitute its own URL.
    """
    envelope: dict = {
        "sender_id": str(payload.sender_id),
        "ttl_seconds": int(payload.ttl_seconds),
        "metadata": payload.metadata or {},
    }
    if payload.sender_broker_url is not None:
        envelope["sender_broker_url"] = payload.sender_broker_url
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


# V1.5 automated pairing: validation for the sender-supplied broker URL.
# Spec: docs/crypto-spec.md §9. Plan: .triad/70-phase5-agreement.md.


def _is_private_lan_host(host: str) -> bool:
    """True if `host` is a private-range IP literal or the exact literal
    `localhost`. Codex consultation 75 RED #1: do NOT prefix-match DNS
    names — `127.evil.test` and `localhost.evil.test` would pass a
    naive `startswith` check. Use `ipaddress.ip_address(...)` for IPs
    and exact-string match for `localhost`.
    """
    import ipaddress
    if host == "localhost":
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return ip.is_private or ip.is_loopback


def _validate_sender_broker_url(url: str, *, debug_allow_http: bool) -> None:
    """Raises HTTPException(400) if the URL fails the V1.5 shape check.
    Release: HTTPS only. Debug: http allowed for private LAN IP literals
    or exact `localhost` only (DNS names like `127.evil.test` are
    rejected). No credentials in URL, no fragment, length ≤ 2048 chars,
    parsable.
    """
    from urllib.parse import urlparse

    if len(url) > 2048:
        raise HTTPException(status_code=400, detail="sender_broker_url exceeds 2048 chars")
    try:
        parsed = urlparse(url)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="sender_broker_url is not a valid URL") from exc
    if parsed.scheme not in ("http", "https"):
        raise HTTPException(status_code=400, detail="sender_broker_url scheme must be http(s)")
    if parsed.username or parsed.password:
        raise HTTPException(status_code=400, detail="sender_broker_url must not contain credentials")
    if parsed.fragment:
        raise HTTPException(status_code=400, detail="sender_broker_url must not contain a fragment")
    if not parsed.hostname:
        raise HTTPException(status_code=400, detail="sender_broker_url is missing a host")
    if parsed.scheme == "http":
        if not debug_allow_http:
            raise HTTPException(status_code=400, detail="sender_broker_url must use HTTPS in release")
        if not _is_private_lan_host(parsed.hostname):
            raise HTTPException(
                status_code=400,
                detail=(
                    "sender_broker_url http only allowed for private-LAN IP "
                    "literals (10/8, 172.16/12, 192.168/16, 127/8) or exact 'localhost' in debug"
                ),
            )


def _bootstrap_register_envelope_bytes(payload) -> bytes:
    """Canonical signing input for the bootstrap-key registration endpoint.

    Signs over the literal ASCII bytes `"syncler-v1-bootstrap-key:"` (24
    bytes) followed by the raw 32-byte X25519 public key, per
    `docs/crypto-spec.md §9.1`. NOT the base64 representation.
    """
    return b"syncler-v1-bootstrap-key:" + decode_base64(
        payload.bootstrap_key, field_name="bootstrap_key", exact=32,
    )


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

    # V1.5 automated pairing (Phase 5a-2): if the sender supplied a
    # broker URL, validate shape + require the sender to have already
    # registered a bootstrap key (otherwise preview can't surface the
    # crypto material the device needs).
    if payload.sender_broker_url is not None:
        from app.config import get_settings  # local import keeps module import cheap
        _validate_sender_broker_url(
            payload.sender_broker_url,
            debug_allow_http=(get_settings().environment == "development"),
        )
        if sender.bootstrap_key is None or sender.bootstrap_key_signature is None:
            raise HTTPException(
                status_code=400,
                detail=(
                    "sender_broker_url requires a registered bootstrap key; "
                    "call POST /v1/senders/me/bootstrap-key first"
                ),
            )

    pending = await initiate_pairing(
        db,
        sender_id=sender.id,
        ttl_seconds=payload.ttl_seconds,
        metadata=payload.metadata,
        sender_broker_url=payload.sender_broker_url,
    )

    base_url = str(request.base_url).rstrip("/")
    broker_url = f"{base_url}/v1/pairing/complete?token={_b64url(pending.pairing_token)}"

    return PairingInitiateResponse(
        pairing_id=pending.id,
        pairing_token=_b64url(pending.pairing_token),
        broker_url=broker_url,
        expires_at=pending.expires_at,
        sender_broker_url=payload.sender_broker_url,
        bootstrap_protocol_version=(1 if payload.sender_broker_url is not None else None),
    )


@router.get("/preview", response_model=PairingPreviewResponse)
async def preview(
    token: str,
    _: None = Depends(rate_limit("message_send_ip")),  # IP-based DoS hygiene
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
    # V1.5: surface the bootstrap crypto material to the device when the
    # sender opted into automated pairing. The device verifies
    # `bootstrap_key_signature` against `sender_public_key` before
    # encrypting anything (defense against syncler-side substitution).
    bootstrap_protocol_version: int | None = None
    bootstrap_key_b64: str | None = None
    bootstrap_sig_b64: str | None = None
    if (
        pending.sender_broker_url is not None
        and sender.bootstrap_key is not None
        and sender.bootstrap_key_signature is not None
    ):
        bootstrap_protocol_version = 1
        bootstrap_key_b64 = _b64(sender.bootstrap_key)
        bootstrap_sig_b64 = _b64(sender.bootstrap_key_signature)
    return PairingPreviewResponse(
        sender_id=sender.id,
        sender_name=sender.name,
        sender_public_key=_b64(sender.public_key),
        sender_public_key_fingerprint=meta["fingerprint"] or "",
        sender_name_hash=meta["name_hash"] or "",
        expires_at=pending.expires_at,
        sender_broker_url=pending.sender_broker_url,
        bootstrap_key=bootstrap_key_b64,
        bootstrap_key_signature=bootstrap_sig_b64,
        bootstrap_protocol_version=bootstrap_protocol_version,
    )


@router.post("/complete", response_model=PairingCompleteResponse, status_code=status.HTTP_201_CREATED)
async def complete(
    payload: PairingCompleteRequest,
    request: Request,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> PairingCompleteResponse:
    # Phase 8 §10.5 — lock user row + 426/409 gates BEFORE writing the new
    # pairing row, otherwise a rotation racing with /complete could leave
    # a pairing with stale key_generation.
    locked_user = await lock_user_and_gate(
        db,
        request=request,
        user_id=ctx.user.id,
        key_generation_observed=payload.key_generation_observed,
    )

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
            user=ctx.user,
            pairing_token=token,
            encrypted_initial_state=encrypted_initial_state,
            # Phase 8 §10.4 — stamp the new row with the generation just
            # locked. Re-reading inside the user-row lock is race-free.
            key_generation=locked_user.key_generation,
            # Phase 8d §10.9 — respect client-generated pairing_id when present.
            pairing_id=payload.pairing_id,
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
    request: Request,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> Response:
    # Phase 8 §10.5 — revoke removes a row from the active-pairing set
    # that rotation enumerates; same lock-first contract. revoke does
    # NOT require key_generation_observed (no new blob is written), so
    # ``require_observed=False`` per Gemini 104 YELLOW.
    await lock_user_and_gate(
        db,
        request=request,
        user_id=ctx.user.id,
        key_generation_observed=None,
        require_observed=False,
    )
    try:
        await revoke_pairing(db, user=ctx.user, pairing_id=pairing_id)
    except PairingTokenNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="pairing not found") from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("", response_model=list[PairingItem])
async def list_pairings(
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> list[PairingItem]:
    result = await db.execute(
        select(Pairing).where(Pairing.user_id == ctx.user.id).order_by(Pairing.created_at.desc()),
    )
    return [PairingItem.model_validate(p) for p in result.scalars().all()]


@router.get(
    "/{pairing_id}/state",
    response_model=PairingStateResponse,
)
async def get_pairing_state(
    pairing_id: uuid.UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> PairingStateResponse:
    """Phase 8e — return the encrypted_state for a pairing the user
    owns. Used by the client to fetch+decrypt every blob during a
    ``root_*`` rotation so it can re-encrypt under the new master key.

    Scoping is `(pairing_id, user_id, revoked_at IS NULL)` — a user
    cannot read another user's pairing state, and revoked pairings
    are 404'd (you can't rotate a key for state you can no longer
    decrypt anyway).
    """
    result = await db.execute(
        select(Pairing).where(
            and_(
                Pairing.id == pairing_id,
                Pairing.user_id == ctx.user.id,
                Pairing.revoked_at.is_(None),
            ),
        ),
    )
    pairing = result.scalar_one_or_none()
    if pairing is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="pairing not found or revoked",
        )
    return PairingStateResponse(
        pairing_id=pairing.id,
        encrypted_state=_b64(pairing.encrypted_state),
        state_version=pairing.state_version,
        key_generation=pairing.key_generation,
    )
