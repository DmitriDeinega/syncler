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
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession
from syncler.crypto import DirectoryDevice

from app.models import LiveCard, Pairing, Plugin
from tests.v2_helpers import (
    build_live_card_delete_body,
    build_live_card_upsert_body,
    fresh_x25519_keypair,
)


@dataclass
class EnrolledCardDevice:
    device_id: uuid.UUID
    session_token: str
    x25519_public: bytes


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _canonical(fields: dict) -> bytes:
    return json.dumps(fields, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _future(hours: float = 1.0) -> datetime:
    return datetime.now(UTC) + timedelta(hours=hours)


def _iso(dt: datetime) -> str:
    return dt.isoformat().replace("+00:00", "Z")


# Phase 7: in-memory nonce registry deleted; replay detection now
# uses the durable nonce_replay table. See test_messages.py for the
# rationale on removing the autouse reset fixture.


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


async def _enroll_device(client: AsyncClient, token: str) -> EnrolledCardDevice:
    keypair = fresh_x25519_keypair()
    response = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "public_key": _b64(b"d" * 32),
            "encryption_public_key": _b64(keypair.public_key_raw),
        },
    )
    assert response.status_code == 201, response.text
    body = response.json()
    return EnrolledCardDevice(
        device_id=uuid.UUID(body["device_id"]),
        session_token=body["session_token"],
        x25519_public=keypair.public_key_raw,
    )


def _to_directory_device(device: EnrolledCardDevice) -> DirectoryDevice:
    return DirectoryDevice(
        device_id=str(device.device_id),
        encryption_public_key=device.x25519_public,
    )


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
    assert response.status_code == 400, response.text


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
    assert response.status_code == 400, response.text


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
    assert response.status_code == 400, response.text


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
    assert response.status_code == 400, response.text


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
    assert response.status_code == 400, response.text


# ---------- Phase 3b: live card upsert security gates --------------------


