"""Phase 3 — template renderer + live cards + dismiss filter.

Coverage list comes directly from the consultation 63 triad sign-off:
  - Phase 3a: template publish golden + malformed-manifest rejections.
  - Phase 3b: cards upsert security gates (revoked plugin, missing
    pairing, TTL cap, sequence regression), delete cross-user.
  - Phase 2 carry-over: inbox dismiss filter excludes dismissed rows.

All tests use the shared ``app_client`` + ``db_session`` fixtures from
``conftest.py``. The earlier ``test_phase3_template.py`` was removed
because it used a non-existent ``client`` fixture AND signed a
canonical envelope that didn't match the server's conditional
inclusion of ``card_type``.
"""

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
from app.models import LiveCard, Pairing, Plugin


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _canonical(fields: dict) -> bytes:
    return json.dumps(fields, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _future(hours: float = 1.0) -> datetime:
    return datetime.now(UTC) + timedelta(hours=hours)


def _iso(dt: datetime) -> str:
    return dt.isoformat().replace("+00:00", "Z")


@pytest.fixture(autouse=True)
def _reset_nonce_registry():
    reset_global_registry()
    yield
    reset_global_registry()


# ---------- shared bootstrap helpers ------------------------------------


async def _signup_login(client: AsyncClient, *, email: str) -> tuple[uuid.UUID, str]:
    body = {
        "email": email,
        "auth_key_hash": _b64(b"a" * 32),
        "encrypted_master_key": _b64(b"b" * 96),
        "auth_salt": _b64(b"c" * 16),
        "argon2_params_version": 1,
    }
    signup = await client.post("/v1/auth/signup", json=body)
    assert signup.status_code == 201, signup.text
    user_id = uuid.UUID(signup.json()["user_id"])

    login = await client.post(
        "/v1/auth/login",
        json={"email": email, "auth_key_hash": body["auth_key_hash"]},
    )
    assert login.status_code == 200, login.text
    return user_id, login.json()["session_token"]


async def _enroll_device(client: AsyncClient, token: str) -> tuple[uuid.UUID, str]:
    response = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {token}"},
        json={"public_key": _b64(b"d" * 32)},
    )
    assert response.status_code == 201, response.text
    body = response.json()
    return uuid.UUID(body["device_id"]), body["session_token"]


async def _register_sender(client: AsyncClient) -> tuple[uuid.UUID, Ed25519PrivateKey]:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    response = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": "Lottery", "contact": "ops@lottery.app"},
    )
    assert response.status_code == 201, response.text
    return uuid.UUID(response.json()["sender_id"]), private_key


async def _seed_pairing_and_live_plugin(
    db_session: AsyncSession,
    *,
    user_id: uuid.UUID,
    sender_id: uuid.UUID,
    card_key_path: str = "$.card_key",
    revoked: bool = False,
) -> uuid.UUID:
    """Seed an active pairing + a Phase 3b live-card plugin row."""
    db_session.add(
        Pairing(
            id=uuid.uuid4(),
            user_id=user_id,
            sender_id=sender_id,
            encrypted_state=b"opaque",
        )
    )
    plugin = Plugin(
        id=uuid.uuid4(),
        sender_id=sender_id,
        plugin_identifier="com.lottery.live",
        version="1.0.0",
        manifest_hash=b"\x00" * 32,
        bundle_hash=b"\x00" * 32,
        signature=b"\x00" * 64,
        signed_bundle_url="https://lottery.app/plugin.js",
        capabilities=["network"],
        endpoints=["https://lottery.app/api/*"],
        renderer="script",
        card_type="live",
        card_key_path=card_key_path,
        revoked_at=datetime.now(UTC) if revoked else None,
    )
    db_session.add(plugin)
    await db_session.commit()
    return plugin.id


# ---------- Phase 3a: template publish ----------------------------------


