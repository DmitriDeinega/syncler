"""V3 #14 step 1 — BroadcastHub interface + in-process impl tests.

Covers the ephemeral / ordered / control lane split (codex 141
#10 + gemini 141 #10) that the V3 #17 Redis backend will swap
into without breaking call sites.
"""

from __future__ import annotations

import asyncio

import pytest

from app.live.hub import (
    BroadcastHub,
    InProcessBroadcastHub,
    OrderedSubscription,
    pairing_revocation_topic,
    plugin_topic,
)


@pytest.fixture
def hub() -> InProcessBroadcastHub:
    return InProcessBroadcastHub()


# --- Ephemeral lane ---


@pytest.mark.asyncio
async def test_ephemeral_publish_with_no_subscribers_returns_zero(
    hub: InProcessBroadcastHub,
) -> None:
    delivered = await hub.publish_ephemeral("topic-a", "hello")
    assert delivered == 0


@pytest.mark.asyncio
async def test_ephemeral_single_subscriber_receives_published_message(
    hub: InProcessBroadcastHub,
) -> None:
    sub = await hub.subscribe_ephemeral("topic-a")
    delivered = await hub.publish_ephemeral("topic-a", "hello")
    assert delivered == 1
    async for msg in sub.messages():
        assert msg == "hello"
        await sub.unsubscribe()
        break


@pytest.mark.asyncio
async def test_ephemeral_two_subscribers_both_receive(
    hub: InProcessBroadcastHub,
) -> None:
    sub_a = await hub.subscribe_ephemeral("topic-a")
    sub_b = await hub.subscribe_ephemeral("topic-a")
    delivered = await hub.publish_ephemeral("topic-a", "broadcast")
    assert delivered == 2
    # Pull one message from each.
    async for msg in sub_a.messages():
        assert msg == "broadcast"
        break
    async for msg in sub_b.messages():
        assert msg == "broadcast"
        break
    await sub_a.unsubscribe()
    await sub_b.unsubscribe()


@pytest.mark.asyncio
async def test_ephemeral_topic_isolation(hub: InProcessBroadcastHub) -> None:
    sub_a = await hub.subscribe_ephemeral("topic-a")
    sub_b = await hub.subscribe_ephemeral("topic-b")
    await hub.publish_ephemeral("topic-a", "only-a")
    assert hub._ephemeral_subscriber_count_for_test("topic-a") == 1
    assert hub._ephemeral_subscriber_count_for_test("topic-b") == 1
    # sub-a sees the message; sub-b should not.
    async for msg in sub_a.messages():
        assert msg == "only-a"
        break
    # sub-b queue should be empty; verify by reading with timeout.
    try:
        await asyncio.wait_for(sub_b._queue.get(), timeout=0.05)
        pytest.fail("topic-b subscriber unexpectedly received message")
    except asyncio.TimeoutError:
        pass
    await sub_a.unsubscribe()
    await sub_b.unsubscribe()


@pytest.mark.asyncio
async def test_ephemeral_oldest_drop_under_saturation(
    hub: InProcessBroadcastHub,
) -> None:
    sub = await hub.subscribe_ephemeral("topic-a")
    # Fill the queue past its cap.
    for i in range(hub.EPHEMERAL_QUEUE_CAP + 5):
        await hub.publish_ephemeral("topic-a", f"msg-{i}")
    # Read back: the FIRST few messages (msg-0..msg-4) should
    # have been dropped to make room.
    received = []
    for _ in range(hub.EPHEMERAL_QUEUE_CAP):
        received.append(await sub._queue.get())
    # Last EPHEMERAL_QUEUE_CAP messages should be retained.
    assert received[-1] == f"msg-{hub.EPHEMERAL_QUEUE_CAP + 4}"
    await sub.unsubscribe()


@pytest.mark.asyncio
async def test_ephemeral_unsubscribe_removes_from_registry(
    hub: InProcessBroadcastHub,
) -> None:
    sub = await hub.subscribe_ephemeral("topic-a")
    assert hub._ephemeral_subscriber_count_for_test("topic-a") == 1
    await sub.unsubscribe()
    assert hub._ephemeral_subscriber_count_for_test("topic-a") == 0


# --- Ordered lane ---


@pytest.mark.asyncio
async def test_ordered_publish_returns_accept_count(
    hub: InProcessBroadcastHub,
) -> None:
    delivered = await hub.publish_ordered("topic-a", "msg1", cursor_id="c1")
    # No live subscribers, but V0.1 in-process always logs the
    # append. Returns 0 live deliveries; the entry sits in the
    # replay buffer.
    assert delivered == 0


