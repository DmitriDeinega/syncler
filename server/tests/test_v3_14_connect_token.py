"""V3 #14 step 2 — /v1/live/connect-token endpoint tests.

Mints a short-lived (60s) opaque token bound to (user_id,
device_id) from the requester's device JWT. The token is the
auth surface for the WS upgrade — keeps the long-lived JWT
out of the Sec-WebSocket-Protocol header (codex 141 #1).

Uses the SQLite-in-memory client pattern from test_devices.py.
"""

from __future__ import annotations

import base64
import time
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

pytest.importorskip("sqlalchemy", reason="connect-token tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="connect-token tests need async SQLite")

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.db import get_db
from app.main import app
from app.models import Base
from app.routers.live import (
    CONNECT_TOKEN_TTL_SECONDS,
    _reset_store_for_test,
    redeem_connect_token,
)


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


def signup_payload(email: str) -> dict[str, object]:
    return {
        "email": email,
        "auth_key_hash": b64_bytes(32, 1),
        "encrypted_master_key": b64_bytes(48, 2),
        "auth_salt": b64_bytes(16, 3),
        "argon2_params_version": 1,
    }


@pytest.fixture
async def client(monkeypatch: pytest.MonkeyPatch) -> AsyncIterator[AsyncClient]:
    monkeypatch.setenv("JWT_SECRET", "test-secret-with-at-least-32-bytes")

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
    _reset_store_for_test()

    transport = ASGITransport(app=app)
    async with AsyncClient(
        transport=transport, base_url="http://testserver"
    ) as ac:
        yield ac

    app.dependency_overrides.clear()
    await engine.dispose()
    _reset_store_for_test()


async def _signup_and_enroll(
    client: AsyncClient, email: str
) -> tuple[str, str, str]:
    """Run signup → login → device enrollment; return (bootstrap_token,
    device_token, device_id)."""
    signup_resp = await client.post(
        "/v1/auth/signup", json=signup_payload(email)
    )
    assert signup_resp.status_code == 201, signup_resp.text
    login_resp = await client.post(
        "/v1/auth/login",
        json={
            "email": email,
            "auth_key_hash": b64_bytes(32, 1),
        },
    )
    assert login_resp.status_code == 200, login_resp.text
    bootstrap_token = login_resp.json()["access_token"]

    enroll_resp = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap_token}"},
        json={
            "label": "test-device",
            "encryption_public_key": b64_bytes(32, 7),
        },
    )
    assert enroll_resp.status_code == 201, enroll_resp.text
    enroll_json = enroll_resp.json()
    return (
        bootstrap_token,
        enroll_json["access_token"],
        enroll_json["device_id"],
    )


@pytest.mark.asyncio
async def test_connect_token_requires_authorization(
    client: AsyncClient,
) -> None:
    resp = await client.post("/v1/live/connect-token")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_connect_token_rejects_bootstrap_token(
    client: AsyncClient,
) -> None:
    """A bootstrap token (no `did` claim) must NOT mint a
    connect token — only device-bound JWTs reach the WS."""
    _, _, _ = await _signup_and_enroll(client, "a@example.com")
    # Re-login to get a fresh bootstrap token without enrolling.
    login_resp = await client.post(
        "/v1/auth/login",
        json={
            "email": "a@example.com",
            "auth_key_hash": b64_bytes(32, 1),
        },
    )
    bootstrap_token = login_resp.json()["access_token"]

    resp = await client.post(
        "/v1/live/connect-token",
        headers={"Authorization": f"Bearer {bootstrap_token}"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_connect_token_mints_and_returns_metadata(
    client: AsyncClient,
) -> None:
    _, device_token, device_id = await _signup_and_enroll(
        client, "b@example.com"
    )
    resp = await client.post(
        "/v1/live/connect-token",
        headers={"Authorization": f"Bearer {device_token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "token" in body
    assert body["ttl_seconds"] == 60
    assert isinstance(body["expires_at_epoch_ms"], int)
    # Token is URL-safe base64 of ~32 bytes → 43 chars.
    assert 40 <= len(body["token"]) <= 60


@pytest.mark.asyncio
async def test_connect_token_is_single_use(client: AsyncClient) -> None:
    _, device_token, _ = await _signup_and_enroll(client, "c@example.com")
    resp = await client.post(
        "/v1/live/connect-token",
        headers={"Authorization": f"Bearer {device_token}"},
    )
    token_str = resp.json()["token"]

    # First redeem returns the binding.
    first = redeem_connect_token(token_str)
    assert first is not None

    # Second redeem returns None — token consumed.
    second = redeem_connect_token(token_str)
    assert second is None


@pytest.mark.asyncio
async def test_connect_token_expires_after_ttl(client: AsyncClient) -> None:
    """V0.1 in-process store stamps expires_at as
    now + TTL_SECONDS. We can't time-travel without mocking
    `time.time`; verify the stamped value is at least TTL
    seconds in the future, then redeem-success on a known-fresh
    token."""
    _, device_token, _ = await _signup_and_enroll(client, "d@example.com")
    before = time.time()
    resp = await client.post(
        "/v1/live/connect-token",
        headers={"Authorization": f"Bearer {device_token}"},
    )
    body = resp.json()
    expires_at_s = body["expires_at_epoch_ms"] / 1000
    assert expires_at_s >= before + CONNECT_TOKEN_TTL_SECONDS - 1.0
    # Fresh token still redeems.
    assert redeem_connect_token(body["token"]) is not None


@pytest.mark.asyncio
async def test_redeem_unknown_token_returns_none(
    client: AsyncClient,
) -> None:
    # Endpoint not strictly required for this test, but the
    # client fixture also resets the store between runs.
    assert redeem_connect_token("not-a-real-token") is None


@pytest.mark.asyncio
async def test_connect_token_binds_to_device(client: AsyncClient) -> None:
    """The token's stored binding matches the enrolling
    device — the WS handshake must verify (user_id, device_id)
    against the redeem result, not against frame-body claims."""
    _, device_token, device_id = await _signup_and_enroll(
        client, "e@example.com"
    )
    resp = await client.post(
        "/v1/live/connect-token",
        headers={"Authorization": f"Bearer {device_token}"},
    )
    body = resp.json()
    binding = redeem_connect_token(body["token"])
    assert binding is not None
    assert str(binding.device_id) == device_id
