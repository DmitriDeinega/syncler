"""V3 #17 — Redis async client factory + lifecycle.

Single process-wide redis.asyncio client pool. Used by the
three live-backplane components (BroadcastHub, EventBus,
connect-token store) when ``LIVE_BACKPLANE=redis``.

Spec: docs/live-backplane.md "Redis client factory".

Production posture (v0.1):
- One pool per process (max 32 connections), shared across
  all backplane callers.
- Connections set up with ``decode_responses=False`` — the
  caller decides per-value whether to decode (PUB/SUB and
  Streams both carry opaque envelope JSON; decoding twice
  wastes cycles).
- Health check on startup: ``ping()`` in
  ``ensure_connected_or_raise``. Fail loud if Redis is
  unreachable when ``LIVE_BACKPLANE=redis`` is set
  (no automatic memory fallback — spec
  "Failure modes — fail closed").
"""

from __future__ import annotations

import logging

import redis.asyncio as aioredis

from app.config import get_settings

logger = logging.getLogger(__name__)

_client: aioredis.Redis | None = None


def get_redis() -> aioredis.Redis:
    """Lazily build the process-wide async Redis client.

    Safe to call from any worker / route handler — the
    client is connection-pool-backed and a no-op when
    already initialized.

    Tests inject via ``set_for_tests``.
    """
    global _client
    if _client is None:
        settings = get_settings()
        _client = aioredis.from_url(
            settings.redis_url,
            decode_responses=False,
            max_connections=32,
            socket_connect_timeout=5,
            socket_timeout=10,
        )
    return _client


async def ensure_connected_or_raise() -> None:
    """Called at app startup when ``LIVE_BACKPLANE=redis``.

    Raises ``redis.ConnectionError`` (or any underlying I/O
    error) up the FastAPI lifespan, so the worker fails to
    start instead of accepting requests that will all fail
    once a real backplane operation hits Redis.
    """
    client = get_redis()
    await client.ping()
    logger.info("redis: live backplane reachable")


async def close_redis() -> None:
    """Tear down on app shutdown. Safe to call when no
    client was ever created."""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def set_for_tests(client: aioredis.Redis | None) -> None:
    """Test seam — inject a fake/real Redis client for the
    duration of a test. Pass ``None`` to clear."""
    global _client
    _client = client


def prefixed(key: str) -> str:
    """Apply the ``LIVE_REDIS_KEY_PREFIX`` to a key. Centralized
    so callers don't sprinkle prefix concatenation across the
    backplane. Spec: docs/live-backplane.md "Configuration"."""
    prefix = get_settings().live_redis_key_prefix
    return f"{prefix}{key}" if prefix else key