@pytest.mark.asyncio
async def test_ordered_subscriber_receives_in_order(
    hub: InProcessBroadcastHub,
) -> None:
    sub = await hub.subscribe_ordered("topic-a")
    await hub.publish_ordered("topic-a", "m1", cursor_id="c1")
    await hub.publish_ordered("topic-a", "m2", cursor_id="c2")
    await hub.publish_ordered("topic-a", "m3", cursor_id="c3")
    received: list[tuple[str, str]] = []
    for _ in range(3):
        received.append(await sub._queue.get())
    assert [c for c, _ in received] == ["c1", "c2", "c3"]
    assert [m for _, m in received] == ["m1", "m2", "m3"]
    await sub.unsubscribe()


@pytest.mark.asyncio
async def test_ordered_replay_since_cursor(
    hub: InProcessBroadcastHub,
) -> None:
    # Append before any subscriber exists.
    await hub.publish_ordered("topic-a", "m1", cursor_id="c1")
    await hub.publish_ordered("topic-a", "m2", cursor_id="c2")
    await hub.publish_ordered("topic-a", "m3", cursor_id="c3")
    # Subscribe with replay-from cursor.
    sub = await hub.subscribe_ordered("topic-a", since_cursor="c1")
    received: list[tuple[str, str]] = []
    # The replay should deliver c2 and c3 (strictly > c1).
    for _ in range(2):
        received.append(await sub._queue.get())
    assert [c for c, _ in received] == ["c2", "c3"]
    await sub.unsubscribe()


@pytest.mark.asyncio
async def test_ordered_replay_skipped_when_since_cursor_is_none(
    hub: InProcessBroadcastHub,
) -> None:
    await hub.publish_ordered("topic-a", "m1", cursor_id="c1")
    sub = await hub.subscribe_ordered("topic-a", since_cursor=None)
    # Queue should be empty after subscribe — no replay.
    try:
        await asyncio.wait_for(sub._queue.get(), timeout=0.05)
        pytest.fail("ordered subscriber unexpectedly received replay")
    except asyncio.TimeoutError:
        pass
    await sub.unsubscribe()


@pytest.mark.asyncio
async def test_ordered_unsubscribe_removes_from_registry(
    hub: InProcessBroadcastHub,
) -> None:
    sub = await hub.subscribe_ordered("topic-a")
    assert hub._ordered_subscriber_count_for_test("topic-a") == 1
    await sub.unsubscribe()
    assert hub._ordered_subscriber_count_for_test("topic-a") == 0


# --- Control lane ---


@pytest.mark.asyncio
async def test_control_lane_is_isolated_from_ephemeral_with_same_topic(
    hub: InProcessBroadcastHub,
) -> None:
    """The ephemeral and control lanes share publisher API
    shape but live in separate maps. A subscriber to one must
    not see messages published on the other."""
    eph_sub = await hub.subscribe_ephemeral("shared-topic")
    ctrl_sub = await hub.subscribe_control("shared-topic")
    await hub.publish_ephemeral("shared-topic", "eph-msg")
    await hub.publish_control("shared-topic", "ctrl-msg")
    eph_msg = await eph_sub._queue.get()
    ctrl_msg = await ctrl_sub._queue.get()
    assert eph_msg == "eph-msg"
    assert ctrl_msg == "ctrl-msg"
    await eph_sub.unsubscribe()
    await ctrl_sub.unsubscribe()


@pytest.mark.asyncio
async def test_control_pairing_revocation_topic_fan_out(
    hub: InProcessBroadcastHub,
) -> None:
    """V3 #14 expected use: revocation-event bus. Multiple WS
    workers subscribe to ``control:pairing-revocation`` and
    kill their sockets when an event arrives."""
    sub_a = await hub.subscribe_control(pairing_revocation_topic())
    sub_b = await hub.subscribe_control(pairing_revocation_topic())
    delivered = await hub.publish_control(
        pairing_revocation_topic(),
        '{"user_id":"u1","device_id":"d1"}',
    )
    assert delivered == 2
    assert (await sub_a._queue.get()).startswith('{"user_id"')
    assert (await sub_b._queue.get()).startswith('{"user_id"')
    await sub_a.unsubscribe()
    await sub_b.unsubscribe()


# --- Topic namespace helpers ---


def test_plugin_topic_format() -> None:
    assert plugin_topic("user-7", "abc-123") == "user:user-7:plugin:abc-123"


def test_pairing_revocation_topic_is_stable() -> None:
    assert pairing_revocation_topic() == "control:pairing-revocation"


# --- Interface compliance ---


def test_in_process_hub_satisfies_protocol() -> None:
    """Static-type sanity: the in-process impl must conform to
    the BroadcastHub Protocol. If a Protocol method goes missing
    in either side, mypy / static checks would catch it; this
    runtime check is a belt-and-braces."""
    hub: BroadcastHub = InProcessBroadcastHub()
    assert hasattr(hub, "publish_ephemeral")
    assert hasattr(hub, "subscribe_ephemeral")
    assert hasattr(hub, "publish_ordered")
    assert hasattr(hub, "subscribe_ordered")
    assert hasattr(hub, "publish_control")
    assert hasattr(hub, "subscribe_control")
