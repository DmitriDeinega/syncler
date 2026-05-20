import base64
from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient

pytest.importorskip("sqlalchemy", reason="auth endpoint DB tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="auth endpoint DB tests need async SQLite")

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.db import get_db
from app.main import app
from app.models import Base


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


def signup_payload(email: str = "user@example.com", auth_key_hash: str | None = None) -> dict[str, object]:
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
    monkeypatch.setenv("PRE_LOGIN_PEPPER", "test-pre-login-pepper")

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


@pytest.mark.asyncio
async def test_signup_creates_user(client: AsyncClient) -> None:
    response = await client.post("/v1/auth/signup", json=signup_payload())

    assert response.status_code == 201
    body = response.json()
    assert body["user_id"]
    assert body["created_at"]


@pytest.mark.asyncio
async def test_signup_duplicate_email_returns_409(client: AsyncClient) -> None:
    payload = signup_payload()

    first = await client.post("/v1/auth/signup", json=payload)
    second = await client.post("/v1/auth/signup", json=payload)

    assert first.status_code == 201
    assert second.status_code == 409


@pytest.mark.asyncio
async def test_signup_invalid_blob_returns_400(client: AsyncClient) -> None:
    payload = signup_payload()
    payload["auth_salt"] = b64_bytes(15, 1)

    response = await client.post("/v1/auth/signup", json=payload)

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_login_success_returns_token_and_encryption_blobs(client: AsyncClient) -> None:
    payload = signup_payload()
    await client.post("/v1/auth/signup", json=payload)

    response = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["user_id"]
    assert body["session_token"]
    assert body["encrypted_master_key"] == payload["encrypted_master_key"]
    assert body["auth_salt"] == payload["auth_salt"]
    assert body["argon2_params_version"] == 1


@pytest.mark.asyncio
async def test_pre_login_real_user_returns_stored_salt(client: AsyncClient) -> None:
    payload = signup_payload()
    await client.post("/v1/auth/signup", json=payload)

    response = await client.post("/v1/auth/pre-login", json={"email": payload["email"]})

    assert response.status_code == 200
    assert response.json() == {
        "auth_salt": payload["auth_salt"],
        "argon2_params_version": 1,
    }


@pytest.mark.asyncio
async def test_pre_login_unknown_user_returns_deterministic_fake_salt(client: AsyncClient) -> None:
    first = await client.post("/v1/auth/pre-login", json={"email": "missing@example.com"})
    second = await client.post("/v1/auth/pre-login", json={"email": "missing@example.com"})
    other = await client.post("/v1/auth/pre-login", json={"email": "other@example.com"})

    assert first.status_code == 200
    assert second.status_code == 200
    assert other.status_code == 200
    assert first.json()["auth_salt"] == second.json()["auth_salt"]
    assert first.json()["auth_salt"] != other.json()["auth_salt"]
    assert len(base64.b64decode(first.json()["auth_salt"])) == 16
    assert first.json()["argon2_params_version"] == 1


@pytest.mark.asyncio
async def test_pre_login_is_rate_limited_by_login_bucket(client: AsyncClient) -> None:
    payload = {"email": "rate-limited@example.com"}

    responses = [await client.post("/v1/auth/pre-login", json=payload) for _ in range(6)]

    assert [response.status_code for response in responses[:5]] == [200, 200, 200, 200, 200]
    assert responses[5].status_code == 429
    assert responses[5].json()["detail"] == "rate limited"


@pytest.mark.asyncio
async def test_login_wrong_hash_returns_generic_401(client: AsyncClient) -> None:
    payload = signup_payload()
    await client.post("/v1/auth/signup", json=payload)

    response = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": b64_bytes(32, 9)},
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid credentials"


@pytest.mark.asyncio
async def test_login_unknown_user_returns_generic_401(client: AsyncClient) -> None:
    response = await client.post(
        "/v1/auth/login",
        json={"email": "missing@example.com", "auth_key_hash": b64_bytes(32, 1)},
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "invalid credentials"


@pytest.mark.asyncio
async def test_account_delete_invalidates_existing_token(client: AsyncClient) -> None:
    payload = signup_payload()
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    token = login.json()["session_token"]

    delete_response = await client.delete("/v1/account", headers={"Authorization": f"Bearer {token}"})
    login_after_delete = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    list_after_delete = await client.get("/v1/auth/devices", headers={"Authorization": f"Bearer {token}"})

    assert delete_response.status_code == 204
    assert login_after_delete.status_code == 401
    assert list_after_delete.status_code == 401


@pytest.mark.asyncio
async def test_full_auth_flow(client: AsyncClient) -> None:
    payload = signup_payload(email="flow@example.com")

    signup = await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    token = login.json()["session_token"]
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4)},
        headers={"Authorization": f"Bearer {token}"},
    )
    devices = await client.get("/v1/auth/devices", headers={"Authorization": f"Bearer {token}"})

    assert signup.status_code == 201
    assert login.status_code == 200
    assert enroll.status_code == 201
    assert devices.status_code == 200
    assert len(devices.json()) == 1
