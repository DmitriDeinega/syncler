"""V3 #14 — live channel HTTP + WebSocket endpoints.

Step 2 of the live-channel implementation order
(`docs/live-channel.md`): the `/v1/live/connect-token`
endpoint.

The device's long-lived JWT must NOT ride in the WS upgrade
subprotocol header (codex 141 #1 — JWTs can contain
characters that break subprotocol parsing, and the header
gets logged by various intermediaries). Instead:

1. Device POSTs `/v1/live/connect-token` with its device JWT
   in the standard ``Authorization: Bearer`` header.
2. Server mints a short-lived (60s) opaque connect token
   bound to ``(user_id, device_id)``.
3. Device opens the WS with that token in the subprotocol
   slot — short, opaque, harmless to log.

Tokens are kept in-process for V0.1 (a small TTL dict). V3
#17's Redis swap will replace this with Redis ``SETEX`` so
the token is reachable from any WS worker. For now,
single-worker dev is fine.
"""

from __future__ import annotations

import secrets
import time
import uuid
from dataclasses import dataclass

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict

from app.auth import AuthContext, current_auth_context

router = APIRouter(tags=["live"])


# --- In-process connect-token store ---


@dataclass(frozen=True)
class ConnectToken:
    user_id: uuid.UUID
    device_id: uuid.UUID
    expires_at_epoch_s: float


# token-string → ConnectToken. Process-local; V3 #17 swaps for
# Redis SETEX. tokens are 256-bit URL-safe random strings.
_TOKEN_STORE: dict[str, ConnectToken] = {}

CONNECT_TOKEN_TTL_SECONDS = 60.0


def _purge_expired_locked(now_epoch_s: float) -> None:
    """Remove tokens past their TTL. Called on every mint /
    redeem so the in-process map doesn't grow without bound
    under churn."""
    stale = [
        tok for tok, entry in _TOKEN_STORE.items()
        if entry.expires_at_epoch_s <= now_epoch_s
    ]
    for tok in stale:
        _TOKEN_STORE.pop(tok, None)


def mint_connect_token(user_id: uuid.UUID, device_id: uuid.UUID) -> ConnectToken:
    """Generate and store a fresh connect token. Returns the
    [ConnectToken] dataclass; the caller serializes the
    `.token` string (returned separately by the endpoint)."""
    now = time.time()
    _purge_expired_locked(now)
    token_str = secrets.token_urlsafe(32)  # 256 bits
    entry = ConnectToken(
        user_id=user_id,
        device_id=device_id,
        expires_at_epoch_s=now + CONNECT_TOKEN_TTL_SECONDS,
    )
    _TOKEN_STORE[token_str] = entry
    # We return both the token string and the entry by stashing
    # the token string back in a wrapper for the response. See
    # the endpoint below.
    return entry


def redeem_connect_token(token: str) -> ConnectToken | None:
    """Look up + atomically consume a connect token. Returns
    the bound (user_id, device_id) on success or None if the
    token is unknown or expired.

    Single-use: tokens are popped on redeem so a stolen token
    can't replay the WS open. The WS handshake is the only
    redeem path."""
    now = time.time()
    _purge_expired_locked(now)
    entry = _TOKEN_STORE.pop(token, None)
    if entry is None:
        return None
    if entry.expires_at_epoch_s <= now:
        return None
    return entry


def _reset_store_for_test() -> None:
    """pytest hook — drains the in-process token map between
    tests so token leakage doesn't cross test boundaries."""
    _TOKEN_STORE.clear()


# --- Endpoint ---


class ConnectTokenResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token: str
    expires_at_epoch_ms: int
    ttl_seconds: int


@router.post("/connect-token", response_model=ConnectTokenResponse)
async def issue_connect_token(
    auth: AuthContext = Depends(current_auth_context),
) -> ConnectTokenResponse:
    """V3 #14 step 2: mint a short-lived opaque connect token.

    The device JWT is verified via the standard auth dependency
    (which also checks device-revocation and user existence —
    a revoked device's JWT can't mint a connect token even if
    the JWT hasn't expired yet).

    The opaque token is 256 bits, URL-safe-base64-encoded,
    valid for 60 seconds, and single-use. The device passes it
    to the WS upgrade as `Sec-WebSocket-Protocol: syncler.v1,
    bearer.<token>`.
    """
    # `current_auth_context` already rejects bootstrap tokens
    # (no device_id) and revoked devices. Defensive double-check
    # against bootstrap tokens since the dataclass guarantees
    # `device` is non-None:
    if auth.device is None:  # type: ignore[unreachable]
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="device JWT required",
        )

    now = time.time()
    _purge_expired_locked(now)
    token_str = secrets.token_urlsafe(32)
    entry = ConnectToken(
        user_id=auth.user.id,
        device_id=auth.device.id,
        expires_at_epoch_s=now + CONNECT_TOKEN_TTL_SECONDS,
    )
    _TOKEN_STORE[token_str] = entry

    return ConnectTokenResponse(
        token=token_str,
        expires_at_epoch_ms=int(entry.expires_at_epoch_s * 1000),
        ttl_seconds=int(CONNECT_TOKEN_TTL_SECONDS),
    )
