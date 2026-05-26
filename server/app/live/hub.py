"""V3 #14 broadcast hub interface + V0.1 in-process impl.

Both reviewers in triad 141 (codex #10 + gemini #10) flagged a
critical design constraint: the hub interface MUST distinguish
ephemeral pub/sub from ordered/durable delivery up front so the
V3 #17 swap (Redis backplane) doesn't force a contract break.

Three lanes:

- `publish_ephemeral` / `subscribe_ephemeral` — best-effort,
  unordered across subscribers, no replay. Presence, cursors,
  typing indicators, ephemeral chat hints.
  V0.1 in-process queue with oldest-drop on saturation;
  V0.2 Redis pub/sub.

- `publish_ordered` / `subscribe_ordered` — best-effort
  per-stream ordering with a small replay buffer keyed by
  cursor id. NOT durable across server restarts in V0.1.
  Chat history catch-up, ordered state diffs.
  V0.1 in-process deque; V0.2 Redis Streams (XADD/XREAD).

- `publish_control` / `subscribe_control` — cross-worker
  control plane. V3 #14 uses this for the revocation
  broadcast (codex 141 #7): when a pairing or device is
  revoked, every WS worker subscribed to the same control
  topic kills its matching sockets.
"""

from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from typing import AsyncIterator, Protocol


class BroadcastHub(Protocol):
    """V3 #14 abstract hub. All three lanes route by `topic`.

    `topic` is a routing key opaque to the hub. V3 #14 uses
    ``plugin:{plugin_row_id}`` for the WS fan-out and
    ``control:pairing-revocation`` for the revocation
    broadcast. V3 #16 (`card.patch`) and V3 #17 (Redis swap)
    will use additional topic shapes.
    """

    async def publish_ephemeral(self, topic: str, message: str) -> int:
        """Best-effort fan-out to current subscribers. Returns
        the number of subscribers delivered to. Slow subscribers
        get oldest-message-dropped under back-pressure."""
        ...

    async def subscribe_ephemeral(
        self, topic: str
    ) -> "EphemeralSubscription":
        """Subscribe to [topic]'s ephemeral lane. Yields fresh
        messages only — no replay. Close via `unsubscribe()`."""
        ...

    async def publish_ordered(
        self, topic: str, message: str, cursor_id: str
    ) -> int:
        """Append to [topic]'s ordered log. [cursor_id] is the
        sender-assigned identifier subscribers use for replay.
        Returns 1 if accepted (V0.1 in-process always accepts;
        V0.2 Redis Streams returns the XADD-id count)."""
        ...

    async def subscribe_ordered(
        self, topic: str, since_cursor: str | None = None
    ) -> "OrderedSubscription":
        """Subscribe to [topic]'s ordered lane. If
        [since_cursor] is provided, replay messages with
        cursor_ids strictly greater than it before live
        delivery resumes."""
        ...

    async def publish_control(self, control_topic: str, event: str) -> int:
        """Cross-worker control channel. V3 #14 uses
        ``control:pairing-revocation`` for revocation events
        (codex 141 #7)."""
        ...

    async def subscribe_control(
        self, control_topic: str
    ) -> "EphemeralSubscription":
        """Subscribe to the control channel for [control_topic]."""
        ...


class EphemeralSubscription:
    """Active subscription to an ephemeral or control topic."""

    def __init__(
        self,
        hub: "InProcessBroadcastHub",
        topic: str,
        queue: asyncio.Queue[str],
        is_control: bool,
    ):
        self._hub = hub
        self._topic = topic
        self._queue = queue
        self._is_control = is_control
        self._closed = False

    async def messages(self) -> AsyncIterator[str]:
        while not self._closed:
            try:
                msg = await self._queue.get()
            except asyncio.CancelledError:
                self._closed = True
                raise
            yield msg

    async def unsubscribe(self) -> None:
        if self._closed:
            return
        self._closed = True
        await self._hub._unsubscribe_ephemeral(
            self._topic, self._queue, self._is_control
        )


class OrderedSubscription:
    """Active subscription to the ordered lane. Yields
    (cursor_id, message) tuples in append order."""

    def __init__(
        self,
        hub: "InProcessBroadcastHub",
        topic: str,
        queue: asyncio.Queue[tuple[str, str]],
    ):
        self._hub = hub
        self._topic = topic
        self._queue = queue
        self._closed = False

    async def messages(self) -> AsyncIterator[tuple[str, str]]:
        while not self._closed:
            try:
                pair = await self._queue.get()
            except asyncio.CancelledError:
                self._closed = True
                raise
            yield pair

    async def unsubscribe(self) -> None:
        if self._closed:
            return
        self._closed = True
        await self._hub._unsubscribe_ordered(self._topic, self._queue)


