import base64
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

pytest.importorskip("sqlalchemy", reason="device endpoint DB tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="device endpoint DB tests need async SQLite")

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.db import get_db
from app.main import app
from app.models import Base


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


def signup_payload(email: str, auth_key_hash: str | None = None) -> dict[str, object]:
    return {
        "email": email,
        "auth_key_hash": auth_key_hash or b64_bytes(32, 1),
        "encrypted_master_key": b64_bytes(48, 2),
        "auth_salt": b64_bytes(16, 3),
        "argon2_params_version": 1,
    }


@pytest.fixture
async def client(monkeypatch: pytest.MonkeyPatch) -> AsyncIterator[AsyncClient]:
    """Uses SQLite in memory; ORM models provide SQLite variants for Postgres UUID/JSONB fields."""
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
    transport = ASGITransport(app=app)

    async with AsyncClient(transport=transport, base_url="http://test") as test_client:
        yield test_client

    app.dependency_overrides.clear()
    await engine.dispose()


async def auth_header(client: AsyncClient, email: str = "device-user@example.com", fill: int = 1) -> dict[str, str]:
    payload = signup_payload(email=email, auth_key_hash=b64_bytes(32, fill))
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    return {"Authorization": f"Bearer {login.json()['session_token']}"}


@pytest.mark.asyncio
async def test_enroll_device(client: AsyncClient) -> None:
    headers = await auth_header(client)

    response = await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4), "fcm_token": "fcm-token"},
        headers=headers,
    )

    assert response.status_code == 201
    body = response.json()
    assert body["device_id"]
    assert body["created_at"]


@pytest.mark.asyncio
async def test_enroll_rejects_invalid_public_key(client: AsyncClient) -> None:
    headers = await auth_header(client)

    response = await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(31, 4)},
        headers=headers,
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_list_devices_hides_sensitive_fields(client: AsyncClient) -> None:
    headers = await auth_header(client)
    await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4), "fcm_token": "fcm-token"},
        headers=headers,
    )

    response = await client.get("/v1/auth/devices", headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert len(body) == 1
    assert body[0]["has_fcm_token"] is True
    assert "public_key" not in body[0]
    assert "fcm_token" not in body[0]


@pytest.mark.asyncio
async def test_revoke_device(client: AsyncClient) -> None:
    headers = await auth_header(client)
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4)},
        headers=headers,
    )
    device_id = enroll.json()["device_id"]

    revoke = await client.post(f"/v1/auth/devices/{device_id}/revoke", headers=headers)
    devices = await client.get("/v1/auth/devices", headers=headers)

    assert revoke.status_code == 204
    assert devices.json()[0]["revoked_at"] is not None


@pytest.mark.asyncio
async def test_revoke_other_users_device_returns_404(client: AsyncClient) -> None:
    first_headers = await auth_header(client, email="first@example.com", fill=1)
    second_headers = await auth_header(client, email="second@example.com", fill=2)
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4)},
        headers=first_headers,
    )

    response = await client.post(
        f"/v1/auth/devices/{enroll.json()['device_id']}/revoke",
        headers=second_headers,
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_device_routes_require_auth(client: AsyncClient) -> None:
    enroll = await client.post("/v1/auth/devices/enroll", json={"public_key": b64_bytes(32, 4)})
    listing = await client.get("/v1/auth/devices")
    revoke = await client.post("/v1/auth/devices/00000000-0000-0000-0000-000000000000/revoke")

    assert enroll.status_code == 401
    assert listing.status_code == 401
    assert revoke.status_code == 401
