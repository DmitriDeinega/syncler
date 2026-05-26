"""V3 #17 — Redis-backed BroadcastHub implementation.

Three lanes per the V3 #14 BroadcastHub Protocol, mapped to
Redis primitives per docs/live-backplane.md "Lane mapping":

- Ephemeral → Redis PUB/SUB (best-effort, no replay).
- Ordered  → Redis Streams (XADD MAXLEN, XREAD/XRANGE).
- Control  → Redis PUB/SUB (best-effort, heartbeat-bounded).

Per-worker subscription registry: each worker opens ONE
Redis SUBSCRIBE per active ephemeral/control topic, ref-
counted by the local subscriber set. Last local unsubscribe
tears down the Redis subscription. Ordered subscribers each
get their own XREAD reader task because they have
per-subscriber cursor state.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator

from app.config import get_settings
from app.redis_client import get_redis, prefixed

logger = logging.getLogger(__name__)


# Tune-knobs that mirror the in-process backend so subscriber
# behavior is the same regardless of which lane.
EPHEMERAL_QUEUE_CAP = 64
ORDERED_QUEUE_CAP = 128


def _eph_channel(topic: str) -> str:
    return prefixed(f"live:eph:{topic}")


def _ctrl_channel(topic: str) -> str:
    return prefixed(f"live:ctrl:{topic}")


def _ord_stream(topic: str) -> str:
    return prefixed(f"live:ord:{topic}")


# --- Ephemeral / control subscription handle ---


class _PubSubSubscription:
    """Local handle returned by ``subscribe_ephemeral`` /
    ``subscribe_control``. Drains the per-subscriber queue
    fed by the hub's Redis reader task. The same handle
    shape that ``InProcessBroadcastHub.EphemeralSubscription``
    exposes, so call sites don't branch on backend."""

    def __init__(
        self,
        hub: "RedisBroadcastHub",
        topic: str,
        queue: asyncio.Queue[str],
        is_control: bool,
    ) -> None:
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
        await self._hub._unsubscribe_pubsub(
            self._topic, self._queue, self._is_control
        )


# --- Ordered subscription handle ---


class _StreamSubscription:
    """Local handle for an ordered-lane subscriber. Each
    subscriber owns a dedicated XREAD reader task (cursor
    state is per-subscriber)."""

    def __init__(
        self,
        hub: "RedisBroadcastHub",
        topic: str,
        queue: asyncio.Queue[tuple[str, str]],
        reader_task: asyncio.Task[None],
    ) -> None:
        self._hub = hub
        self._topic = topic
        self._queue = queue
        self._reader_task = reader_task
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
        if not self._reader_task.done():
            self._reader_task.cancel()


# --- Hub ---


