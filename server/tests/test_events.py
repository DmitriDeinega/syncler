"""Tests for the Phase 2 SSE event bus + /v1/events endpoint."""

from __future__ import annotations

import asyncio
import base64
import json
import uuid
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

pytest.importorskip("sqlalchemy", reason="event tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="event tests need async SQLite")

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.db import get_db
from app.main import app
from app.models import Base
from app.services.events import (
    EventBus,
    _reset_for_tests,
    encode_sse,
    get_event_bus,
)


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


@pytest.fixture
async def client(monkeypatch: pytest.MonkeyPatch) -> AsyncIterator[AsyncClient]:
    """SQLite in-memory fixture (same shape as test_devices.py)."""
    monkeypatch.setenv("JWT_SECRET", "test-secret-with-at-least-32-bytes")
    _reset_for_tests()
    engine = create_async_engine(
        "sqlite+aiosqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)

    async def override_get_db() -> AsyncIterator:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as test_client:
        yield test_client
    app.dependency_overrides.clear()
    await engine.dispose()


# ---------- Pure event-bus unit tests ----------

@pytest.mark.asyncio
async def test_event_bus_publish_to_user_fans_out_to_all_devices() -> None:
    bus = EventBus()
    user = uuid.uuid4()
    dev_a = uuid.uuid4()
    dev_b = uuid.uuid4()
    sub_a = await bus.subscribe(user, dev_a)
    sub_b = await bus.subscribe(user, dev_b)

    delivered = await bus.publish_to_user(user, "inbox.changed", {"message_id": "m1"})

    assert delivered == 2
    event_a = await asyncio.wait_for(sub_a.queue.get(), timeout=0.5)
    event_b = await asyncio.wait_for(sub_b.queue.get(), timeout=0.5)
    assert event_a is not None and event_a.type == "inbox.changed"
    assert event_b is not None and event_b.type == "inbox.changed"
    # Per-event ids increase monotonically.
    assert int(event_b.id) >= int(event_a.id)


@pytest.mark.asyncio
async def test_event_bus_does_not_cross_users() -> None:
    bus = EventBus()
    user_a = uuid.uuid4()
    user_b = uuid.uuid4()
    sub_a = await bus.subscribe(user_a, uuid.uuid4())
    sub_b = await bus.subscribe(user_b, uuid.uuid4())

    await bus.publish_to_user(user_a, "inbox.changed", {"message_id": "m1"})

    event_a = await asyncio.wait_for(sub_a.queue.get(), timeout=0.5)
    assert event_a is not None
    # user_b sees nothing within the timeout window.
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(sub_b.queue.get(), timeout=0.1)


@pytest.mark.asyncio
async def test_event_bus_close_device_subscribers_sends_shutdown_sentinel() -> None:
    bus = EventBus()
    user = uuid.uuid4()
    dev = uuid.uuid4()
    sub = await bus.subscribe(user, dev)

    await bus.close_device_subscribers(dev)

    # The shutdown sentinel is None.
    item = await asyncio.wait_for(sub.queue.get(), timeout=0.5)
    assert item is None


@pytest.mark.asyncio
async def test_event_bus_unsubscribe_removes_from_user_and_device_indices() -> None:
    bus = EventBus()
    user = uuid.uuid4()
    dev = uuid.uuid4()
    sub = await bus.subscribe(user, dev)

    await bus.unsubscribe(sub)
    # No subscribers means publish reaches no one.
    delivered = await bus.publish_to_user(user, "inbox.changed", {})
    assert delivered == 0


# ---------- SSE wire encoding ----------

def test_encode_sse_emits_well_formed_lines() -> None:
    from app.services.events import Event
    event = Event(type="inbox.changed", data={"message_id": "m1"}, id="42")
    wire = encode_sse(event)
    text = wire.decode("utf-8")
    # SSE: each block has id, event, data lines + a blank line terminator.
    assert "id: 42" in text
    assert "event: inbox.changed" in text
    assert 'data: {"message_id":"m1"}' in text
    assert text.endswith("\n\n")


# ---------- Integration: SSE handshake + publishers ----------

async def _signup_login_enroll(client: AsyncClient, email: str, fill: int) -> str:
    """Return a device-bound JWT for a freshly-signed-up user."""
    payload = {
        "email": email,
        "auth_key_hash": b64_bytes(32, fill),
        "encrypted_master_key": b64_bytes(48, fill + 1),
        "auth_salt": b64_bytes(16, fill + 2),
        "argon2_params_version": 1,
    }
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": email, "auth_key_hash": payload["auth_key_hash"]},
    )
    bootstrap = login.json()["session_token"]
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap}"},
        json={"public_key": b64_bytes(32, fill + 3)},
    )
    return enroll.json()["session_token"]


