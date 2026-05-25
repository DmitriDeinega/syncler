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


async def signup_and_login(client: AsyncClient, email: str = "device-user@example.com", fill: int = 1) -> str:
    """Return the bootstrap (user-only) JWT for a freshly-signed-up user.
    Use this when the test needs to call /v1/auth/devices/enroll itself
    (Phase 0 fix-up: enroll requires a bootstrap token, not a device-bound
    one, so a revoked device cannot regain access via re-enrollment)."""
    payload = signup_payload(email=email, auth_key_hash=b64_bytes(32, fill))
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    return login.json()["session_token"]


async def bootstrap_header(client: AsyncClient, email: str = "device-user@example.com", fill: int = 1) -> dict[str, str]:
    """Header carrying the bootstrap JWT (no `did` claim).

    Suitable for /v1/auth/devices/enroll only. Sensitive routes reject
    this token with 401 device_required.
    """
    token = await signup_and_login(client, email=email, fill=fill)
    return {"Authorization": f"Bearer {token}"}


async def auth_header(client: AsyncClient, email: str = "device-user@example.com", fill: int = 1) -> dict[str, str]:
    """Header carrying a device-bound JWT (the `did`-claim variant).

    Sets up a user, signs in, enrolls one device, returns the device-bound
    session_token from the enroll response. Use this for tests that call
    sensitive routes (state, inbox, list_devices, revoke, etc.).

    Tests that need to enroll additional devices on the same user MUST
    use `bootstrap_header` for those enroll calls, because enroll rejects
    device-bound tokens (Codex consultation 51 RED #1).
    """
    payload = signup_payload(email=email, auth_key_hash=b64_bytes(32, fill))
    await client.post("/v1/auth/signup", json=payload)
    login = await client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    bootstrap_headers = {"Authorization": f"Bearer {login.json()['session_token']}"}
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, fill + 10),
            "encryption_public_key": b64_bytes(32, fill + 110),
        },
        headers=bootstrap_headers,
    )
    # Return BOTH the device-bound header and the bootstrap header so
    # tests can choose, but the canonical return is device-bound.
    return {"Authorization": f"Bearer {enroll.json()['session_token']}"}


async def bootstrap_only_header(client: AsyncClient, email: str = "bootstrap-only@example.com", fill: int = 99) -> dict[str, str]:
    """Return a header for the bootstrap token from a user that has NOT
    enrolled any device. Used by tests that need to verify sensitive routes
    reject pre-device tokens."""
    return await bootstrap_header(client, email=email, fill=fill)


@pytest.mark.asyncio
async def test_enroll_device(client: AsyncClient) -> None:
    headers = await bootstrap_header(client)

    response = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 4),
            "encryption_public_key": b64_bytes(32, 104),
            "fcm_token": "fcm-token",
        },
        headers=headers,
    )

    assert response.status_code == 201
    body = response.json()
    assert body["device_id"]
    assert body["created_at"]
    # Phase 0: enroll returns a device-bound JWT alongside the device_id.
    assert body["session_token"]


@pytest.mark.asyncio
async def test_enroll_rejects_invalid_public_key(client: AsyncClient) -> None:
    headers = await bootstrap_header(client)

    response = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(31, 4),
            "encryption_public_key": b64_bytes(32, 104),
        },
        headers=headers,
    )

    assert response.status_code == 400


@pytest.mark.asyncio
async def test_enroll_rejects_device_bound_token(client: AsyncClient) -> None:
    """Codex consultation 51 RED #1: a revoked device's still-valid
    device-bound JWT must NOT be able to enroll a new device and
    regain access. The enroll endpoint accepts bootstrap tokens only."""
    device_bound_headers = await auth_header(client)

    response = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 9),
            "encryption_public_key": b64_bytes(32, 109),
        },
        headers=device_bound_headers,
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "bootstrap_required"


