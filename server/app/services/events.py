"""In-process event bus for SSE fan-out.

Phase 2: replaces the Android client's 15-second polling loop with a
foreground-only Server-Sent Events stream. Sensitive routes (message
send, dismiss, state put, device revoke) publish hint events here;
[app.routers.events.events] streams them to every subscribed device of
the same user.

Scope: in-process. One uvicorn worker = one event bus. Multi-worker
production scaling lands later via Redis pub/sub (or NATS / pgnotify);
the bus interface in this module is intentionally narrow so that
swap-in is straightforward.

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
import uuid
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)


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


class EventBus:
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
        self._next_id = 0

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
        async with self._lock:
            self._next_id += 1
            event = Event(type=event_type, data=data, id=str(self._next_id))
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


# Single process-wide instance. Tests reset via [_reset_for_tests].
_bus = EventBus()


def get_event_bus() -> EventBus:
    return _bus


def _reset_for_tests() -> None:
    """Test helper — resets the global event bus so test isolation
    works without leaking subscribers between cases."""
    global _bus
    _bus = EventBus()


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
