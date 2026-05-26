"""V3 #14 push-frame shape tests (triad 158/159).

Triad 158 bug 1 fix: `POST /v1/live/plugin/{id}/push` now
wraps the V2 envelope in a multiplex `message` frame so
devices' LiveChannelClient dispatches it correctly. These
tests pin the wire shape produced by the push handler
without needing a real WebSocket or Postgres — the hub's
in-process pub/sub is enough.

PG dev box has been down through V3 #14 + V3 #16 + V3 #17
+ V4 ship; these unit tests run against in-memory SQLite
via the existing conftest's setup, but they DO NOT use
the session fixture that requires a live PG — instead
they directly drive the push handler with a fake DB
session + the in-process broadcast hub.
"""

from __future__ import annotations

import base64
import json
import uuid
from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock

import pytest


pytestmark = pytest.mark.asyncio


async def _drive_push(
    *,
    plugin_row_id: str,
    channel: str,
    envelope: dict,
    paired_user_ids: list[uuid.UUID] | None = None,
    plugin_revoked: bool = False,
    plugin_exists: bool = True,
):
    """Reach into push_live with a stubbed DB session + a fresh
    in-process hub so we can assert exactly what frame shape
    arrives on the topic."""
    from app.live import hub as hub_module
    from app.live.hub import InProcessBroadcastHub, plugin_topic
    from app.models import Pairing, Plugin
    from app.routers.live import LivePushRequest, push_live

    # Fresh hub so every test runs in isolation.
    test_hub = InProcessBroadcastHub()
    hub_module.set_hub(test_hub)

    plugin = MagicMock(spec=Plugin)
    plugin.id = uuid.UUID(plugin_row_id)
    plugin.sender_id = uuid.uuid4()
    plugin.revoked_at = datetime.now(UTC) if plugin_revoked else None

    pairings = [
        MagicMock(spec=Pairing, sender_id=plugin.sender_id, user_id=uid, revoked_at=None)
        for uid in (paired_user_ids or [uuid.uuid4()])
    ]

    plugin_result = MagicMock()
    plugin_result.scalar_one_or_none.return_value = plugin if plugin_exists else None

    pairings_result = MagicMock()
    pairings_result.scalars.return_value.all.return_value = pairings

    db = AsyncMock()
    # First execute() returns the plugin lookup; second the
    # pairings list. Sequence matches push_live's two queries.
    db.execute.side_effect = [plugin_result, pairings_result]

    body = LivePushRequest(channel=channel, envelope=envelope)
    return plugin, pairings, test_hub, await push_live(
        plugin_row_id=plugin_row_id, body=body, db=db,
    )


async def _capture_topic(hub, topic: str) -> list[str]:
    """Hub helper — subscribe + read the buffered frames."""
    captured: list[str] = []
    sub = await hub.subscribe_ephemeral(topic)
    try:
        # No actual blocking — InProcessBroadcastHub's
        # subscribe_ephemeral returns a sub with the queue
        # populated by the prior publish_ephemeral calls;
        # drain non-blockingly.
        import asyncio
        while True:
            try:
                captured.append(
                    sub._queue.get_nowait()  # noqa: SLF001 — test only
                )
            except asyncio.QueueEmpty:
                break
    finally:
        await sub.unsubscribe()
    return captured


def _build_envelope() -> dict:
    """Minimal V2-shape envelope. The push handler treats it
    as opaque — only the framing-and-fanout layer matters
    for these tests."""
    return {
        "protocol_version": 2,
        "envelope_kind": "event",
        "sender_id": str(uuid.uuid4()),
        "user_id": str(uuid.uuid4()),
        "plugin_id": str(uuid.uuid4()),
        "payload_nonce": "AAAAAAAAAAAAAAAA",  # 16 chars b64 = 12 bytes
        "payload_ciphertext": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==",
        "recipient_envelopes": [],
        "envelope_signature": "x" * 88,
        "recipient_directory_version": 1,
    }


async def test_push_wraps_envelope_in_message_frame() -> None:
    """The fundamental triad 158 bug 1 contract: every fanned-
    out frame must be a `{type:"message", channel, id, payload}`
    object — NOT the raw envelope JSON.

    Hub semantics: subscribers receive only messages published
    AFTER they subscribe, so we pre-arm the subscription via a
    hand-built hub before invoking push_live."""
    from app.live import hub as hub_module
    from app.live.hub import InProcessBroadcastHub, plugin_topic
    from app.models import Pairing, Plugin
    from app.routers.live import LivePushRequest, push_live

    plugin_row_id = str(uuid.uuid4())
    channel = "ticker"
    envelope = _build_envelope()
    user_id = uuid.uuid4()

    test_hub = InProcessBroadcastHub()
    hub_module.set_hub(test_hub)
    sub = await test_hub.subscribe_ephemeral(
        plugin_topic(str(user_id), plugin_row_id)
    )

    plugin = MagicMock(spec=Plugin)
    plugin.id = uuid.UUID(plugin_row_id)
    plugin.sender_id = uuid.uuid4()
    plugin.revoked_at = None

    pairing = MagicMock(
        spec=Pairing,
        sender_id=plugin.sender_id,
        user_id=user_id,
        revoked_at=None,
    )

    plugin_result = MagicMock()
    plugin_result.scalar_one_or_none.return_value = plugin
    pairings_result = MagicMock()
    pairings_result.scalars.return_value.all.return_value = [pairing]
    db = AsyncMock()
    db.execute.side_effect = [plugin_result, pairings_result]

    body = LivePushRequest(channel=channel, envelope=envelope)
    await push_live(plugin_row_id=plugin_row_id, body=body, db=db)

    # Drain the subscription's pre-queued frame.
    import asyncio
    frame_json = await asyncio.wait_for(sub._queue.get(), timeout=1.0)
    await sub.unsubscribe()

    frame = json.loads(frame_json)
    assert frame["type"] == "message"
    assert frame["channel"] == channel
    assert isinstance(frame["id"], str) and len(frame["id"]) == 32
    inner = json.loads(
        base64.b64decode(frame["payload"]).decode("utf-8")
    )
    assert inner == envelope


