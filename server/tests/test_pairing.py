"""Tests for /v1/pairing/*."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import PendingPairing


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _signup_and_login(client: AsyncClient, email: str = "alice@example.com") -> str:
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
    return login.json()["session_token"]


async def _register_sender(client: AsyncClient) -> tuple[uuid.UUID, Ed25519PrivateKey]:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    response = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": "Lottery", "contact": "ops@lottery.app"},
    )
    return uuid.UUID(response.json()["sender_id"]), private_key


def _sign_initiate(private_key: Ed25519PrivateKey, *, sender_id: uuid.UUID, ttl: int, metadata: dict) -> str:
    body = {"sender_id": str(sender_id), "ttl_seconds": ttl, "metadata": metadata}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _b64(private_key.sign(canonical))


@pytest.mark.asyncio
async def test_pairing_initiate_and_complete_round_trip(app_client: AsyncClient) -> None:
    session_token = await _signup_and_login(app_client)
    sender_id, private_key = await _register_sender(app_client)

    initiate = await app_client.post(
        "/v1/pairing/initiate",
        json={
            "sender_id": str(sender_id),
            "ttl_seconds": 300,
            "metadata": {"display_name": "Lottery"},
            "signature": _sign_initiate(
                private_key, sender_id=sender_id, ttl=300, metadata={"display_name": "Lottery"}
            ),
        },
    )
    assert initiate.status_code == 201, initiate.text
    pairing_token = initiate.json()["pairing_token"]

    complete = await app_client.post(
        "/v1/pairing/complete",
        headers={"Authorization": f"Bearer {session_token}"},
        json={
            "pairing_token": pairing_token,
            "encrypted_initial_state": _b64(b"opaque-initial-state-blob"),
        },
    )
    assert complete.status_code == 201, complete.text
    body = complete.json()
    assert body["sender_id"] == str(sender_id)
    assert body["sender_name"] == "Lottery"
    # Fingerprint format: 6-4 groups, hyphen-separated, base32
    assert "-" in body["sender_public_key_fingerprint"]


@pytest.mark.asyncio
async def test_pairing_initiate_rejects_bad_signature(app_client: AsyncClient) -> None:
    sender_id, _ = await _register_sender(app_client)
    initiate = await app_client.post(
        "/v1/pairing/initiate",
        json={
            "sender_id": str(sender_id),
            "ttl_seconds": 300,
            "metadata": {},
            "signature": _b64(b"\xff" * 64),
        },
    )
    assert initiate.status_code == 401, initiate.text


@pytest.mark.asyncio
async def test_pairing_complete_rejects_expired_token(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    session_token = await _signup_and_login(app_client)
    sender_id, private_key = await _register_sender(app_client)

    # Insert an already-expired pending pairing
    expired = PendingPairing(
        id=uuid.uuid4(),
        sender_id=sender_id,
        pairing_token=b"E" * 32,
        expires_at=datetime.now(UTC) - timedelta(seconds=10),
    )
    db_session.add(expired)
    await db_session.commit()

    complete = await app_client.post(
        "/v1/pairing/complete",
        headers={"Authorization": f"Bearer {session_token}"},
        json={
            "pairing_token": _b64(b"E" * 32),
            "encrypted_initial_state": _b64(b"opaque-state"),
        },
    )
    assert complete.status_code == 410, complete.text


@pytest.mark.asyncio
async def test_pairing_revoke_and_list(app_client: AsyncClient) -> None:
    session_token = await _signup_and_login(app_client)
    sender_id, private_key = await _register_sender(app_client)

    initiate = await app_client.post(
        "/v1/pairing/initiate",
        json={
            "sender_id": str(sender_id),
            "ttl_seconds": 300,
            "metadata": {},
            "signature": _sign_initiate(private_key, sender_id=sender_id, ttl=300, metadata={}),
        },
    )
    complete = await app_client.post(
        "/v1/pairing/complete",
        headers={"Authorization": f"Bearer {session_token}"},
        json={
            "pairing_token": initiate.json()["pairing_token"],
            "encrypted_initial_state": _b64(b"opaque-initial-state"),
        },
    )
    pairing_id = complete.json()["pairing_id"]

    listing = await app_client.get(
        "/v1/pairing",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert listing.status_code == 200
    assert len(listing.json()) == 1

    revoke = await app_client.post(
        f"/v1/pairing/{pairing_id}/revoke",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert revoke.status_code == 204
