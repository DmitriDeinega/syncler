"""V3 #14 step 3 — WS endpoint smoke tests.

Focused on the auth + frame-shape paths. Full multiplex /
heartbeat / rate-limit / revocation e2e coverage lands in
step 8.

Uses the SQLite-in-memory + FastAPI TestClient.websocket_connect
pattern. WebSocket tests in FastAPI need the synchronous
TestClient, NOT httpx AsyncClient.
"""

from __future__ import annotations

import base64
import json
import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

pytest.importorskip("sqlalchemy", reason="ws tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="ws tests need async SQLite")

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.db import get_db
from app.main import app
from app.models import Base
from app.routers.live import _reset_store_for_test


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
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("JWT_SECRET", "test-secret-with-at-least-32-bytes")

    engine = create_async_engine(
        "sqlite+aiosqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    import asyncio
    asyncio.run(_setup_schema(engine))

    async def override_get_db():
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    _reset_store_for_test()

    with TestClient(app) as tc:
        yield tc

    app.dependency_overrides.clear()
    _reset_store_for_test()


async def _setup_schema(engine) -> None:
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)


def test_ws_rejects_missing_subprotocol(client: TestClient) -> None:
    """No Sec-WebSocket-Protocol header → close 4400."""
    try:
        with client.websocket_connect("/v1/live/plugin/00000000-0000-0000-0000-000000000000"):
            pytest.fail("WS should have been rejected")
    except WebSocketDisconnect as e:
        assert e.code == 4400


def test_ws_rejects_unknown_bearer_token(client: TestClient) -> None:
    """Subprotocol present but token unknown → close 4400."""
    try:
        with client.websocket_connect(
            "/v1/live/plugin/00000000-0000-0000-0000-000000000000",
            subprotocols=["syncler.v1", "bearer.not-a-real-token"],
        ):
            pytest.fail("WS should have been rejected")
    except WebSocketDisconnect as e:
        assert e.code == 4400


def test_ws_rejects_malformed_plugin_row_id(client: TestClient) -> None:
    """Even with a valid bearer, a malformed plugin_row_id
    path segment closes 4400 before any pairing check."""
    # Mint a token via direct hub call (bypassing the auth
    # leg — the rejection should fire on the plugin_row UUID
    # parse, which happens before pairing lookup).
    from app.routers.live import ConnectToken, _TOKEN_STORE
    import uuid as _uuid
    token = "test-token-malformed-row"
    _TOKEN_STORE[token] = ConnectToken(
        user_id=_uuid.uuid4(),
        device_id=_uuid.uuid4(),
        expires_at_epoch_s=time.time() + 60,
    )
    try:
        with client.websocket_connect(
            "/v1/live/plugin/not-a-uuid",
            subprotocols=["syncler.v1", f"bearer.{token}"],
        ):
            pytest.fail("WS should have been rejected")
    except WebSocketDisconnect as e:
        assert e.code == 4400
