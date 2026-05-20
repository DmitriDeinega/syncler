import base64

import pytest
from httpx import AsyncClient


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


def signup_payload(email: str = "integration@example.com") -> dict[str, object]:
    return {
        "email": email,
        "auth_key_hash": b64_bytes(32, 1),
        "encrypted_master_key": b64_bytes(48, 2),
        "auth_salt": b64_bytes(16, 3),
        "argon2_params_version": 1,
    }


@pytest.mark.asyncio
async def test_auth_device_and_account_lifecycle(app_client: AsyncClient) -> None:
    payload = signup_payload()

    signup = await app_client.post("/v1/auth/signup", json=payload)
    assert signup.status_code == 201
    assert signup.json()["user_id"]

    login = await app_client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    assert login.status_code == 200
    session_token = login.json()["session_token"]
    auth_headers = {"Authorization": f"Bearer {session_token}"}

    enroll = await app_client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4), "fcm_token": "test-fcm-token"},
        headers=auth_headers,
    )
    assert enroll.status_code == 201
    device_id = enroll.json()["device_id"]

    devices = await app_client.get("/v1/auth/devices", headers=auth_headers)
    assert devices.status_code == 200
    assert any(device["id"] == device_id for device in devices.json())

    revoke = await app_client.post(f"/v1/auth/devices/{device_id}/revoke", headers=auth_headers)
    assert revoke.status_code == 204

    delete_account = await app_client.delete("/v1/account", headers=auth_headers)
    assert delete_account.status_code == 204

    login_after_delete = await app_client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    assert login_after_delete.status_code == 401
