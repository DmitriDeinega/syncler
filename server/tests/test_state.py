"""Tests for /v1/state CAS sync."""

from __future__ import annotations

import base64
import uuid

import pytest
from httpx import AsyncClient


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _signup_login_enroll(client: AsyncClient, email: str = "alice@example.com") -> str:
    """Return a device-bound session token usable on sensitive routes.

    Phase 0: /v1/state requires a JWT with a `did` claim. The bootstrap
    token from /v1/auth/login alone is rejected (401 device_required);
    the device-bound token from /v1/auth/devices/enroll is required.
    """
    auth_key_hash = _b64(b"a" * 32)
    await client.post(
        "/v1/auth/signup",
        json={
            "email": email,
            "auth_key_hash": auth_key_hash,
            "encrypted_master_key": _b64(b"b" * 96),
            "auth_salt": _b64(b"c" * 16),
            "argon2_params_version": 1,
        },
    )
    login = await client.post(
        "/v1/auth/login",
        json={"email": email, "auth_key_hash": auth_key_hash},
    )
    bootstrap = login.json()["session_token"]
    enroll = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap}"},
        json={"public_key": _b64(b"d" * 32), "encryption_public_key": _b64(b"e" * 32)},
    )
    return enroll.json()["session_token"]


# Legacy alias kept for diff readability; the tests below already use it.
_signup_and_login = _signup_login_enroll


@pytest.mark.asyncio
async def test_state_get_returns_empty_for_new_user(app_client: AsyncClient) -> None:
    session = await _signup_and_login(app_client)
    response = await app_client.get("/v1/state", headers={"Authorization": f"Bearer {session}"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["state_version"] == 0
    assert body["encrypted_blob"] == ""


@pytest.mark.asyncio
async def test_state_put_initial_then_update(app_client: AsyncClient) -> None:
    session = await _signup_and_login(app_client)

    put1 = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 0, "new_encrypted_blob": _b64(b"first-state")},
    )
    assert put1.status_code == 200, put1.text
    assert put1.json()["new_state_version"] == 1

    put2 = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 1, "new_encrypted_blob": _b64(b"second-state")},
    )
    assert put2.status_code == 200
    assert put2.json()["new_state_version"] == 2


@pytest.mark.asyncio
async def test_state_put_rejects_stale_version(app_client: AsyncClient) -> None:
    session = await _signup_and_login(app_client)

    await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 0, "new_encrypted_blob": _b64(b"first-state")},
    )

    stale = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 0, "new_encrypted_blob": _b64(b"would-overwrite")},
    )
    assert stale.status_code == 409, stale.text
    detail = stale.json()["detail"]
    assert detail["current_state_version"] == 1


@pytest.mark.asyncio
async def test_state_requires_auth(app_client: AsyncClient) -> None:
    response = await app_client.get("/v1/state")
    assert response.status_code == 401
