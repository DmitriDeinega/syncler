"""V3 #17 — connect-token store with pluggable backend.

The V3 #14 in-process dict moved here behind a small
``TokenStore`` interface so the production deploy can swap
in the Redis-backed impl without the route handler caring.

Spec: docs/live-backplane.md "Connect-token store".

Two implementations:
- ``InProcessTokenStore`` — V0.1 default. dict + purge-on-
  access. Tests + single-worker dev.
- ``RedisTokenStore`` — Multi-worker. ``SET key value EX 60
  NX`` mint + ``GETDEL`` atomic single-use redeem.
"""

from __future__ import annotations

import json
import logging
import secrets
import time
import uuid
from dataclasses import dataclass
from typing import Protocol

from app.config import get_settings
from app.redis_client import get_redis, prefixed

logger = logging.getLogger(__name__)

CONNECT_TOKEN_TTL_SECONDS = 60.0


@dataclass(frozen=True)
class ConnectToken:
    """Bound device identity behind a connect-token string.

    The token string itself is NEVER stored on this dataclass
    — the route handler returns it once at mint time and the
    Redis/in-process backend keys the storage by it.
    """

    user_id: uuid.UUID
    device_id: uuid.UUID
    expires_at_epoch_s: float


class TokenStore(Protocol):
    """V3 #17 connect-token store. Two ops only: mint a
    fresh token string and atomically redeem one. Both
    backends are async-safe; the in-process one is also
    sync-safe since it only touches dict ops."""

    async def mint(
        self, user_id: uuid.UUID, device_id: uuid.UUID
    ) -> tuple[str, ConnectToken]:
        """Generate a 256-bit URL-safe random token, persist
        it with [CONNECT_TOKEN_TTL_SECONDS] TTL, and return
        ``(token_string, ConnectToken)``."""
        ...

    async def redeem(self, token: str) -> ConnectToken | None:
        """Atomically consume the token. Returns the bound
        identity on success or ``None`` on unknown / expired.
        Single-use semantics: a second redeem of the same
        token MUST return ``None``."""
        ...

    async def reset_for_test(self) -> None:
        """Drain the store between tests."""
        ...


# --- In-process impl (V0.1 default) ---


class InProcessTokenStore:
    """Dict-backed store. Process-local; multiple workers
    won't see each other's mints — that's the v3 #17 swap."""

    def __init__(self) -> None:
        self._tokens: dict[str, ConnectToken] = {}

    def _purge_expired_locked(self, now_epoch_s: float) -> None:
        stale = [
            tok for tok, entry in self._tokens.items()
            if entry.expires_at_epoch_s <= now_epoch_s
        ]
        for tok in stale:
            self._tokens.pop(tok, None)

    async def mint(
        self, user_id: uuid.UUID, device_id: uuid.UUID
    ) -> tuple[str, ConnectToken]:
        now = time.time()
        self._purge_expired_locked(now)
        token_str = secrets.token_urlsafe(32)
        entry = ConnectToken(
            user_id=user_id,
            device_id=device_id,
            expires_at_epoch_s=now + CONNECT_TOKEN_TTL_SECONDS,
        )
        self._tokens[token_str] = entry
        return token_str, entry

    async def redeem(self, token: str) -> ConnectToken | None:
        now = time.time()
        self._purge_expired_locked(now)
        entry = self._tokens.pop(token, None)
        if entry is None:
            return None
        if entry.expires_at_epoch_s <= now:
            return None
        return entry

    async def reset_for_test(self) -> None:
        self._tokens.clear()


# --- Redis impl (V3 #17) ---


def _token_key(token: str) -> str:
    return prefixed(f"live:token:{token}")


# GETDEL appeared in Redis 6.2. For older Redis, swap to the
# Lua script below. We default to GETDEL — the docker-compose
# pin is 7-alpine so 6.2+ is always available in dev.
_GETDEL_LUA = """
local v = redis.call('GET', KEYS[1])
if v then
    redis.call('DEL', KEYS[1])
end
return v
"""


class RedisTokenStore:
    """SETEX-backed store. Multi-worker safe.

    Spec: docs/live-backplane.md "Connect-token store".
    """

    async def mint(
        self, user_id: uuid.UUID, device_id: uuid.UUID
    ) -> tuple[str, ConnectToken]:
        token_str = secrets.token_urlsafe(32)
        now = time.time()
        entry = ConnectToken(
            user_id=user_id,
            device_id=device_id,
            expires_at_epoch_s=now + CONNECT_TOKEN_TTL_SECONDS,
        )
        payload = json.dumps(
            {
                "user_id": str(entry.user_id),
                "device_id": str(entry.device_id),
                "expires_at_epoch_s": entry.expires_at_epoch_s,
            },
            separators=(",", ":"),
        ).encode("utf-8")
        client = get_redis()
        # NX so we never silently overwrite an existing
        # token entry — 256-bit random collisions are
        # astronomically unlikely, the NX is a belt-and-
        # suspenders guard against bugs.
        ok = await client.set(
            _token_key(token_str),
            payload,
            ex=int(CONNECT_TOKEN_TTL_SECONDS),
            nx=True,
        )
        if not ok:
            # Vanishingly unlikely (random collision) but
            # if it happens we have to surface the failure
            # rather than mint a duplicate.
            raise RuntimeError("connect token mint collided; retry")
        return token_str, entry

    async def redeem(self, token: str) -> ConnectToken | None:
        client = get_redis()
        try:
            raw = await client.getdel(_token_key(token))
        except Exception:
            # Older Redis (<6.2) — fall back to Lua. Cached
            # script eval is cheap on subsequent calls.
            raw = await client.eval(
                _GETDEL_LUA, 1, _token_key(token)
            )
        if raw is None:
            return None
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8")
        try:
            data = json.loads(raw)
        except (TypeError, ValueError):
            logger.warning("redis token payload not JSON; dropping")
            return None
        # Redis already enforced expiry via EX. We re-check
        # against wall time as a defensive belt; a clock
        # skew between Redis + this process would otherwise
        # let an expired token through.
        if data["expires_at_epoch_s"] <= time.time():
            return None
        return ConnectToken(
            user_id=uuid.UUID(data["user_id"]),
            device_id=uuid.UUID(data["device_id"]),
            expires_at_epoch_s=float(data["expires_at_epoch_s"]),
        )

    async def reset_for_test(self) -> None:
        # The test harness FLUSHDB's its scoped Redis db; no
        # per-store reset needed in the Redis backend.
        return None


# --- Factory ---


_store: TokenStore | None = None


def get_token_store() -> TokenStore:
    """Process-wide singleton, dispatching on
    ``LIVE_BACKPLANE``. Tests inject via ``set_for_tests``."""
    global _store
    if _store is None:
        backend = get_settings().live_backplane
        if backend == "redis":
            _store = RedisTokenStore()
        else:
            _store = InProcessTokenStore()
    return _store


def set_for_tests(store: TokenStore | None) -> None:
    global _store
    _store = store
