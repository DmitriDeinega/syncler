"""Event bus for SSE fan-out.

Phase 2: replaces the Android client's 15-second polling loop with a
foreground-only Server-Sent Events stream. Sensitive routes (message
send, dismiss, state put, device revoke) publish hint events here;
[app.routers.events.events] streams them to every subscribed device of
the same user.

V3 #17 (docs/live-backplane.md): the bus now has two backends. The
default ``InProcessEventBus`` keeps the V1 single-worker shape; the
``RedisEventBus`` PUB/SUB-fans-out across workers using per-user
channels + a shared device-close channel.

Events are HINTS, not authoritative data: the device pulls the actual
content via the existing REST endpoints. This keeps the encryption
model unchanged (the inbox payload still rides on /v1/messages/inbox
under the device-bound JWT auth context) and the SSE channel itself
content-blind.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass
from typing import Any, Protocol

logger = logging.getLogger(__name__)


# V3 #17 — stable per-process identifier for SSE event-IDs. The
# pid encoded as base36 keeps the ID short while staying unique
# across workers on the same host.
_WORKER_ID = format(os.getpid(), "x")
_LAST_EPOCH_MS = 0
_SEQ_WITHIN_MS = 0
_ID_LOCK = asyncio.Lock()


async def _next_event_id() -> str:
    """V3 #17 SSE event-id shape: ``{epoch_ms}-{worker_pid_hex}-{seq}``.

    Documented as diagnostic / resume markers only — there is NO
    Last-Event-ID replay guarantee in the v0.1 Redis-PUB/SUB-backed
    bus (spec: docs/live-backplane.md "Event IDs"). The lex-sortable
    shape just makes the per-stream ordering visible to humans
    inspecting client logs.
    """
    global _LAST_EPOCH_MS, _SEQ_WITHIN_MS
    async with _ID_LOCK:
        now_ms = int(time.time() * 1000)
        if now_ms == _LAST_EPOCH_MS:
            _SEQ_WITHIN_MS += 1
        else:
            _LAST_EPOCH_MS = now_ms
            _SEQ_WITHIN_MS = 0
        return f"{now_ms}-{_WORKER_ID}-{_SEQ_WITHIN_MS}"


# Bounded queue size per subscriber. Tuned modestly — a device that
# stops reading the stream (paused, network limbo) will lose events
# rather than balloon the server's memory. The client always pulls
# /v1/messages/inbox on resume regardless, so an overflowed queue is
# self-healing.
_MAX_QUEUE = 64


@dataclass(frozen=True)
class Event:
    """A single SSE-bound event.

    `type` is the SSE event-type line ("inbox.changed", "state.changed",
    "dismiss"). `data` becomes the SSE data-line payload as JSON. `id`
    is the SSE event-id used by clients with `Last-Event-ID` resume —
    we use a monotonic per-bus counter so reconnecting clients can ask
    for everything since their last seen id.
    """

    type: str
    data: dict[str, Any]
    id: str


class _Subscriber:
    """A single connected SSE stream. Identity-hashed (not value-hashed)
    so we can store subscribers in sets keyed by (user_id, device_id)
    even though `asyncio.Queue` itself isn't hashable. Two different
    streams from the same device are different subscribers."""

    __slots__ = ("user_id", "device_id", "queue")

    def __init__(self, user_id: uuid.UUID, device_id: uuid.UUID) -> None:
        self.user_id = user_id
        self.device_id = device_id
        self.queue: asyncio.Queue[Event | None] = asyncio.Queue(maxsize=_MAX_QUEUE)

    def __hash__(self) -> int:
        return id(self)

    def __eq__(self, other: object) -> bool:
        return self is other


class EventBus(Protocol):
    """V3 #17 — abstract bus surface. Both InProcessEventBus and
    RedisEventBus implement this; ``get_event_bus()`` dispatches
    on ``LIVE_BACKPLANE``."""

    async def subscribe(self, user_id: uuid.UUID, device_id: uuid.UUID) -> _Subscriber: ...
    async def unsubscribe(self, sub: _Subscriber) -> None: ...
    async def publish_to_user(
        self, user_id: uuid.UUID, event_type: str, data: dict[str, Any]
    ) -> int: ...
    async def close_device_subscribers(self, device_id: uuid.UUID) -> None: ...


class InProcessEventBus:
    """In-process pub/sub keyed by (user_id, device_id).

    A device subscribes via [subscribe], reads from the returned queue
    until None is sentinel-pushed, then unsubscribes. Publishers call
    [publish_to_user] (fan-out to all the user's connected devices)
    or [close_device_subscribers] (server-initiated disconnect, e.g.
    on device revoke).
    """

    def __init__(self) -> None:
        # Two indices: (user_id -> {subs}) for fan-out, (device_id -> sub)
        # for targeted disconnect on revoke. A given subscriber lives in
        # both. Multiple SSE streams from the same device (rare, but
        # possible if a client reconnects before the old stream times
        # out) are tracked as a set under the device_id key.
        self._by_user: dict[uuid.UUID, set[_Subscriber]] = {}
        self._by_device: dict[uuid.UUID, set[_Subscriber]] = {}
        self._lock = asyncio.Lock()

    async def subscribe(self, user_id: uuid.UUID, device_id: uuid.UUID) -> _Subscriber:
        """Register a new SSE subscriber. Caller is responsible for
        calling [unsubscribe] in a finally block when the stream ends."""
        sub = _Subscriber(user_id=user_id, device_id=device_id)
        async with self._lock:
            self._by_user.setdefault(user_id, set()).add(sub)
            self._by_device.setdefault(device_id, set()).add(sub)
        logger.info("sse: subscribed user=%s device=%s", user_id, device_id)
        return sub

    async def unsubscribe(self, sub: _Subscriber) -> None:
        async with self._lock:
            self._by_user.get(sub.user_id, set()).discard(sub)
            if not self._by_user.get(sub.user_id):
                self._by_user.pop(sub.user_id, None)
            self._by_device.get(sub.device_id, set()).discard(sub)
            if not self._by_device.get(sub.device_id):
                self._by_device.pop(sub.device_id, None)
        logger.info("sse: unsubscribed user=%s device=%s", sub.user_id, sub.device_id)

    async def publish_to_user(self, user_id: uuid.UUID, event_type: str, data: dict[str, Any]) -> int:
        """Fan-out to every active subscriber for `user_id`. Returns the
        number of subscribers the event was queued for (callers don't
        usually care, but it's useful for tests and logging).

        Bounded-queue overflow drops the event for that subscriber and
        logs a warning. The client pulls REST endpoints on resume so a
        dropped hint is non-fatal — at worst the user's freshness is
        delayed until next foreground or next FCM wakeup.

        ID assignment AND enqueue both happen under the same lock so a
        higher-id event can't be enqueued before a lower-id one when
        two publishers race (Codex consultation 56 RED — would otherwise
        violate per-subscriber ordering). put_nowait is non-blocking so
        the lock isn't held while waiting on queue space.
        """
        delivered = 0
        event_id = await _next_event_id()
        async with self._lock:
            event = Event(type=event_type, data=data, id=event_id)
            for sub in self._by_user.get(user_id, set()):
                try:
                    sub.queue.put_nowait(event)
                    delivered += 1
                except asyncio.QueueFull:
                    logger.warning(
                        "sse: queue full for user=%s device=%s; dropping event %s",
                        sub.user_id, sub.device_id, event_type,
                    )
        return delivered

    async def close_device_subscribers(self, device_id: uuid.UUID) -> None:
        """Server-initiated disconnect for every stream tied to
        `device_id`. Invoked from the device-revoke endpoint so a
        revoked device's SSE stream closes immediately rather than
        waiting for the JWT's natural expiry."""
        async with self._lock:
            subs = list(self._by_device.get(device_id, set()))
        for sub in subs:
            try:
                # None is the sentinel: the SSE stream coroutine sees
                # it and exits its loop cleanly.
                sub.queue.put_nowait(None)
            except asyncio.QueueFull:
                # Queue full of pending events; clear it so the close
                # sentinel can land. The subscriber is going away anyway.
                while not sub.queue.empty():
                    sub.queue.get_nowait()
                sub.queue.put_nowait(None)
        logger.info("sse: closed %d subscribers for device=%s", len(subs), device_id)


# --- Redis impl (V3 #17) ---


def _sse_user_channel(user_id: uuid.UUID) -> str:
    from app.redis_client import prefixed
    return prefixed(f"sse:user:{user_id}")


_SSE_DEVICE_CLOSE_CHANNEL_SUFFIX = "sse:device-close"


def _sse_device_close_channel() -> str:
    from app.redis_client import prefixed
    return prefixed(_SSE_DEVICE_CLOSE_CHANNEL_SUFFIX)


class RedisEventBus:
    """V3 #17 — multi-worker SSE fan-out via Redis PUB/SUB.

    Each worker keeps a local subscriber set under
    ``_local_subs_by_user`` and ``_local_subs_by_device``
    (same shape as the in-process impl). When the FIRST
    local subscriber for a given user arrives, the worker
    opens a Redis SUBSCRIBE on ``sse:user:{user_id}``; when
    the LAST one leaves, the subscription closes. This is
    the reference-counted-subscription pattern from spec
    docs/live-backplane.md "EventBus".

    Workers also keep a single ambient subscription to
    ``sse:device-close`` so any worker that owns a doomed
    device's SSE stream can notify it.

    Failure posture: Redis errors propagate up so the
    triggering route surfaces a 5xx (no silent success)
    per spec "Failure modes — fail closed".
    """

    def __init__(self) -> None:
        self._local_subs_by_user: dict[uuid.UUID, set[_Subscriber]] = {}
        self._local_subs_by_device: dict[uuid.UUID, set[_Subscriber]] = {}
        self._user_subscription_tasks: dict[uuid.UUID, asyncio.Task[None]] = {}
        self._device_close_task: asyncio.Task[None] | None = None
        self._lock = asyncio.Lock()

    async def subscribe(
        self, user_id: uuid.UUID, device_id: uuid.UUID
    ) -> _Subscriber:
        sub = _Subscriber(user_id=user_id, device_id=device_id)
        async with self._lock:
            need_user_task = user_id not in self._local_subs_by_user
            self._local_subs_by_user.setdefault(user_id, set()).add(sub)
            self._local_subs_by_device.setdefault(device_id, set()).add(sub)
            if need_user_task:
                self._user_subscription_tasks[user_id] = asyncio.create_task(
                    self._reader_loop_user(user_id),
                    name=f"sse-redis-user-{user_id}",
                )
            if self._device_close_task is None:
                self._device_close_task = asyncio.create_task(
                    self._reader_loop_device_close(),
                    name="sse-redis-device-close",
                )
        logger.info("sse(redis): subscribed user=%s device=%s", user_id, device_id)
        return sub

    async def unsubscribe(self, sub: _Subscriber) -> None:
        async with self._lock:
            self._local_subs_by_user.get(sub.user_id, set()).discard(sub)
            if not self._local_subs_by_user.get(sub.user_id):
                self._local_subs_by_user.pop(sub.user_id, None)
                task = self._user_subscription_tasks.pop(sub.user_id, None)
                if task is not None and not task.done():
                    task.cancel()
            self._local_subs_by_device.get(sub.device_id, set()).discard(sub)
            if not self._local_subs_by_device.get(sub.device_id):
                self._local_subs_by_device.pop(sub.device_id, None)
        logger.info(
            "sse(redis): unsubscribed user=%s device=%s",
            sub.user_id, sub.device_id,
        )

    async def publish_to_user(
        self, user_id: uuid.UUID, event_type: str, data: dict[str, Any]
    ) -> int:
        from app.redis_client import get_redis
        event_id = await _next_event_id()
        payload = json.dumps(
            {"id": event_id, "type": event_type, "data": data},
            separators=(",", ":"),
        ).encode("utf-8")
        client = get_redis()
        # Returns the number of Redis subscribers, NOT the
        # downstream device count — best-effort telemetry
        # only per spec "Lane mapping — ephemeral".
        return await client.publish(_sse_user_channel(user_id), payload)

    async def close_device_subscribers(self, device_id: uuid.UUID) -> None:
        from app.redis_client import get_redis
        payload = json.dumps(
            {"device_id": str(device_id), "reason": "revoked"},
            separators=(",", ":"),
        ).encode("utf-8")
        client = get_redis()
        await client.publish(_sse_device_close_channel(), payload)
        logger.info("sse(redis): published device-close for %s", device_id)

    async def _reader_loop_user(self, user_id: uuid.UUID) -> None:
        from app.redis_client import get_redis
        client = get_redis()
        pubsub = client.pubsub()
        try:
            await pubsub.subscribe(_sse_user_channel(user_id))
            while True:
                msg = await pubsub.get_message(
                    ignore_subscribe_messages=True, timeout=None
                )
                if msg is None or msg.get("type") != "message":
                    continue
                raw = msg.get("data")
                if isinstance(raw, (bytes, bytearray)):
                    raw = raw.decode("utf-8")
                try:
                    parsed = json.loads(raw)
                    event = Event(
                        type=parsed["type"],
                        data=parsed["data"],
                        id=parsed["id"],
                    )
                except (TypeError, ValueError, KeyError):
                    logger.warning("sse(redis): malformed user event; dropping")
                    continue
                await self._fan_out_local(user_id, event)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("sse(redis): user reader loop died user=%s", user_id)
        finally:
            try:
                await pubsub.unsubscribe(_sse_user_channel(user_id))
            except Exception:
                pass
            await pubsub.aclose()

    async def _reader_loop_device_close(self) -> None:
        from app.redis_client import get_redis
        client = get_redis()
        pubsub = client.pubsub()
        try:
            await pubsub.subscribe(_sse_device_close_channel())
            while True:
                msg = await pubsub.get_message(
                    ignore_subscribe_messages=True, timeout=None
                )
                if msg is None or msg.get("type") != "message":
                    continue
                raw = msg.get("data")
                if isinstance(raw, (bytes, bytearray)):
                    raw = raw.decode("utf-8")
                try:
                    parsed = json.loads(raw)
                    device_id = uuid.UUID(parsed["device_id"])
                except (TypeError, ValueError, KeyError):
                    logger.warning("sse(redis): malformed close event; dropping")
                    continue
                await self._local_close_device(device_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("sse(redis): device-close reader died")
        finally:
            try:
                await pubsub.unsubscribe(_sse_device_close_channel())
            except Exception:
                pass
            await pubsub.aclose()

    async def _fan_out_local(
        self, user_id: uuid.UUID, event: Event
    ) -> None:
        async with self._lock:
            subs = list(self._local_subs_by_user.get(user_id, set()))
        for sub in subs:
            try:
                sub.queue.put_nowait(event)
            except asyncio.QueueFull:
                logger.warning(
                    "sse(redis): queue full user=%s device=%s; dropping %s",
                    sub.user_id, sub.device_id, event.type,
                )

    async def _local_close_device(self, device_id: uuid.UUID) -> None:
        async with self._lock:
            subs = list(self._local_subs_by_device.get(device_id, set()))
        for sub in subs:
            try:
                sub.queue.put_nowait(None)
            except asyncio.QueueFull:
                while not sub.queue.empty():
                    try:
                        sub.queue.get_nowait()
                    except asyncio.QueueEmpty:
                        break
                try:
                    sub.queue.put_nowait(None)
                except asyncio.QueueFull:
                    pass


# --- Factory ---


# Single process-wide instance. Tests reset via [_reset_for_tests].
_bus: EventBus | None = None


def get_event_bus() -> EventBus:
    global _bus
    if _bus is None:
        from app.config import get_settings
        backend = get_settings().live_backplane
        _bus = RedisEventBus() if backend == "redis" else InProcessEventBus()
    return _bus


def _reset_for_tests() -> None:
    """Test helper — resets the global event bus so test isolation
    works without leaking subscribers between cases."""
    global _bus
    _bus = None


# Event encoding helpers ---------------------------------------------

def encode_sse(event: Event) -> bytes:
    """Render an Event as raw SSE wire bytes (`event:`, `data:`, `id:`
    lines, terminated by a blank line). Used by the streaming
    response."""
    parts = [
        f"id: {event.id}",
        f"event: {event.type}",
        f"data: {json.dumps(event.data, separators=(',', ':'))}",
        "",
        "",
    ]
    return "\n".join(parts).encode("utf-8")


HEARTBEAT_BYTES = b": keep-alive\n\n"