async def test_push_validates_channel_name() -> None:
    """Channel name must match the same regex the WS frame
    parser uses, else 400 before any fan-out happens."""
    from fastapi import HTTPException
    from app.routers.live import LivePushRequest, push_live

    db = AsyncMock()
    body = LivePushRequest(channel="has spaces", envelope=_build_envelope())

    with pytest.raises(HTTPException) as exc:
        await push_live(plugin_row_id=str(uuid.uuid4()), body=body, db=db)
    assert exc.value.status_code == 400
    assert "channel" in str(exc.value.detail).lower()


async def test_push_rejects_oversized_envelope() -> None:
    """An envelope that would push the wrapped frame past
    MAX_FRAME_BYTES on the wire must 413."""
    from fastapi import HTTPException
    from app.routers.live import MAX_FRAME_BYTES, LivePushRequest, push_live
    from app.models import Plugin
    from app.live import hub as hub_module
    from app.live.hub import InProcessBroadcastHub

    hub_module.set_hub(InProcessBroadcastHub())

    plugin = MagicMock(spec=Plugin)
    plugin.id = uuid.uuid4()
    plugin.sender_id = uuid.uuid4()
    plugin.revoked_at = None

    plugin_result = MagicMock()
    plugin_result.scalar_one_or_none.return_value = plugin

    db = AsyncMock()
    db.execute.return_value = plugin_result

    # ~64 KB of single-character ciphertext fills the
    # envelope past the wrapped-frame ceiling.
    envelope = _build_envelope()
    envelope["payload_ciphertext"] = "A" * MAX_FRAME_BYTES

    body = LivePushRequest(channel="ticker", envelope=envelope)
    with pytest.raises(HTTPException) as exc:
        await push_live(plugin_row_id=str(uuid.uuid4()), body=body, db=db)
    assert exc.value.status_code == 413


async def test_push_404s_unknown_plugin() -> None:
    """Plugin lookup miss → 404, no fan-out."""
    from fastapi import HTTPException
    from app.live import hub as hub_module
    from app.live.hub import InProcessBroadcastHub

    hub_module.set_hub(InProcessBroadcastHub())

    plugin_result = MagicMock()
    plugin_result.scalar_one_or_none.return_value = None
    db = AsyncMock()
    db.execute.return_value = plugin_result

    from app.routers.live import LivePushRequest, push_live
    body = LivePushRequest(channel="ticker", envelope=_build_envelope())
    with pytest.raises(HTTPException) as exc:
        await push_live(plugin_row_id=str(uuid.uuid4()), body=body, db=db)
    assert exc.value.status_code == 404


async def test_push_fans_out_to_every_paired_user() -> None:
    """Triad 144 gemini privacy fix — push goes to user-scoped
    topics for every paired user, NOT to a single global topic.
    Pre-arm one subscription per user before invoking push."""
    import asyncio
    from app.live import hub as hub_module
    from app.live.hub import InProcessBroadcastHub, plugin_topic
    from app.models import Pairing, Plugin
    from app.routers.live import LivePushRequest, push_live

    plugin_row_id = str(uuid.uuid4())
    users = [uuid.uuid4(), uuid.uuid4(), uuid.uuid4()]

    test_hub = InProcessBroadcastHub()
    hub_module.set_hub(test_hub)
    subs = []
    for u in users:
        subs.append(
            await test_hub.subscribe_ephemeral(
                plugin_topic(str(u), plugin_row_id)
            )
        )

    plugin = MagicMock(spec=Plugin)
    plugin.id = uuid.UUID(plugin_row_id)
    plugin.sender_id = uuid.uuid4()
    plugin.revoked_at = None
    pairings = [
        MagicMock(spec=Pairing, sender_id=plugin.sender_id, user_id=u, revoked_at=None)
        for u in users
    ]
    plugin_result = MagicMock()
    plugin_result.scalar_one_or_none.return_value = plugin
    pairings_result = MagicMock()
    pairings_result.scalars.return_value.all.return_value = pairings
    db = AsyncMock()
    db.execute.side_effect = [plugin_result, pairings_result]

    body = LivePushRequest(channel="ticker", envelope=_build_envelope())
    await push_live(plugin_row_id=plugin_row_id, body=body, db=db)

    for sub, u in zip(subs, users):
        frame_json = await asyncio.wait_for(sub._queue.get(), timeout=1.0)
        await sub.unsubscribe()
        assert frame_json, f"empty frame for user {u}"
