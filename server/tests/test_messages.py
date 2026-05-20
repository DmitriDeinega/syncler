"""Tests for /v1/messages/* and /v1/senders/*."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.nonce import reset_global_registry
from app.models import Pairing, Plugin


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _future(seconds: int = 3600) -> datetime:
    return datetime.now(UTC) + timedelta(seconds=seconds)


def _iso(dt: datetime) -> str:
    """ISO-8601 format matching the server's envelope canonicalization."""
    return dt.isoformat().replace("+00:00", "Z")


@pytest.fixture(autouse=True)
def _reset_nonce_registry():
    reset_global_registry()
    yield
    reset_global_registry()


async def _signup_and_login(client: AsyncClient, email: str = "alice@example.com") -> tuple[uuid.UUID, str]:
    auth_key_hash = _b64(b"a" * 32)
    encrypted_master_key = _b64(b"b" * 96)
    auth_salt = _b64(b"c" * 16)
    signup = await client.post(
        "/v1/auth/signup",
        json={
            "email": email,
            "auth_key_hash": auth_key_hash,
            "encrypted_master_key": encrypted_master_key,
            "auth_salt": auth_salt,
            "argon2_params_version": 1,
        },
    )
    assert signup.status_code == 201, signup.text
    user_id = uuid.UUID(signup.json()["user_id"])

    login = await client.post(
        "/v1/auth/login",
        json={"email": email, "auth_key_hash": auth_key_hash},
    )
    assert login.status_code == 200, login.text
    return user_id, login.json()["session_token"]


async def _enroll_device(client: AsyncClient, session_token: str, fcm_token: str = "fcm-token-stub") -> uuid.UUID:
    response = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {session_token}"},
        json={"public_key": _b64(b"d" * 32), "fcm_token": fcm_token},
    )
    assert response.status_code == 201, response.text
    return uuid.UUID(response.json()["device_id"])


async def _register_sender(client: AsyncClient) -> tuple[uuid.UUID, Ed25519PrivateKey]:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    response = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": "Trading Bot", "contact": "ops@example.com"},
    )
    assert response.status_code == 201, response.text
    return uuid.UUID(response.json()["sender_id"]), private_key


async def _seed_pairing_and_plugin(
    db_session: AsyncSession, *, user_id: uuid.UUID, sender_id: uuid.UUID
) -> uuid.UUID:
    pairing = Pairing(
        id=uuid.uuid4(),
        user_id=user_id,
        sender_id=sender_id,
        encrypted_state=b"opaque-pairing-state",
    )
    plugin = Plugin(
        id=uuid.uuid4(),
        sender_id=sender_id,
        version="1.0.0",
        manifest_hash=b"\x00" * 32,
        bundle_hash=b"\x00" * 32,
        signature=b"\x00" * 64,
        signed_bundle_url="https://example.com/plugin.js",
        capabilities=["network"],
        endpoints=["https://example.com/*"],
    )
    db_session.add_all([pairing, plugin])
    await db_session.commit()
    return plugin.id


def _build_envelope_signature(
    private_key: Ed25519PrivateKey,
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    encrypted_body_b64: str,
    nonce_b64: str,
    expires_at: datetime,
    min_plugin_version: str = "",
) -> str:
    envelope = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin_id),
        "encrypted_body": encrypted_body_b64,
        "nonce": nonce_b64,
        "min_plugin_version": min_plugin_version,
        "expires_at": _iso(expires_at),
    }
    envelope_bytes = json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _b64(private_key.sign(envelope_bytes))


async def _send_payload(
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    private_key: Ed25519PrivateKey,
    nonce: bytes | None = None,
    expires_at: datetime | None = None,
) -> dict:
    nonce_bytes = nonce or (uuid.uuid4().bytes[:12])
    expires = expires_at or _future()
    encrypted_body = _b64(b"ciphertext-and-tag-padding")
    nonce_b64 = _b64(nonce_bytes)
    signature = _build_envelope_signature(
        private_key,
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_b64=encrypted_body,
        nonce_b64=nonce_b64,
        expires_at=expires,
    )
    return {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin_id),
        "encrypted_body": encrypted_body,
        "nonce": nonce_b64,
        "envelope_signature": signature,
        "expires_at": _iso(expires),
    }


@pytest.mark.asyncio
async def test_send_message_round_trip(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 201, send.text
    message_id = send.json()["message_id"]

    inbox = await app_client.get(
        "/v1/messages/inbox",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert inbox.status_code == 200, inbox.text
    items = inbox.json()["messages"]
    assert len(items) == 1
    assert items[0]["id"] == message_id


@pytest.mark.asyncio
async def test_send_rejects_replayed_nonce(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    fixed_nonce = b"X" * 12
    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key, nonce=fixed_nonce
    )
    first = await app_client.post("/v1/messages/send", json=body)
    assert first.status_code == 201, first.text

    replay = await app_client.post("/v1/messages/send", json=body)
    assert replay.status_code == 409, replay.text


@pytest.mark.asyncio
async def test_send_rejects_plugin_from_other_sender(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_a, private_key_a = await _register_sender(app_client)
    sender_b, _ = await _register_sender(app_client)
    # Plugin belongs to sender_b, not sender_a.
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_b)
    # But pairing for sender_a too (to bypass that check)
    db_session.add(
        Pairing(
            id=uuid.uuid4(),
            user_id=user_id,
            sender_id=sender_a,
            encrypted_state=b"x",
        )
    )
    await db_session.commit()

    body = await _send_payload(
        sender_id=sender_a, user_id=user_id, plugin_id=plugin_id, private_key=private_key_a
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 410, send.text  # plugin not owned by sender


@pytest.mark.asyncio
async def test_send_rejects_past_expires_at(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        private_key=private_key,
        expires_at=datetime.now(UTC) - timedelta(seconds=10),
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 400, send.text


@pytest.mark.asyncio
async def test_send_returns_410_when_no_device_has_fcm_token(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    # Enroll device WITHOUT fcm_token
    response = await app_client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {session_token}"},
        json={"public_key": _b64(b"d" * 32)},
    )
    assert response.status_code == 201
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 410, send.text


@pytest.mark.asyncio
async def test_dismiss_rejects_foreign_device(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    device_id = await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    message_id = send.json()["message_id"]

    foreign_device = uuid.uuid4()
    dismiss = await app_client.post(
        f"/v1/messages/{message_id}/dismiss",
        params={"device_id": str(foreign_device)},
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert dismiss.status_code == 404

    # Own device works
    dismiss = await app_client.post(
        f"/v1/messages/{message_id}/dismiss",
        params={"device_id": str(device_id)},
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert dismiss.status_code == 204


@pytest.mark.asyncio
async def test_send_rejects_bad_signature(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, _ = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin_id),
        "encrypted_body": _b64(b"ciphertext-and-tag-padding"),
        "nonce": _b64(b"\x00" * 12),
        "envelope_signature": _b64(b"\xff" * 64),
        "expires_at": _iso(_future()),
    }
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 401, send.text