def _publish_template_body(
    sender_id: uuid.UUID,
    private_key: Ed25519PrivateKey,
    *,
    template: dict,
    endpoints: list[str],
    version: str = "1.0.0",
) -> dict:
    """Build a publish body and matching sender_signature for a template
    plugin. Mirrors the server's conditional canonicalization (template
    block included, card_type omitted when 'event')."""
    envelope = {
        "sender_id": str(sender_id),
        "plugin_identifier": "com.lottery.template",
        "version": version,
        "manifest_hash": _b64(b"M" * 32),
        "bundle_hash": _b64(b"B" * 32),
        "signature": _b64(b"S" * 64),
        "signed_bundle_url": "https://lottery.app/plugin.js",
        "capabilities": ["network"],
        "endpoints": endpoints,
        "renderer": "template",
        "template": template,
    }
    sig = _b64(private_key.sign(_canonical(envelope)))
    return {**envelope, "sender_signature": sig}


@pytest.mark.asyncio
async def test_publish_template_plugin_success(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    body = _publish_template_body(
        sender_id,
        private_key,
        template={
            "layout": "standard_card",
            "fields": {
                "title": {"path": "$.title"},
                "subtitle": {"path": "$.data.sub"},
                "body": {"path": "$.body"},
            },
            "actions": [
                {"id": "ack", "label": "Acknowledge", "endpoint": "https://lottery.app/api/ack"},
            ],
        },
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 201, response.text

    # /latest must round-trip the template block.
    latest = await app_client.get(f"/v1/plugins/{sender_id}/com.lottery.template/latest")
    assert latest.status_code == 200, latest.text
    data = latest.json()
    assert data["renderer"] == "template"
    assert data["template"]["layout"] == "standard_card"
    assert data["template"]["fields"]["title"]["path"] == "$.title"
    assert data["template"]["actions"][0]["endpoint"] == "https://lottery.app/api/ack"


@pytest.mark.asyncio
async def test_publish_template_rejected_unknown_layout(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    body = _publish_template_body(
        sender_id,
        private_key,
        template={"layout": "spinning_3d_card", "fields": {"title": {"path": "$.t"}}},
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 422, response.text


@pytest.mark.asyncio
async def test_publish_template_rejected_missing_title(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    # standard_card requires `title`.
    body = _publish_template_body(
        sender_id,
        private_key,
        template={"layout": "standard_card", "fields": {"body": {"path": "$.b"}}},
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 422, response.text


@pytest.mark.asyncio
async def test_publish_template_rejected_bad_jsonpath(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    body = _publish_template_body(
        sender_id,
        private_key,
        template={
            "layout": "standard_card",
            # Array indexing isn't in the V1 JSONPath dialect.
            "fields": {"title": {"path": "$.items[0]"}},
        },
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 422, response.text


@pytest.mark.asyncio
async def test_publish_template_rejected_action_endpoint_not_declared(
    app_client: AsyncClient,
) -> None:
    sender_id, private_key = await _register_sender(app_client)
    body = _publish_template_body(
        sender_id,
        private_key,
        template={
            "layout": "standard_card",
            "fields": {"title": {"path": "$.title"}},
            # endpoint is outside the declared glob.
            "actions": [
                {"id": "ack", "label": "Acknowledge", "endpoint": "https://attacker.example/x"},
            ],
        },
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 422, response.text


@pytest.mark.asyncio
async def test_publish_template_rejected_duplicate_action_ids(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    body = _publish_template_body(
        sender_id,
        private_key,
        template={
            "layout": "standard_card",
            "fields": {"title": {"path": "$.title"}},
            "actions": [
                {"id": "tap", "label": "Tap", "endpoint": "https://lottery.app/api/a"},
                {"id": "tap", "label": "Tap2", "endpoint": "https://lottery.app/api/b"},
            ],
        },
        endpoints=["https://lottery.app/api/*"],
    )
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 422, response.text


# ---------- Phase 3b: live card upsert security gates --------------------


def _upsert_body(
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    private_key: Ed25519PrivateKey,
    *,
    card_key: str = "card-1",
    sequence_number: int = 1,
    expires_at: datetime | None = None,
) -> dict:
    expires = expires_at or _future(hours=12)
    body: dict = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin_id),
        "card_key": card_key,
        "encrypted_payload": _b64(b"ciphertext-and-tag-padding"),
        "nonce": _b64(uuid.uuid4().bytes[:12]),
        "sequence_number": sequence_number,
        "expires_at": _iso(expires),
    }
    envelope = {**body, "card_type": "live"}
    body["envelope_signature"] = _b64(private_key.sign(_canonical(envelope)))
    return body


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_plugin_revoked(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #2: revoked plugin must not upsert cards."""
    user_id, bootstrap = await _signup_login(app_client, email="rev@example.com")
    await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id, revoked=True,
    )

    body = _upsert_body(sender_id, user_id, plugin_id, private_key)
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 410, response.text


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_no_pairing(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #3: missing active pairing must reject."""
    user_id, bootstrap = await _signup_login(app_client, email="np@example.com")
    await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    # Seed a plugin without the pairing row.
    plugin = Plugin(
        id=uuid.uuid4(),
        sender_id=sender_id,
        plugin_identifier="com.lottery.nopair",
        version="1.0.0",
        manifest_hash=b"\x00" * 32,
        bundle_hash=b"\x00" * 32,
        signature=b"\x00" * 64,
        signed_bundle_url="https://lottery.app/plugin.js",
        capabilities=["network"],
        endpoints=["https://lottery.app/api/*"],
        renderer="script",
        card_type="live",
        card_key_path="$.card_key",
    )
    db_session.add(plugin)
    await db_session.commit()

    body = _upsert_body(sender_id, user_id, plugin.id, private_key)
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 410, response.text


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_expires_past(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #4: already-expired expires_at is rejected."""
    user_id, bootstrap = await _signup_login(app_client, email="exp1@example.com")
    await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    body = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 400, response.text


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_expires_exceeds_48h(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #4: expires_at beyond the 48h cap is rejected."""
    user_id, bootstrap = await _signup_login(app_client, email="exp2@example.com")
    await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    body = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        expires_at=_future(hours=72),  # > 48h cap
    )
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 400, response.text


@pytest.mark.asyncio
async def test_card_upsert_sequence_regression_rejected(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Phase 3b: lower sequence_number than existing must 409."""
    user_id, bootstrap = await _signup_login(app_client, email="seq@example.com")
    await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    first = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        card_key="seq-card", sequence_number=10,
    )
    r1 = await app_client.post("/v1/cards/upsert", json=first)
    assert r1.status_code == 201, r1.text

    older = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        card_key="seq-card", sequence_number=5,
    )
    r2 = await app_client.post("/v1/cards/upsert", json=older)
    assert r2.status_code == 409, r2.text


# ---------- Phase 3b: cross-user delete (Codex 62 RED #5) ----------------


@pytest.mark.asyncio
async def test_card_delete_signature_bound_to_user(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """A delete signature for Alice's card must NOT delete Bob's card with
    the same (sender_id, card_key). Closes Codex consultation 62 RED #5.
    """
    sender_id, private_key = await _register_sender(app_client)
    alice_user_id, alice_bootstrap = await _signup_login(app_client, email="alice@x.com")
    await _enroll_device(app_client, alice_bootstrap)
    bob_user_id, bob_bootstrap = await _signup_login(app_client, email="bob@x.com")
    await _enroll_device(app_client, bob_bootstrap)

    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=alice_user_id, sender_id=sender_id,
    )
    # Bob also paired with the same sender for the same plugin.
    db_session.add(
        Pairing(
            id=uuid.uuid4(),
            user_id=bob_user_id,
            sender_id=sender_id,
            encrypted_state=b"opaque",
        )
    )
    await db_session.commit()

    # Both upsert a card with the SAME card_key.
    for uid in (alice_user_id, bob_user_id):
        body = _upsert_body(sender_id, uid, plugin_id, private_key, card_key="shared-key")
        r = await app_client.post("/v1/cards/upsert", json=body)
        assert r.status_code == 201, r.text

    # Build a delete envelope for ALICE's card and submit it.
    alice_envelope = _canonical(
        {
            "sender_id": str(sender_id),
            "user_id": str(alice_user_id),
            "card_key": "shared-key",
        }
    )
    delete_body = {
        "sender_id": str(sender_id),
        "user_id": str(alice_user_id),
        "card_key": "shared-key",
        "envelope_signature": _b64(private_key.sign(alice_envelope)),
    }
    response = await app_client.post("/v1/cards/delete", json=delete_body)
    assert response.status_code == 204

    # Bob's card MUST still exist.
    rows = await db_session.execute(
        LiveCard.__table__.select().where(LiveCard.user_id == bob_user_id)
    )
    bob_cards = list(rows.all())
    assert len(bob_cards) == 1, "Bob's card was wrongly deleted by Alice's signature"

    # And Alice's card MUST be gone.
    rows_alice = await db_session.execute(
        LiveCard.__table__.select().where(LiveCard.user_id == alice_user_id)
    )
    assert list(rows_alice.all()) == []


# ---------- Phase 2 carry-over: inbox dismiss filter ---------------------


@pytest.mark.asyncio
async def test_inbox_omits_dismissed_messages(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Per consultation 57, inbox_for_device LEFT JOINs DeliveryStatus and
    filters dismissed_at IS NULL. A dismissed message must not re-surface
    in the next inbox pull on the dismissing device."""
    user_id, bootstrap = await _signup_login(app_client, email="dismiss@x.com")
    _, session_token = await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)

    db_session.add(
        Pairing(
            id=uuid.uuid4(),
            user_id=user_id,
            sender_id=sender_id,
            encrypted_state=b"opaque",
        )
    )
    plugin = Plugin(
        id=uuid.uuid4(),
        sender_id=sender_id,
        plugin_identifier="com.lottery.evt",
        version="1.0.0",
        manifest_hash=b"\x00" * 32,
        bundle_hash=b"\x00" * 32,
        signature=b"\x00" * 64,
        signed_bundle_url="https://lottery.app/plugin.js",
        capabilities=["network"],
        endpoints=["https://lottery.app/api/*"],
        renderer="script",
    )
    db_session.add(plugin)
    await db_session.commit()

    # Send a message via the public send route.
    expires = _future(hours=2)
    envelope = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin.id),
        "encrypted_body": _b64(b"ciphertext-and-tag-padding"),
        "nonce": _b64(uuid.uuid4().bytes[:12]),
        "min_plugin_version": "",
        "expires_at": _iso(expires),
    }
    sig = _b64(private_key.sign(_canonical(envelope)))
    send_body = {
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(plugin.id),
        "encrypted_body": envelope["encrypted_body"],
        "nonce": envelope["nonce"],
        "envelope_signature": sig,
        "expires_at": _iso(expires),
    }
    send = await app_client.post("/v1/messages/send", json=send_body)
    assert send.status_code == 201, send.text
    message_id = send.json()["message_id"]

    # Confirm it shows up.
    inbox1 = await app_client.get(
        "/v1/messages/inbox",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert inbox1.status_code == 200
    assert any(item["id"] == message_id for item in inbox1.json()["items"])

    # Dismiss + re-pull.
    dismiss = await app_client.post(
        f"/v1/messages/{message_id}/dismiss",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert dismiss.status_code in (200, 204), dismiss.text

    inbox2 = await app_client.get(
        "/v1/messages/inbox",
        headers={"Authorization": f"Bearer {session_token}"},
    )
    assert inbox2.status_code == 200
    assert all(item["id"] != message_id for item in inbox2.json()["items"]), (
        "dismissed message still surfaced in inbox feed"
    )