@pytest.mark.asyncio
async def test_events_handshake_rejects_bootstrap_token(client: AsyncClient) -> None:
    """A bootstrap (user-only) JWT must not be allowed to open an
    event stream — the device-bound auth context is required."""
    payload = {
        "email": "bootstrap@example.com",
        "auth_key_hash": b64_bytes(32, 1),
        "encrypted_master_key": b64_bytes(48, 2),
        "auth_salt": b64_bytes(16, 3),
        "argon2_params_version": 1,
    }
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    bootstrap = login.json()["session_token"]

    response = await client.get(
        "/v1/events",
        headers={"Authorization": f"Bearer {bootstrap}"},
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "device_required"


@pytest.mark.asyncio
async def test_events_handshake_rejects_revoked_device(client: AsyncClient) -> None:
    """A revoked device's still-valid JWT must not be allowed to open
    an event stream."""
    session_token = await _signup_login_enroll(client, "revoked@example.com", fill=1)
    # Find the device id and revoke it using its own token (still active
    # at the moment of the call).
    devices = await client.get(
        "/v1/auth/devices",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    device_id = devices.json()[0]["id"]
    await client.post(
        f"/v1/auth/devices/{device_id}/revoke",
        headers={"Authorization": f"Bearer {session_token}"},
    )

    response = await client.get(
        "/v1/events",
        headers={"Authorization": f"Bearer {session_token}"},
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "device_revoked"


@pytest.mark.asyncio
async def test_state_put_publishes_state_changed_event(client: AsyncClient) -> None:
    """Verify the PUT /v1/state hook fires an SSE event. We subscribe
    via the event bus directly (rather than streaming the SSE endpoint)
    so the test is fast + deterministic."""
    session_token = await _signup_login_enroll(client, "state-events@example.com", fill=2)
    # Pull the device_id from listDevices so we can subscribe to the bus
    # using the same (user_id, device_id) the auth context resolves to.
    devices = await client.get(
        "/v1/auth/devices",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    device_uuid = uuid.UUID(devices.json()[0]["id"])
    # We need the user_id; sub via api list_users isn't exposed, but the
    # bus key is user_id which we can derive by decoding the JWT subject.
    import jwt
    user_uuid = uuid.UUID(jwt.decode(session_token, options={"verify_signature": False})["sub"])

    sub = await get_event_bus().subscribe(user_uuid, device_uuid)
    try:
        put_response = await client.put(
            "/v1/state",
            headers={"Authorization": f"Bearer {session_token}"},
            json={
                "expected_state_version": 0,
                "new_encrypted_blob": _b64(b"opaque-state-blob"),
            },
        )
        assert put_response.status_code == 200

        event = await asyncio.wait_for(sub.queue.get(), timeout=1.0)
        assert event is not None
        assert event.type == "state.changed"
        assert event.data["version"] == 1
    finally:
        await get_event_bus().unsubscribe(sub)


@pytest.mark.asyncio
async def test_revoke_device_closes_event_stream(client: AsyncClient) -> None:
    """When a device is revoked, any open SSE stream for that device
    should receive the shutdown sentinel from the event bus."""
    session_token = await _signup_login_enroll(client, "revoke-disconnect@example.com", fill=3)
    devices = await client.get(
        "/v1/auth/devices",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    device_uuid = uuid.UUID(devices.json()[0]["id"])
    import jwt
    user_uuid = uuid.UUID(jwt.decode(session_token, options={"verify_signature": False})["sub"])

    # Need a second device to revoke the first (a revoked device can't
    # revoke itself — current_auth_context would reject mid-revoke).
    bootstrap_login = await client.post(
        "/v1/auth/login",
        json={
            "email": "revoke-disconnect@example.com",
            "auth_key_hash": b64_bytes(32, 3),
        },
    )
    bootstrap_token = bootstrap_login.json()["session_token"]
    enroll_b = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap_token}"},
        json={"public_key": b64_bytes(32, 100)},
    )
    second_token = enroll_b.json()["session_token"]

    sub = await get_event_bus().subscribe(user_uuid, device_uuid)
    try:
        revoke = await client.post(
            f"/v1/auth/devices/{device_uuid}/revoke",
            headers={"Authorization": f"Bearer {second_token}"},
        )
        assert revoke.status_code == 204

        # The first device's stream receives the shutdown sentinel.
        item = await asyncio.wait_for(sub.queue.get(), timeout=1.0)
        assert item is None
    finally:
        await get_event_bus().unsubscribe(sub)
