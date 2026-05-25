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

from app.models import Pairing, Plugin


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _future(seconds: int = 3600) -> datetime:
    return datetime.now(UTC) + timedelta(seconds=seconds)


def _iso(dt: datetime) -> str:
    """ISO-8601 format matching the server's envelope canonicalization."""
    return dt.isoformat().replace("+00:00", "Z")


# Phase 7: the in-memory nonce registry was removed; replay detection
# now uses the durable `nonce_replay` table. The test DB is recreated
# per test session via conftest, and individual tests use distinct
# senders / nonces, so no autouse reset fixture is needed.


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


async def _enroll_device(client: AsyncClient, session_token: str, fcm_token: str = "fcm-token-stub") -> tuple[uuid.UUID, str]:
    """Enroll a device and return (device_id, device_bound_session_token).

    Phase 0 (device-bound JWT): callers must use the returned
    device-bound token for any subsequent call to sensitive routes
    (state, inbox, message detail, dismiss). The bootstrap token is
    rejected.
    """
    response = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {session_token}"},
        json={"public_key": _b64(b"d" * 32), "fcm_token": fcm_token},
    )
    assert response.status_code == 201, response.text
    body = response.json()
    return uuid.UUID(body["device_id"]), body["session_token"]


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
        plugin_identifier="com.test.plugin",
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
    user_id, bootstrap_token = await _signup_and_login(app_client)
    _, session_token = await _enroll_device(app_client, bootstrap_token)
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
    items = inbox.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == message_id


@pytest.mark.asyncio
async def test_send_returns_410_when_pairing_revoked(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Phase 13 (Codex 112): after a pairing is revoked (e.g. by
    root_compromise_rotation, or any direct revoke), the sender's
    next send returns 410 Gone — NOT 403. Cards already returned
    410; messages now matches. Sender SDK / docs expect the same
    status across both surfaces.
    """
    user_id, session_token = await _signup_and_login(app_client)
    await _enroll_device(app_client, session_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )
    # Revoke the pairing directly (simulates the post-rotation state).
    from sqlalchemy import update as sa_update
    from datetime import UTC
    await db_session.execute(
        sa_update(Pairing)
        .where(Pairing.user_id == user_id, Pairing.sender_id == sender_id)
        .values(revoked_at=datetime.now(UTC)),
    )
    await db_session.commit()

    body = await _send_payload(
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        private_key=private_key,
    )
    response = await app_client.post("/v1/messages/send", json=body)
    assert response.status_code == 410, response.text
    assert "no active pairing" in response.json()["detail"]


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
async def test_send_succeeds_when_device_has_no_fcm_token(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """A device without an FCM token (typical in debug builds without a real
    google-services.json) must still be able to receive messages via the
    /v1/messages/inbox pull endpoint. Storage is the source of truth; FCM is
    a best-effort delivery channel on top of it."""
    user_id, bootstrap_token = await _signup_and_login(app_client)
    # Enroll device WITHOUT fcm_token. Capture the device-bound session_token
    # from the enroll response — that's what sensitive routes require.
    response = await app_client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap_token}"},
        json={"public_key": _b64(b"d" * 32)},
    )
    assert response.status_code == 201
    session_token = response.json()["session_token"]
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 201, send.text
    message_id = send.json()["message_id"]

    # The message is pullable from the inbox endpoint.
    inbox = await app_client.get(
        "/v1/messages/inbox",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert inbox.status_code == 200, inbox.text
    ids = [m["id"] for m in inbox.json()["items"]]
    assert message_id in ids


@pytest.mark.asyncio
async def test_send_returns_410_when_user_has_no_devices(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """If the user has zero active devices, the message has no possible
    recipient and storage refuses (would just expire after 30d). Distinct from
    the FCM-less case — there, storage succeeds and inbox-pull works."""
    user_id, _ = await _signup_and_login(app_client)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    assert send.status_code == 410, send.text


@pytest.mark.asyncio
async def test_dismiss_uses_jwt_bound_device(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Phase 0: device identity comes from the JWT, not from a query
    parameter. The previous "foreign device" attack vector (caller
    spoofing `device_id` in the URL) is now structurally impossible —
    the auth context dependency rejects any token whose `did` claim
    doesn't match an active device the user owns.

    This test verifies the post-Phase-0 dismiss works end-to-end with
    a device-bound JWT, replacing the M5.1 foreign-device test (whose
    attack model no longer exists)."""
    user_id, bootstrap_token = await _signup_and_login(app_client)
    _, session_token = await _enroll_device(app_client, bootstrap_token)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_plugin(db_session, user_id=user_id, sender_id=sender_id)

    body = await _send_payload(
        sender_id=sender_id, user_id=user_id, plugin_id=plugin_id, private_key=private_key
    )
    send = await app_client.post("/v1/messages/send", json=body)
    message_id = send.json()["message_id"]

    dismiss = await app_client.post(
        f"/v1/messages/{message_id}/dismiss",
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