class RedisBroadcastHub:
    """V3 #17 Redis-backed hub. Implements the V3 #14
    BroadcastHub Protocol surface byte-for-byte (publish_*
    + subscribe_* methods)."""

    def __init__(self) -> None:
        # Per-topic local subscriber queues.
        self._eph_queues: dict[str, set[asyncio.Queue[str]]] = {}
        self._ctrl_queues: dict[str, set[asyncio.Queue[str]]] = {}
        # Per-topic Redis subscription tasks. Started on first
        # local subscriber for that topic, cancelled when the
        # last one leaves (ref-counted via _eph_queues).
        self._eph_tasks: dict[str, asyncio.Task[None]] = {}
        self._ctrl_tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

    # --- Ephemeral lane (Redis PUB/SUB) ---

    async def publish_ephemeral(self, topic: str, message: str) -> int:
        client = get_redis()
        # Returns Redis subscriber count (workers listening),
        # NOT downstream device count — best-effort telemetry
        # per spec.
        return await client.publish(_eph_channel(topic), message)

    async def subscribe_ephemeral(self, topic: str) -> _PubSubSubscription:
        queue: asyncio.Queue[str] = asyncio.Queue(maxsize=EPHEMERAL_QUEUE_CAP)
        async with self._lock:
            need_task = topic not in self._eph_queues
            self._eph_queues.setdefault(topic, set()).add(queue)
            if need_task:
                self._eph_tasks[topic] = asyncio.create_task(
                    self._reader_loop_pubsub(
                        _eph_channel(topic), self._eph_queues, topic
                    ),
                    name=f"hub-redis-eph-{topic}",
                )
        return _PubSubSubscription(self, topic, queue, is_control=False)

    # --- Control lane (Redis PUB/SUB) ---

    async def publish_control(self, control_topic: str, event: str) -> int:
        client = get_redis()
        return await client.publish(_ctrl_channel(control_topic), event)

    async def subscribe_control(self, control_topic: str) -> _PubSubSubscription:
        queue: asyncio.Queue[str] = asyncio.Queue(maxsize=EPHEMERAL_QUEUE_CAP)
        async with self._lock:
            need_task = control_topic not in self._ctrl_queues
            self._ctrl_queues.setdefault(control_topic, set()).add(queue)
            if need_task:
                self._ctrl_tasks[control_topic] = asyncio.create_task(
                    self._reader_loop_pubsub(
                        _ctrl_channel(control_topic),
                        self._ctrl_queues,
                        control_topic,
                    ),
                    name=f"hub-redis-ctrl-{control_topic}",
                )
        return _PubSubSubscription(self, control_topic, queue, is_control=True)

    async def _unsubscribe_pubsub(
        self,
        topic: str,
        queue: asyncio.Queue[str],
        is_control: bool,
    ) -> None:
        registry = self._ctrl_queues if is_control else self._eph_queues
        tasks = self._ctrl_tasks if is_control else self._eph_tasks
        async with self._lock:
            registry.get(topic, set()).discard(queue)
            if not registry.get(topic):
                registry.pop(topic, None)
                task = tasks.pop(topic, None)
                if task is not None and not task.done():
                    task.cancel()

    async def _reader_loop_pubsub(
        self,
        redis_channel: str,
        local_registry: dict[str, set[asyncio.Queue[str]]],
        topic: str,
    ) -> None:
        client = get_redis()
        pubsub = client.pubsub()
        try:
            await pubsub.subscribe(redis_channel)
            while True:
                msg = await pubsub.get_message(
                    ignore_subscribe_messages=True, timeout=None
                )
                if msg is None or msg.get("type") != "message":
                    continue
                raw = msg.get("data")
                if isinstance(raw, (bytes, bytearray)):
                    raw = raw.decode("utf-8")
                # Fan out to local subscribers with bounded
                # oldest-drop, matching the in-process impl.
                async with self._lock:
                    queues = list(local_registry.get(topic, ()))
                for q in queues:
                    self._enqueue_with_drop(q, raw)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception(
                "hub(redis): reader loop died channel=%s", redis_channel
            )
        finally:
            try:
                await pubsub.unsubscribe(redis_channel)
            except Exception:
                pass
            await pubsub.aclose()

    # --- Ordered lane (Redis Streams) ---

    async def publish_ordered(
        self, topic: str, message: str, cursor_id: str
    ) -> int:
        client = get_redis()
        maxlen = get_settings().live_ordered_stream_maxlen
        await client.xadd(
            _ord_stream(topic),
            {"cursor_id": cursor_id, "message": message},
            maxlen=maxlen,
            approximate=True,
        )
        return 1

    async def subscribe_ordered(
        self, topic: str, since_cursor: str | None = None
    ) -> _StreamSubscription:
        queue: asyncio.Queue[tuple[str, str]] = asyncio.Queue(
            maxsize=ORDERED_QUEUE_CAP
        )
        reader_task = asyncio.create_task(
            self._reader_loop_ordered(topic, queue, since_cursor),
            name=f"hub-redis-ord-{topic}",
        )
        return _StreamSubscription(self, topic, queue, reader_task)

    async def _reader_loop_ordered(
        self,
        topic: str,
        queue: asyncio.Queue[tuple[str, str]],
        since_cursor: str | None,
    ) -> None:
        """Walk the Redis Stream for [topic].

        Cursor mapping (spec docs/live-backplane.md "Lane
        mapping — ordered with replay"):
          1. If [since_cursor] is None → XREAD from $ (live only).
          2. Else → XRANGE the recent entries; find the one
             whose ``cursor_id`` field == [since_cursor];
             capture its stream ID S*; replay entries with
             stream ID > S* then transition to live XREAD.
          3. If [since_cursor] is not in the retained window,
             log a warning and start live only. Postgres is
             authoritative for durable catch-up.
        """
        client = get_redis()
        stream = _ord_stream(topic)
        last_id = "$"
        try:
            if since_cursor is not None:
                resolved = await self._resolve_since_cursor(stream, since_cursor)
                if resolved is None:
                    logger.warning(
                        "hub(redis): ordered cursor %s not found for topic=%s; live only",
                        since_cursor, topic,
                    )
                else:
                    # Replay entries strictly after the
                    # resolved stream ID, then continue live.
                    entries = await client.xrange(stream, min=f"({resolved}", max="+")
                    for stream_id, fields in entries:
                        cursor_b = fields.get(b"cursor_id") or fields.get("cursor_id")
                        msg_b = fields.get(b"message") or fields.get("message")
                        if cursor_b is None or msg_b is None:
                            continue
                        self._enqueue_ord_with_drop(
                            queue,
                            (
                                _maybe_decode(cursor_b),
                                _maybe_decode(msg_b),
                            ),
                        )
                        last_id = _maybe_decode(stream_id)

            while True:
                result = await client.xread(
                    streams={stream: last_id},
                    count=64,
                    block=0,  # block indefinitely
                )
                if not result:
                    continue
                for _stream_name, entries in result:
                    for stream_id, fields in entries:
                        last_id = _maybe_decode(stream_id)
                        cursor_b = fields.get(b"cursor_id") or fields.get("cursor_id")
                        msg_b = fields.get(b"message") or fields.get("message")
                        if cursor_b is None or msg_b is None:
                            continue
                        self._enqueue_ord_with_drop(
                            queue,
                            (
                                _maybe_decode(cursor_b),
                                _maybe_decode(msg_b),
                            ),
                        )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("hub(redis): ordered reader died topic=%s", topic)

    async def _resolve_since_cursor(
        self, stream: str, since_cursor: str
    ) -> str | None:
        """XRANGE-scan recent entries to find the stream ID
        whose ``cursor_id`` field equals [since_cursor]. Walks
        the most recent ``maxlen`` entries — for v0.1 that's
        the configurable Redis Streams cap (default 1024).

        Returns the matched stream ID (as string) or None
        if not found (trimmed away or never written)."""
        client = get_redis()
        # XRANGE returns chronological order. Cheap on small
        # streams; if a v0.2 deployment uses very large
        # streams + sparse cursor lookups, swap to XREVRANGE +
        # early-exit on match.
        entries = await client.xrange(stream, min="-", max="+")
        for stream_id, fields in entries:
            cursor_b = fields.get(b"cursor_id") or fields.get("cursor_id")
            if cursor_b is None:
                continue
            if _maybe_decode(cursor_b) == since_cursor:
                return _maybe_decode(stream_id)
        return None

    # --- Local-queue saturation helpers (oldest-drop) ---

    def _enqueue_with_drop(
        self, queue: asyncio.Queue[str], value: str
    ) -> bool:
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

    def _enqueue_ord_with_drop(
        self,
        queue: asyncio.Queue[tuple[str, str]],
        value: tuple[str, str],
    ) -> bool:
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


def _maybe_decode(value: Any) -> str:
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8")
    return str(value)
