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

import asyncio
import json
import logging
import secrets
import time
import uuid
from dataclasses import dataclass
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect, status
from pydantic import BaseModel, ConfigDict
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, current_auth_context
from app.db import get_db
from app.live.hub import (
    EphemeralSubscription,
    get_hub,
    pairing_revocation_topic,
    plugin_topic,
)
from app.models import Pairing, Plugin

logger = logging.getLogger(__name__)
router = APIRouter(tags=["live"])

# --- Frame + rate-limit constants (spec docs/live-channel.md) ---

MAX_FRAME_BYTES = 64 * 1024
MAX_CHANNELS_PER_SOCKET = 10
MAX_CHANNEL_NAME_LEN = 64
HEARTBEAT_PING_INTERVAL_S = 30.0
HEARTBEAT_PONG_DEADLINE_S = 60.0
OUTBOUND_RATE_LIMIT_BYTES_PER_S = 16 * 1024
OUTBOUND_BURST_BYTES = 64 * 1024
SUSTAINED_VIOLATION_S = 10.0

# WS close codes (4xxx is the "library-defined" range).
CLOSE_POLICY_VIOLATION = 1008
CLOSE_PAIRING_REVOKED = 4401
CLOSE_HEARTBEAT_TIMEOUT = 4408
CLOSE_RATE_LIMIT_EXCEEDED = 4429
CLOSE_BAD_AUTH = 4400


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


# --- WS multiplex endpoint ---


def _extract_bearer_from_subprotocol(websocket: WebSocket) -> str | None:
    """Parse `Sec-WebSocket-Protocol: syncler.v1, bearer.<token>`
    from the WS request and return the token bytes. Returns
    None if missing/malformed."""
    raw = websocket.headers.get("sec-websocket-protocol")
    if raw is None:
        return None
    # Header is comma-separated subprotocols. We expect at least
    # one starting with `bearer.`.
    for part in raw.split(","):
        token = part.strip()
        if token.startswith("bearer."):
            return token[len("bearer."):]
    return None


async def _verify_plugin_paired_to_user(
    db: AsyncSession, plugin_row_id: uuid.UUID, user_id: uuid.UUID
) -> bool:
    """Confirm the plugin row is active AND its sender is
    currently paired with [user_id]. The pairing join is the
    same shape the inbox routes use; revocation flips
    pairings.revoked_at and the join falls through."""
    plugin_result = await db.execute(
        select(Plugin).where(Plugin.id == plugin_row_id)
    )
    plugin = plugin_result.scalar_one_or_none()
    if plugin is None or plugin.revoked_at is not None:
        return False
    pairing_result = await db.execute(
        select(Pairing).where(
            Pairing.user_id == user_id,
            Pairing.sender_id == plugin.sender_id,
            Pairing.revoked_at.is_(None),
        )
    )
    return pairing_result.scalar_one_or_none() is not None


class _SocketState:
    """Per-socket bookkeeping: open channel set, token-bucket
    rate-limit counters, pong deadline.
    """

    def __init__(self) -> None:
        self.channels: set[str] = set()
        # Token-bucket: starts full (allows burst), refills at
        # OUTBOUND_RATE_LIMIT_BYTES_PER_S.
        self.bucket_bytes: float = float(OUTBOUND_BURST_BYTES)
        self.bucket_last_refill_s: float = time.monotonic()
        # When the bucket goes negative we start a sustained
        # violation window; if it stays below zero for
        # SUSTAINED_VIOLATION_S we close.
        self.violation_started_s: float | None = None
        # Heartbeat tracking. The pong deadline rolls forward
        # each time the client responds.
        self.last_pong_s: float = time.monotonic()

    def refill(self, now_s: float) -> None:
        delta = now_s - self.bucket_last_refill_s
        if delta <= 0:
            return
        self.bucket_last_refill_s = now_s
        self.bucket_bytes = min(
            float(OUTBOUND_BURST_BYTES),
            self.bucket_bytes + delta * OUTBOUND_RATE_LIMIT_BYTES_PER_S,
        )

    def consume(self, byte_count: int, now_s: float) -> bool:
        """Charge [byte_count] against the bucket. Returns
        True if the call should close the socket due to
        sustained violation. False otherwise."""
        self.refill(now_s)
        self.bucket_bytes -= byte_count
        if self.bucket_bytes < 0:
            if self.violation_started_s is None:
                self.violation_started_s = now_s
            elif now_s - self.violation_started_s >= SUSTAINED_VIOLATION_S:
                return True
        else:
            self.violation_started_s = None
        return False