class InProcessBroadcastHub:
    """V0.1 single-worker in-process [BroadcastHub] impl.

    The interface contract above is what the V3 #17 Redis-backed
    implementation will satisfy without touching call sites.

    Concurrency: a single `asyncio.Lock` serializes mutation
    of the subscriber maps. Hot-path message delivery is
    lock-free per-queue.
    """

    EPHEMERAL_QUEUE_CAP = 64
    ORDERED_QUEUE_CAP = 128
    ORDERED_REPLAY_CAP = 256

    def __init__(self) -> None:
        self._ephemeral: dict[str, set[asyncio.Queue[str]]] = defaultdict(set)
        self._control: dict[str, set[asyncio.Queue[str]]] = defaultdict(set)
        self._ordered_subs: dict[
            str, set[asyncio.Queue[tuple[str, str]]]
        ] = defaultdict(set)
        # Per-topic ring buffer for ordered-lane replay. Bounded
        # so a single noisy topic can't OOM the worker.
        self._ordered_log: dict[str, deque[tuple[str, str]]] = defaultdict(
            lambda: deque(maxlen=self.ORDERED_REPLAY_CAP)
        )
        self._lock = asyncio.Lock()

    # --- Ephemeral lane ---

    async def publish_ephemeral(self, topic: str, message: str) -> int:
        async with self._lock:
            queues = list(self._ephemeral.get(topic, ()))
        return self._fan_out(queues, message)

    async def subscribe_ephemeral(self, topic: str) -> EphemeralSubscription:
        queue: asyncio.Queue[str] = asyncio.Queue(
            maxsize=self.EPHEMERAL_QUEUE_CAP
        )
        async with self._lock:
            self._ephemeral[topic].add(queue)
        return EphemeralSubscription(self, topic, queue, is_control=False)

    async def _unsubscribe_ephemeral(
        self, topic: str, queue: asyncio.Queue[str], is_control: bool
    ) -> None:
        registry = self._control if is_control else self._ephemeral
        async with self._lock:
            registry[topic].discard(queue)
            if not registry[topic]:
                registry.pop(topic, None)

    # --- Ordered lane ---

    async def publish_ordered(
        self, topic: str, message: str, cursor_id: str
    ) -> int:
        async with self._lock:
            self._ordered_log[topic].append((cursor_id, message))
            queues = list(self._ordered_subs.get(topic, ()))
        delivered = 0
        for q in queues:
            if self._enqueue_with_drop(q, (cursor_id, message)):
                delivered += 1
        return delivered

    async def subscribe_ordered(
        self, topic: str, since_cursor: str | None = None
    ) -> OrderedSubscription:
        queue: asyncio.Queue[tuple[str, str]] = asyncio.Queue(
            maxsize=self.ORDERED_QUEUE_CAP
        )
        async with self._lock:
            # Drain replay buffer past since_cursor BEFORE
            # registering the live subscription so we don't
            # double-deliver any in-flight publish_ordered.
            if since_cursor is not None:
                for cursor_id, msg in self._ordered_log.get(topic, ()):
                    if cursor_id > since_cursor:
                        self._enqueue_with_drop(queue, (cursor_id, msg))
            self._ordered_subs[topic].add(queue)
        return OrderedSubscription(self, topic, queue)

    async def _unsubscribe_ordered(
        self, topic: str, queue: asyncio.Queue[tuple[str, str]]
    ) -> None:
        async with self._lock:
            self._ordered_subs[topic].discard(queue)
            if not self._ordered_subs[topic]:
                self._ordered_subs.pop(topic, None)

    # --- Control lane ---

    async def publish_control(self, control_topic: str, event: str) -> int:
        async with self._lock:
            queues = list(self._control.get(control_topic, ()))
        return self._fan_out(queues, event)

    async def subscribe_control(
        self, control_topic: str
    ) -> EphemeralSubscription:
        queue: asyncio.Queue[str] = asyncio.Queue(
            maxsize=self.EPHEMERAL_QUEUE_CAP
        )
        async with self._lock:
            self._control[control_topic].add(queue)
        return EphemeralSubscription(self, control_topic, queue, is_control=True)

    # --- Internals ---

    def _fan_out(self, queues: list[asyncio.Queue[str]], message: str) -> int:
        delivered = 0
        for q in queues:
            if self._enqueue_with_drop(q, message):
                delivered += 1
        return delivered

    @staticmethod
    def _enqueue_with_drop(queue: asyncio.Queue, value: object) -> bool:
        """Put [value] into [queue]; on QueueFull drop the oldest
        and retry once. Returns True on enqueue, False if the
        retry also fails."""
        try:
            queue.put_nowait(value)
            return True
        except asyncio.QueueFull:
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
            try:
                queue.put_nowait(value)
                return True
            except asyncio.QueueFull:
                return False

    # --- Test helpers ---

    def _ephemeral_subscriber_count_for_test(self, topic: str) -> int:
        return len(self._ephemeral.get(topic, ()))

    def _ordered_subscriber_count_for_test(self, topic: str) -> int:
        return len(self._ordered_subs.get(topic, ()))

    def _control_subscriber_count_for_test(self, topic: str) -> int:
        return len(self._control.get(topic, ()))


# Process-singleton hub. Tests reset via `set_hub(...)`.
_HUB: BroadcastHub = InProcessBroadcastHub()


def get_hub() -> BroadcastHub:
    return _HUB


def set_hub(hub: BroadcastHub) -> None:
    """For tests + V3 #17 swap-time. Production code should not
    call this outside startup."""
    global _HUB
    _HUB = hub


# --- Topic-namespace helpers (keep call sites grep-able) ---


def plugin_topic(user_id: str, plugin_row_id: str) -> str:
    """V3 #14 WS fan-out routing key.

    Triad 144 gemini CRITICAL fix: scoped to (user, plugin)
    NOT just (plugin). The previous global `plugin:{rowId}`
    topic delivered every push to every device for every
    user paired with that sender — User B's WS would receive
    frames addressed to User A, leaking metadata even when
    the V2 envelope payload remained opaque.
    """
    return f"user:{user_id}:plugin:{plugin_row_id}"


def pairing_revocation_topic() -> str:
    """Server-wide control topic the pairing-delete + device-
    revoke paths publish to. WS sockets subscribe to this and
    actively close on matching events (codex 141 #7)."""
    return "control:pairing-revocation"
