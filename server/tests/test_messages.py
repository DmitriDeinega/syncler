"""Tests for /v1/messages/* and /v1/senders/*."""

from __future__ import annotations

import base64
import json
import uuid
from datetime import UTC, datetime

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Pairing, Plugin


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _signup_and_login(client: AsyncClient) -> tuple[uuid.UUID, str]:
    auth_key_hash = _b64(b"a" * 32)
    encrypted_master_key = _b64(b"b" * 96)
    auth_salt = _b64(b"c" * 16)
    signup = await client.post(
        "/v1/auth/signup",
        json={
            "email": "alice@example.com",
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
        json={"email": "alice@example.com", "auth_key_hash": auth_key_hash},
    )
    assert login.status_code == 200, login.text
    return user_id, login.json()["session_token"]


async def _enroll_device(client: AsyncClient, session_token: str) -> uuid.UUID:
    response = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {session_token}"},
        json={"public_key": _b64(b"d" * 32), "fcm_token": "fcm-token-stub"},
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
    min_plugin_version: str = "",
) -> str:
    envelope = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin_id),
        "encrypted_body": encrypted_body_b64,
        "nonce": nonce_b64,
        "min_plugin_version": min_plugin_version,
    }
    envelope_bytes = json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _b64(private_key.sign(envelope_bytes))


@pytest.mark.asyncio
async def test_send_message_round_trip(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(
        db_session, user_id=user_id, sender_id=sender_id
    )

    encrypted_body = _b64(b"ciphertext-and-tag-padding")
    nonce = _b64(b"\x00" * 12)
    signature = _build_envelope_signature(
        private_key,
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_b64=encrypted_body,
        nonce_b64=nonce,
    )

    send = await app_client.post(
        "/v1/messages/send",
        headers={"X-Sender-ID": str(sender_id)},
        json={
            "sender_id": str(sender_id),
            "user_id": str(user_id),
            "plugin_id": str(plugin_id),
            "encrypted_body": encrypted_body,
            "nonce": nonce,
            "envelope_signature": signature,
        },
    )
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
    assert items[0]["encrypted_body"] == encrypted_body
    assert items[0]["nonce"] == nonce

    fetch = await app_client.get(
        f"/v1/messages/{message_id}",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert fetch.status_code == 200
    assert fetch.json()["encrypted_body"] == encrypted_body


@pytest.mark.asyncio
async def test_send_rejects_bad_signature(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(
        db_session, user_id=user_id, sender_id=sender_id
    )

    encrypted_body = _b64(b"ciphertext-and-tag-padding")
    nonce = _b64(b"\x00" * 12)
    bogus_sig = _b64(b"\xff" * 64)

    send = await app_client.post(
        "/v1/messages/send",
        headers={"X-Sender-ID": str(sender_id)},
        json={
            "sender_id": str(sender_id),
            "user_id": str(user_id),
            "plugin_id": str(plugin_id),
            "encrypted_body": encrypted_body,
            "nonce": nonce,
            "envelope_signature": bogus_sig,
        },
    )
    assert send.status_code == 401, send.text


@pytest.mark.asyncio
async def test_send_returns_410_when_no_active_device(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, _ = await _signup_and_login(app_client)
    # Note: no device enrolled
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(
        db_session, user_id=user_id, sender_id=sender_id
    )

    encrypted_body = _b64(b"ciphertext-and-tag-padding")
    nonce = _b64(b"\x00" * 12)
    signature = _build_envelope_signature(
        private_key,
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_b64=encrypted_body,
        nonce_b64=nonce,
    )

    send = await app_client.post(
        "/v1/messages/send",
        headers={"X-Sender-ID": str(sender_id)},
        json={
            "sender_id": str(sender_id),
            "user_id": str(user_id),
            "plugin_id": str(plugin_id),
            "encrypted_body": encrypted_body,
            "nonce": nonce,
            "envelope_signature": signature,
        },
    )
    assert send.status_code == 410, send.text


@pytest.mark.asyncio
async def test_dismiss_marks_delivery_status(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    user_id, session_token = await _signup_and_login(app_client)
    device_id = await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(
        db_session, user_id=user_id, sender_id=sender_id
    )

    encrypted_body = _b64(b"ciphertext-and-tag-padding")
    nonce = _b64(b"\x00" * 12)
    signature = _build_envelope_signature(
        private_key,
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        encrypted_body_b64=encrypted_body,
        nonce_b64=nonce,
    )

    send = await app_client.post(
        "/v1/messages/send",
        headers={"X-Sender-ID": str(sender_id)},
        json={
            "sender_id": str(sender_id),
            "user_id": str(user_id),
            "plugin_id": str(plugin_id),
            "encrypted_body": encrypted_body,
            "nonce": nonce,
            "envelope_signature": signature,
        },
    )
    assert send.status_code == 201
    message_id = send.json()["message_id"]

    dismiss = await app_client.post(
        f"/v1/messages/{message_id}/dismiss",
        params={"device_id": str(device_id)},
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert dismiss.status_code == 204