def _valid_channel_name(name: str) -> bool:
    """Per spec: `^[a-zA-Z0-9._-]+$`, max 64 chars."""
    if not name or len(name) > MAX_CHANNEL_NAME_LEN:
        return False
    return all(
        c.isalnum() or c in "._-" for c in name
    )


def _build_error_frame(channel: str, frame_id: str | None, code: str) -> str:
    payload: dict[str, Any] = {"channel": channel, "type": "error", "payload": code}
    if frame_id is not None:
        payload["id"] = frame_id
    return json.dumps(payload, separators=(",", ":"))


def _build_ack_frame(channel: str, frame_id: str) -> str:
    return json.dumps(
        {"channel": channel, "type": "ack", "id": frame_id},
        separators=(",", ":"),
    )


@router.websocket("/plugin/{plugin_row_id}")
async def plugin_socket(
    websocket: WebSocket,
    plugin_row_id: str,
    db: AsyncSession = Depends(get_db),
) -> None:
    """V3 #14 step 3: device-side WS endpoint.

    Multiplexes channels over a single socket per
    (device, plugin_row). Sender-pushed frames arrive via the
    BroadcastHub's ephemeral lane; device-originated frames
    are validated, rate-limited, and (step 5) forwarded to the
    sender's webhook.

    Auth: `Sec-WebSocket-Protocol: syncler.v1, bearer.<token>`
    where token is a short-lived connect token minted by
    `/v1/live/connect-token` and bound to (user_id, device_id).
    """
    bearer = _extract_bearer_from_subprotocol(websocket)
    if bearer is None:
        await websocket.close(code=CLOSE_BAD_AUTH)
        return
    binding = redeem_connect_token(bearer)
    if binding is None:
        await websocket.close(code=CLOSE_BAD_AUTH)
        return

    # Parse + validate plugin_row_id.
    try:
        plugin_row_uuid = uuid.UUID(plugin_row_id)
    except ValueError:
        await websocket.close(code=CLOSE_BAD_AUTH)
        return

    # Pairing + active-plugin check.
    if not await _verify_plugin_paired_to_user(
        db, plugin_row_uuid, binding.user_id
    ):
        await websocket.close(code=CLOSE_BAD_AUTH)
        return

    # Accept the WS with the negotiated subprotocol echoed back.
    await websocket.accept(subprotocol="syncler.v1")

    state = _SocketState()
    hub = get_hub()
    push_sub = await hub.subscribe_ephemeral(plugin_topic(plugin_row_id))
    revoke_sub = await hub.subscribe_control(pairing_revocation_topic())

    inbound_task: asyncio.Task[Any] | None = None
    push_task: asyncio.Task[Any] | None = None
    revoke_task: asyncio.Task[Any] | None = None
    heartbeat_task: asyncio.Task[Any] | None = None
    closing = asyncio.Event()
    close_code = {"value": 1000}

    async def pump_inbound() -> None:
        """Read frames from the device; validate + audit-log."""
        while not closing.is_set():
            try:
                msg = await websocket.receive_text()
            except WebSocketDisconnect:
                closing.set()
                return
            if len(msg) > MAX_FRAME_BYTES:
                await websocket.send_text(
                    _build_error_frame("", None, "payload_too_large")
                )
                continue
            now = time.monotonic()
            if state.consume(len(msg), now):
                close_code["value"] = CLOSE_RATE_LIMIT_EXCEEDED
                closing.set()
                return
            await _handle_inbound_frame(websocket, state, msg)
            state.last_pong_s = time.monotonic()  # ANY frame counts

    async def pump_pushes() -> None:
        """Forward server→device fan-outs from the hub."""
        async for payload in push_sub.messages():
            if closing.is_set():
                break
            await websocket.send_text(payload)

    async def watch_revocation() -> None:
        """Listen on the pairing-revocation control topic.
        Each event is JSON ``{"user_id":..., "device_id":...}``;
        close if it matches THIS socket's binding."""
        async for raw in revoke_sub.messages():
            if closing.is_set():
                break
            try:
                event = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if event.get("user_id") == str(binding.user_id) and (
                event.get("device_id") in (None, str(binding.device_id))
            ):
                close_code["value"] = CLOSE_PAIRING_REVOKED
                closing.set()
                return

    async def heartbeat() -> None:
        """Ping the client every HEARTBEAT_PING_INTERVAL_S; if
        no inbound frame arrives within HEARTBEAT_PONG_DEADLINE_S,
        close."""
        while not closing.is_set():
            await asyncio.sleep(HEARTBEAT_PING_INTERVAL_S)
            if closing.is_set():
                return
            since = time.monotonic() - state.last_pong_s
            if since > HEARTBEAT_PONG_DEADLINE_S:
                close_code["value"] = CLOSE_HEARTBEAT_TIMEOUT
                closing.set()
                return
            try:
                await websocket.send_text(
                    json.dumps({"channel": "", "type": "ping"}, separators=(",", ":"))
                )
            except Exception:
                closing.set()
                return

    try:
        inbound_task = asyncio.create_task(pump_inbound())
        push_task = asyncio.create_task(pump_pushes())
        revoke_task = asyncio.create_task(watch_revocation())
        heartbeat_task = asyncio.create_task(heartbeat())
        # Wait until any of the lifecycle paths flips `closing`.
        await closing.wait()
    finally:
        closing.set()
        for task in (inbound_task, push_task, revoke_task, heartbeat_task):
            if task is not None:
                task.cancel()
        for task in (inbound_task, push_task, revoke_task, heartbeat_task):
            if task is not None:
                try:
                    await task
                except (asyncio.CancelledError, WebSocketDisconnect):
                    pass
                except Exception:
                    logger.exception("live ws task errored")
        await push_sub.unsubscribe()
        await revoke_sub.unsubscribe()
        try:
            await websocket.close(code=close_code["value"])
        except Exception:
            pass