def _upsert_body(
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    private_key: Ed25519PrivateKey,
    *,
    recipients: list[EnrolledCardDevice],
    card_key: str = "card-1",
    sequence_number: int = 1,
    expires_at: datetime | None = None,
) -> dict:
    """Phase 9b V2 live-card upsert body (spec §11.5)."""
    return build_live_card_upsert_body(
        sender_id=str(sender_id),
        user_id=str(user_id),
        plugin_id=str(plugin_id),
        card_key=card_key,
        card_type="standard_card",
        sequence_number=sequence_number,
        payload={"card_key": card_key, "msg": "v2-card"},
        recipient_devices=[_to_directory_device(d) for d in recipients],
        recipient_directory_version=1,
        sender_ed25519=private_key,
        expires_at=expires_at,
    )


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_plugin_revoked(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #2: revoked plugin must not upsert cards."""
    user_id, bootstrap = await _signup_login(app_client, email="rev@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id, revoked=True,
    )

    body = _upsert_body(sender_id, user_id, plugin_id, private_key, recipients=[enrolled])
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 410, response.text


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_no_pairing(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #3: missing active pairing must reject."""
    user_id, bootstrap = await _signup_login(app_client, email="np@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
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

    body = _upsert_body(sender_id, user_id, plugin.id, private_key, recipients=[enrolled])
    response = await app_client.post("/v1/cards/upsert", json=body)
    assert response.status_code == 410, response.text


@pytest.mark.asyncio
async def test_card_upsert_rejected_when_expires_past(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Codex consultation 62 RED #4: already-expired expires_at is rejected."""
    user_id, bootstrap = await _signup_login(app_client, email="exp1@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    body = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        recipients=[enrolled],
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
    enrolled = await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    body = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        recipients=[enrolled],
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
    enrolled = await _enroll_device(app_client, bootstrap)
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    first = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        recipients=[enrolled],
        card_key="seq-card", sequence_number=10,
    )
    r1 = await app_client.post("/v1/cards/upsert", json=first)
    assert r1.status_code == 201, r1.text

    older = _upsert_body(
        sender_id, user_id, plugin_id, private_key,
        recipients=[enrolled],
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
    alice_enrolled = await _enroll_device(app_client, alice_bootstrap)
    bob_user_id, bob_bootstrap = await _signup_login(app_client, email="bob@x.com")
    bob_enrolled = await _enroll_device(app_client, bob_bootstrap)

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

    # Both upsert a card with the SAME card_key, each sealed to their
    # own device.
    for uid, enrolled in ((alice_user_id, alice_enrolled), (bob_user_id, bob_enrolled)):
        body = _upsert_body(
            sender_id, uid, plugin_id, private_key,
            recipients=[enrolled], card_key="shared-key",
        )
        r = await app_client.post("/v1/cards/upsert", json=body)
        assert r.status_code == 201, r.text

    # Build a V2 delete envelope for ALICE's card. The envelope binds
    # user_id (Codex 62 RED #5) AND plugin_id (Codex 125 RED #1 fix),
    # so this delete cannot affect Bob's card with the same card_key
    # — that's the test invariant.
    delete_body = build_live_card_delete_body(
        sender_id=str(sender_id),
        user_id=str(alice_user_id),
        plugin_id=str(plugin_id),
        card_key="shared-key",
        sender_ed25519=private_key,
    )
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


# ---------- Phase 12 (Codex 95): delete-envelope freshness + replay ------


def _delete_body(
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    private_key,
    *,
    plugin_id: uuid.UUID,
    card_key: str,
    nonce: bytes | None = None,
    expires_at: datetime | None = None,
) -> dict:
    """Phase 9b V2 live-card delete body (spec §11.6). plugin_id is
    now part of the canonical signed envelope (Codex 125 RED #1 fix)."""
    return build_live_card_delete_body(
        sender_id=str(sender_id),
        user_id=str(user_id),
        plugin_id=str(plugin_id),
        card_key=card_key,
        sender_ed25519=private_key,
        nonce=nonce,
        expires_at=expires_at,
    )


@pytest.mark.asyncio
async def test_card_delete_rejects_replayed_envelope(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """A captured delete envelope MUST be rejected on the second
    submission via the per-sender nonce_replay registry. Without this
    the delete would replay forever against any future card with the
    same (sender_id, user_id, card_key). Codex consultation 95.
    """
    sender_id, private_key = await _register_sender(app_client)
    user_id, bootstrap = await _signup_login(app_client, email="test@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    # First card + first delete succeed.
    body1 = _upsert_body(sender_id, user_id, plugin_id, private_key, recipients=[enrolled], card_key="C")
    r = await app_client.post("/v1/cards/upsert", json=body1)
    assert r.status_code == 201, r.text

    delete = _delete_body(sender_id, user_id, private_key, plugin_id=plugin_id, card_key="C")
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 204, r.text

    # Sender re-creates the card (could be a legitimate later state).
    body2 = _upsert_body(sender_id, user_id, plugin_id, private_key, recipients=[enrolled], card_key="C")
    r = await app_client.post("/v1/cards/upsert", json=body2)
    assert r.status_code == 201, r.text

    # Replay the exact same delete envelope → 409 nonce-already-used.
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 409, r.text
    assert "nonce already used" in r.json()["detail"]


@pytest.mark.asyncio
async def test_card_delete_rejects_expired_envelope(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """A delete with expires_at in the past gets 400. Codex 95."""
    sender_id, private_key = await _register_sender(app_client)
    user_id, bootstrap = await _signup_login(app_client, email="test@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )
    body = _upsert_body(sender_id, user_id, plugin_id, private_key, recipients=[enrolled], card_key="C")
    await app_client.post("/v1/cards/upsert", json=body)

    expired = datetime.now(UTC) - timedelta(seconds=5)
    delete = _delete_body(
        sender_id, user_id, private_key, plugin_id=plugin_id, card_key="C", expires_at=expired,
    )
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 400, r.text
    assert "expires_at" in r.json()["detail"]


@pytest.mark.asyncio
async def test_card_delete_rejects_exceeds_ttl_cap(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """A delete with expires_at > now + 48h gets 400. Same cap as upsert."""
    sender_id, private_key = await _register_sender(app_client)
    user_id, bootstrap = await _signup_login(app_client, email="test@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    plugin_id = await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )
    body = _upsert_body(sender_id, user_id, plugin_id, private_key, recipients=[enrolled], card_key="C")
    await app_client.post("/v1/cards/upsert", json=body)

    too_far = datetime.now(UTC) + timedelta(hours=72)
    delete = _delete_body(
        sender_id, user_id, private_key, plugin_id=plugin_id, card_key="C", expires_at=too_far,
    )
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 400, r.text


@pytest.mark.asyncio
async def test_card_delete_rejects_naive_expires_at(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """A naive (no-tzinfo) expires_at in the body is rejected with 422
    by Pydantic — NOT by the route's comparison against tz-aware now,
    which would TypeError → 500 (Codex 111 nit).
    """
    sender_id, private_key = await _register_sender(app_client)
    user_id, bootstrap = await _signup_login(app_client, email="test@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    # Submit a delete with a naive datetime string (no Z, no offset)
    # — Pydantic's `expires_at` validator rejects it before signature
    # verification.
    nonce = _b64(uuid.uuid4().bytes[:12])
    naive_iso = (datetime.now(UTC) + timedelta(hours=1)).replace(tzinfo=None).isoformat()
    body = {
        "protocol_version": 2,
        "envelope_kind": "live_card_delete",
        "sender_id": str(sender_id),
        "user_id": str(user_id),
        "plugin_id": str(uuid.uuid4()),
        "card_key": "C",
        "nonce": nonce,
        "expires_at": naive_iso,
        "envelope_signature": _b64(b"\xff" * 64),
    }
    r = await app_client.post("/v1/cards/delete", json=body)
    # 400 (our app's validation_exception_handler downgrades 422 -> 400).
    assert r.status_code == 400, r.text


@pytest.mark.asyncio
async def test_card_delete_records_nonce_even_when_card_missing(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """A delete for a non-existent card still records the nonce so a
    replay can't later land against a freshly-created card with the
    same key. The route must explicitly commit because
    `delete_live_card` no-ops without committing when the row is
    absent — without that commit the nonce_replay insert rolls back
    on session-close.
    """
    sender_id, private_key = await _register_sender(app_client)
    user_id, bootstrap = await _signup_login(app_client, email="test@example.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    await _seed_pairing_and_live_plugin(
        db_session, user_id=user_id, sender_id=sender_id,
    )

    # Delete a card that doesn't exist — should still succeed (idempotent).
    delete = _delete_body(
        sender_id, user_id, private_key,
        plugin_id=uuid.uuid4(),  # any valid uuid; the lookup just misses
        card_key="never-existed",
    )
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 204, r.text

    # Now replay → MUST 409. If the no-op path didn't commit, this
    # would silently succeed and reveal the replay window.
    r = await app_client.post("/v1/cards/delete", json=delete)
    assert r.status_code == 409, r.text


# ---------- Phase 2 carry-over: inbox dismiss filter ---------------------


@pytest.mark.asyncio
async def test_inbox_omits_dismissed_messages(
    app_client: AsyncClient, db_session: AsyncSession
) -> None:
    """Per consultation 57, inbox_for_device LEFT JOINs DeliveryStatus and
    filters dismissed_at IS NULL. A dismissed message must not re-surface
    in the next inbox pull on the dismissing device."""
    user_id, bootstrap = await _signup_login(app_client, email="dismiss@x.com")
    enrolled = await _enroll_device(app_client, bootstrap)
    session_token = enrolled.session_token
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

    # Send a V2 event publish via the public send route.
    from tests.v2_helpers import build_event_publish_body
    send_body = build_event_publish_body(
        sender_id=str(sender_id),
        user_id=str(user_id),
        plugin_id=str(plugin.id),
        payload={"msg": "v2-event"},
        recipient_devices=[_to_directory_device(enrolled)],
        recipient_directory_version=1,
        sender_ed25519=private_key,
        expires_at=_future(hours=2),
    )
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
