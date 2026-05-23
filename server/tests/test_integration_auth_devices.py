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
    bootstrap_headers = {"Authorization": f"Bearer {login.json()['session_token']}"}

    # Phase 0 separates bootstrap auth (login only) from device-bound auth
    # (everything sensitive). Enroll two devices so the test can revoke
    # one and still have a valid device-bound token to delete the account.
    enroll_a = await app_client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 4), "fcm_token": "test-fcm-token"},
        headers=bootstrap_headers,
    )
    assert enroll_a.status_code == 201
    device_a_id = enroll_a.json()["device_id"]
    headers_a = {"Authorization": f"Bearer {enroll_a.json()['session_token']}"}

    enroll_b = await app_client.post(
        "/v1/auth/devices/enroll",
        json={"public_key": b64_bytes(32, 5)},
        headers=bootstrap_headers,
    )
    assert enroll_b.status_code == 201
    headers_b = {"Authorization": f"Bearer {enroll_b.json()['session_token']}"}

    devices = await app_client.get("/v1/auth/devices", headers=headers_a)
    assert devices.status_code == 200
    assert any(device["id"] == device_a_id for device in devices.json())

    # Revoke device A using device B's token (so we still have a valid
    # device-bound token afterwards for the account-delete step).
    revoke = await app_client.post(f"/v1/auth/devices/{device_a_id}/revoke", headers=headers_b)
    assert revoke.status_code == 204

    # Device A's token is now rejected by sensitive routes.
    list_with_revoked = await app_client.get("/v1/auth/devices", headers=headers_a)
    assert list_with_revoked.status_code == 401

    delete_account = await app_client.delete("/v1/account", headers=headers_b)
    assert delete_account.status_code == 204

    login_after_delete = await app_client.post(
        "/v1/auth/login",
        json={"email": payload["email"], "auth_key_hash": payload["auth_key_hash"]},
    )
    assert login_after_delete.status_code == 401