@pytest.mark.asyncio
async def test_list_devices_hides_sensitive_fields(client: AsyncClient) -> None:
    """Enroll two devices (one with fcm_token, one without) and verify
    list_devices flags has_fcm_token correctly and never leaks public_key
    or fcm_token. Bootstrap token is needed for both enroll calls because
    enroll rejects device-bound tokens."""
    bootstrap = await signup_and_login(client)
    bootstrap_headers = {"Authorization": f"Bearer {bootstrap}"}

    first = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 11),
            "encryption_public_key": b64_bytes(32, 111),
        },
        headers=bootstrap_headers,
    )
    assert first.status_code == 201
    device_headers = {"Authorization": f"Bearer {first.json()['session_token']}"}

    await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 4),
            "encryption_public_key": b64_bytes(32, 104),
            "fcm_token": "fcm-token",
        },
        headers=bootstrap_headers,
    )

    response = await client.get("/v1/auth/devices", headers=device_headers)

    assert response.status_code == 200
    body = response.json()
    assert len(body) == 2
    for row in body:
        assert "public_key" not in row
        assert "fcm_token" not in row
    fcm_row = next(row for row in body if row["has_fcm_token"])
    assert fcm_row["has_fcm_token"] is True
    no_fcm_row = next(row for row in body if not row["has_fcm_token"])
    assert no_fcm_row["has_fcm_token"] is False


@pytest.mark.asyncio
async def test_revoke_device(client: AsyncClient) -> None:
    """Enroll two devices; revoke the second using the first's token;
    verify the second is marked revoked and the first is still authorized."""
    bootstrap = await signup_and_login(client)
    bootstrap_headers = {"Authorization": f"Bearer {bootstrap}"}

    first = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 11),
            "encryption_public_key": b64_bytes(32, 111),
        },
        headers=bootstrap_headers,
    )
    device_headers = {"Authorization": f"Bearer {first.json()['session_token']}"}

    second = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 4),
            "encryption_public_key": b64_bytes(32, 104),
        },
        headers=bootstrap_headers,
    )
    target_device_id = second.json()["device_id"]

    revoke = await client.post(f"/v1/auth/devices/{target_device_id}/revoke", headers=device_headers)
    devices = await client.get("/v1/auth/devices", headers=device_headers)

    assert revoke.status_code == 204
    target_row = next(row for row in devices.json() if row["id"] == target_device_id)
    assert target_row["revoked_at"] is not None


@pytest.mark.asyncio
async def test_state_endpoint_rejects_bootstrap_only_token(client: AsyncClient) -> None:
    """Pre-Phase-0 tokens (no `did` claim) must be rejected on sensitive
    routes with a `device_required` hint so the client knows to re-enroll."""
    headers = await bootstrap_only_header(client)

    response = await client.get("/v1/state", headers=headers)

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "device_required"


@pytest.mark.asyncio
async def test_state_endpoint_rejects_revoked_device(client: AsyncClient) -> None:
    """A device whose `revoked_at` is set must not be able to reach
    sensitive routes — even with its still-valid JWT."""
    headers = await auth_header(client)

    # Find the device that auth_header enrolled and revoke it. After
    # revocation, the device's own token should be rejected by the same
    # auth context dependency.
    listing = await client.get("/v1/auth/devices", headers=headers)
    device_id = listing.json()[0]["id"]
    await client.post(f"/v1/auth/devices/{device_id}/revoke", headers=headers)

    response = await client.get("/v1/state", headers=headers)

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "device_revoked"


@pytest.mark.asyncio
async def test_revoke_other_users_device_returns_404(client: AsyncClient) -> None:
    """Verify cross-user isolation: user B cannot revoke user A's device.
    Uses each user's own device-bound JWT for the actual call (which is
    what current_auth_context requires), and the API does NOT leak the
    existence of foreign-user devices."""
    # User A has at least one device (set up by auth_header).
    user_a_headers = await auth_header(client, email="first@example.com", fill=1)
    user_a_devices = await client.get("/v1/auth/devices", headers=user_a_headers)
    user_a_device_id = user_a_devices.json()[0]["id"]

    # User B has their own device-bound token.
    user_b_headers = await auth_header(client, email="second@example.com", fill=2)

    response = await client.post(
        f"/v1/auth/devices/{user_a_device_id}/revoke",
        headers=user_b_headers,
    )

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_device_routes_require_auth(client: AsyncClient) -> None:
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        json={
            "public_key": b64_bytes(32, 4),
            "encryption_public_key": b64_bytes(32, 104),
        },
    )
    listing = await client.get("/v1/auth/devices")
    revoke = await client.post("/v1/auth/devices/00000000-0000-0000-0000-000000000000/revoke")

    assert enroll.status_code == 401
    assert listing.status_code == 401
    assert revoke.status_code == 401