async def _handle_inbound_frame(
    websocket: WebSocket, state: _SocketState, raw: str
) -> None:
    """Parse a device→server frame and act on its type:

    - `open` — register a new channel (cap-checked).
    - `close` — release a channel.
    - `message` — payload forwarded to sender (step 5 — V0.1
      ack-only, no webhook yet).
    - `pong` — just rolls the heartbeat deadline.

    Any unrecognized type or malformed shape sends an `error`
    frame back."""
    try:
        frame = json.loads(raw)
    except json.JSONDecodeError:
        await websocket.send_text(_build_error_frame("", None, "invalid_frame"))
        return
    if not isinstance(frame, dict):
        await websocket.send_text(_build_error_frame("", None, "invalid_frame"))
        return
    channel = frame.get("channel")
    ftype = frame.get("type")
    frame_id = frame.get("id")
    if not isinstance(channel, str) or not isinstance(ftype, str):
        await websocket.send_text(_build_error_frame("", None, "invalid_frame"))
        return

    if ftype == "open":
        if not _valid_channel_name(channel):
            await websocket.send_text(
                _build_error_frame(channel, frame_id, "channel_name_invalid")
            )
            return
        if channel in state.channels:
            # Idempotent re-open: just ack.
            await websocket.send_text(
                json.dumps(
                    {"channel": channel, "type": "open"},
                    separators=(",", ":"),
                )
            )
            return
        if len(state.channels) >= MAX_CHANNELS_PER_SOCKET:
            await websocket.send_text(
                _build_error_frame(channel, frame_id, "channel_limit_exceeded")
            )
            return
        state.channels.add(channel)
        await websocket.send_text(
            json.dumps(
                {"channel": channel, "type": "open"},
                separators=(",", ":"),
            )
        )
        return

    if ftype == "close":
        state.channels.discard(channel)
        await websocket.send_text(
            json.dumps(
                {"channel": channel, "type": "close"},
                separators=(",", ":"),
            )
        )
        return

    if ftype == "message":
        if channel not in state.channels:
            await websocket.send_text(
                _build_error_frame(channel, frame_id, "channel_not_open")
            )
            return
        if not isinstance(frame_id, str) or len(frame_id) > 32:
            await websocket.send_text(
                _build_error_frame(channel, None, "invalid_frame")
            )
            return
        # V0.1: ack now; webhook forwarding is step 5.
        # The frame's `payload` is treated as opaque bytes (an
        # already-sealed V2-style envelope per the spec).
        await websocket.send_text(_build_ack_frame(channel, frame_id))
        return

    if ftype == "pong":
        # Heartbeat: pong updates last_pong_s (already done by
        # the inbound loop on ANY frame). No ack needed.
        return

    if ftype == "ping":
        # Client-initiated ping (rare); respond with pong.
        await websocket.send_text(
            json.dumps({"channel": "", "type": "pong"}, separators=(",", ":"))
        )
        return

    await websocket.send_text(
        _build_error_frame(channel, frame_id, "invalid_frame")
    )
