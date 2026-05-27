"""V3 Tier 2B observability endpoints — header-signed GETs that let a
partner answer 'did my sends reach the server?' without breaking the
E2EE contract.

Triad 162 plan, codex/gemini consensus:
- Reuse the existing Ed25519-signed-canonical-string pattern.
- Lift signature material into headers because GET semantics matter.
- Privacy: counts + metadata only, no recipient counts, no device counts.
"""

from __future__ import annotations

import base64
import time
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import LiveCard, Message, Pairing, Plugin


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _signup_user(client: AsyncClient, email: str) -> uuid.UUID:
    """Create a User via the public signup API. We need real User rows so
    Pairing/Message FK constraints hold — direct ORM inserts in the test
    session fight with the API's commit boundary."""
    r = await client.post(
        "/v1/auth/signup",
        json={
            "email": email,
            "auth_key_hash": _b64(b"a" * 32),
            "encrypted_master_key": _b64(b"b" * 96),
            "auth_salt": _b64(b"c" * 16),
            "argon2_params_version": 1,
        },
    )
    assert r.status_code == 201, r.text
    return uuid.UUID(r.json()["user_id"])


async def _register_sender(client: AsyncClient) -> tuple[str, Ed25519PrivateKey]:
    priv = Ed25519PrivateKey.generate()
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    r = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(pub), "name": "Observability Test"},
    )
    assert r.status_code == 201, r.text
    return r.json()["sender_id"], priv


def _sign_senders_me(
    priv: Ed25519PrivateKey,
    *,
    endpoint: str,
    sender_id: str,
    query: str,
    timestamp: int | None = None,
) -> dict[str, str]:
    ts = int(time.time()) if timestamp is None else timestamp
    canonical = f"syncler-v1-senders-me:{endpoint}:{sender_id}:{query}:{ts}".encode("ascii")
    sig = priv.sign(canonical)
    return {
        "X-Sender-Id": sender_id,
        "X-Sender-Timestamp": str(ts),
        "X-Sender-Signature": _b64(sig),
    }


async def _seed_pairing(
    db: AsyncSession, *, user_id: uuid.UUID, sender_uuid: uuid.UUID,
) -> None:
    db.add(
        Pairing(
            id=uuid.uuid4(),
            user_id=user_id,
            sender_id=sender_uuid,
            encrypted_state=b"\x00" * 32,
            state_version=1,
            key_generation=1,
        )
    )
    await db.commit()


async def _seed_plugin(db: AsyncSession, sender_uuid: uuid.UUID) -> uuid.UUID:
    plugin_id = uuid.uuid4()
    db.add(
        Plugin(
            id=plugin_id,
            sender_id=sender_uuid,
            plugin_identifier=f"app.test.{plugin_id.hex[:6]}",
            version="0.1.0",
            manifest_hash=b"\x00" * 32,
            bundle_hash=b"\x00" * 32,
            signature=b"\x00" * 64,
            signed_bundle_url="https://example/bundle.tar.gz",
            capabilities={},
            endpoints={},
            renderer="template",
            template={},
            card_type="event",
        )
    )
    await db.commit()
    return plugin_id


@pytest.mark.asyncio
async def test_senders_me_messages_returns_metadata_only(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """Happy path: signed GET returns rows ordered by sent_at desc, metadata only."""
    sender_id, priv = await _register_sender(app_client)
    sender_uuid = uuid.UUID(sender_id)

    user_id = await _signup_user(app_client, "obs-msg@example.com")
    await _seed_pairing(db_session, user_id=user_id, sender_uuid=sender_uuid)
    plugin_id = await _seed_plugin(db_session, sender_uuid)

    # Two messages, second is newest.
    now = datetime.now(timezone.utc)
    for i, ts in enumerate([now - timedelta(minutes=10), now]):
        db_session.add(
            Message(
                id=uuid.uuid4(),
                sender_id=sender_uuid,
                user_id=user_id,
                plugin_id=plugin_id,
                encrypted_body_pointer="A" * (100 + i),
                expires_at=now + timedelta(days=7),
                sent_at=ts,
            )
        )
    await db_session.commit()

    headers = _sign_senders_me(priv, endpoint="messages", sender_id=sender_id, query="limit=20")
    r = await app_client.get("/v1/senders/me/messages?limit=20", headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert len(data["items"]) == 2
    # Newest first.
    assert data["items"][0]["encrypted_body_size"] == 101
    assert data["items"][1]["encrypted_body_size"] == 100
    # Only the expected fields are present.
    keys = set(data["items"][0].keys())
    assert keys == {
        "id", "user_id", "plugin_id", "encrypted_body_size", "sent_at", "expires_at",
    }


@pytest.mark.asyncio
async def test_senders_me_cards_returns_metadata_only(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    sender_id, priv = await _register_sender(app_client)
    sender_uuid = uuid.UUID(sender_id)

    user_id = await _signup_user(app_client, "obs-cards@example.com")
    await _seed_pairing(db_session, user_id=user_id, sender_uuid=sender_uuid)
    plugin_id = await _seed_plugin(db_session, sender_uuid)

    now = datetime.now(timezone.utc)
    card_id = uuid.uuid4()
    db_session.add(
        LiveCard(
            id=card_id,
            user_id=user_id,
            sender_id=sender_uuid,
            plugin_id=plugin_id,
            card_key="game-42",
            encrypted_body_pointer="X" * 200,
            card_type="live",
            sequence_number=7,
            expires_at=now + timedelta(hours=1),
        )
    )
    await db_session.commit()

    headers = _sign_senders_me(priv, endpoint="cards", sender_id=sender_id, query="limit=20")
    r = await app_client.get("/v1/senders/me/cards?limit=20", headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert len(data["items"]) == 1
    item = data["items"][0]
    assert item["card_key"] == "game-42"
    assert item["sequence_number"] == 7
    keys = set(item.keys())
    assert keys == {
        "id", "user_id", "plugin_id", "card_key", "sequence_number",
        "created_at", "updated_at", "expires_at",
    }


@pytest.mark.asyncio
async def test_senders_me_stats_counts(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    sender_id, priv = await _register_sender(app_client)
    sender_uuid = uuid.UUID(sender_id)

    user_id = await _signup_user(app_client, "obs-stats@example.com")
    await _seed_pairing(db_session, user_id=user_id, sender_uuid=sender_uuid)
    plugin_id = await _seed_plugin(db_session, sender_uuid)

    now = datetime.now(timezone.utc)
    # One in-window message and one 48h old (out of window).
    db_session.add(Message(
        id=uuid.uuid4(), sender_id=sender_uuid, user_id=user_id, plugin_id=plugin_id,
        encrypted_body_pointer="A", expires_at=now + timedelta(days=7), sent_at=now,
    ))
    db_session.add(Message(
        id=uuid.uuid4(), sender_id=sender_uuid, user_id=user_id, plugin_id=plugin_id,
        encrypted_body_pointer="B", expires_at=now + timedelta(days=7), sent_at=now - timedelta(hours=48),
    ))
    # One live card in flight, one expired.
    db_session.add(LiveCard(
        id=uuid.uuid4(), user_id=user_id, sender_id=sender_uuid, plugin_id=plugin_id,
        card_key="live-1", encrypted_body_pointer="A", card_type="live",
        sequence_number=1, expires_at=now + timedelta(hours=1),
    ))
    db_session.add(LiveCard(
        id=uuid.uuid4(), user_id=user_id, sender_id=sender_uuid, plugin_id=plugin_id,
        card_key="live-2", encrypted_body_pointer="B", card_type="live",
        sequence_number=1, expires_at=now - timedelta(hours=1),
    ))
    await db_session.commit()

    headers = _sign_senders_me(priv, endpoint="stats", sender_id=sender_id, query="")
    r = await app_client.get("/v1/senders/me/stats", headers=headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["messages_sent_last_24h"] == 1
    assert data["paired_users_active"] == 1
    assert data["live_cards_in_flight"] == 1


@pytest.mark.asyncio
async def test_senders_me_rejects_missing_headers(app_client: AsyncClient) -> None:
    r = await app_client.get("/v1/senders/me/stats")
    assert r.status_code == 401
    assert "missing sender auth headers" in r.json()["detail"]


@pytest.mark.asyncio
async def test_senders_me_rejects_stale_timestamp(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    stale = int(time.time()) - 1000  # > 300s window
    headers = _sign_senders_me(
        priv, endpoint="stats", sender_id=sender_id, query="", timestamp=stale,
    )
    r = await app_client.get("/v1/senders/me/stats", headers=headers)
    assert r.status_code == 401
    assert "stale or future timestamp" in r.json()["detail"]


@pytest.mark.asyncio
async def test_senders_me_rejects_wrong_key(app_client: AsyncClient) -> None:
    sender_id, _ = await _register_sender(app_client)
    wrong_key = Ed25519PrivateKey.generate()
    headers = _sign_senders_me(wrong_key, endpoint="stats", sender_id=sender_id, query="")
    r = await app_client.get("/v1/senders/me/stats", headers=headers)
    assert r.status_code == 401
    assert "invalid sender signature" in r.json()["detail"]


@pytest.mark.asyncio
async def test_senders_me_rejects_canonical_mismatch(app_client: AsyncClient) -> None:
    """Signing 'messages' but hitting /stats must fail — endpoint name is in canonical."""
    sender_id, priv = await _register_sender(app_client)
    headers = _sign_senders_me(priv, endpoint="messages", sender_id=sender_id, query="")
    r = await app_client.get("/v1/senders/me/stats", headers=headers)
    assert r.status_code == 401


@pytest.mark.asyncio
async def test_senders_me_rejects_unknown_sender(app_client: AsyncClient) -> None:
    """Random sender_id that isn't registered → 404 (per existing convention)."""
    priv = Ed25519PrivateKey.generate()
    bogus_id = str(uuid.uuid4())
    headers = _sign_senders_me(priv, endpoint="stats", sender_id=bogus_id, query="")
    r = await app_client.get("/v1/senders/me/stats", headers=headers)
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_senders_me_messages_respects_limit(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    sender_id, priv = await _register_sender(app_client)
    sender_uuid = uuid.UUID(sender_id)
    user_id = await _signup_user(app_client, "obs-limit@example.com")
    await _seed_pairing(db_session, user_id=user_id, sender_uuid=sender_uuid)
    plugin_id = await _seed_plugin(db_session, sender_uuid)

    now = datetime.now(timezone.utc)
    for i in range(5):
        db_session.add(Message(
            id=uuid.uuid4(), sender_id=sender_uuid, user_id=user_id, plugin_id=plugin_id,
            encrypted_body_pointer=f"msg-{i}",
            expires_at=now + timedelta(days=7),
            sent_at=now - timedelta(minutes=i),
        ))
    await db_session.commit()

    headers = _sign_senders_me(priv, endpoint="messages", sender_id=sender_id, query="limit=2")
    r = await app_client.get("/v1/senders/me/messages?limit=2", headers=headers)
    assert r.status_code == 200
    assert len(r.json()["items"]) == 2


@pytest.mark.asyncio
async def test_senders_me_isolates_per_sender(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    """Sender A must NOT see Sender B's messages even with a valid signature."""
    # Sender A is the requester; sender B owns the messages. We sign up
    # only one user (sender B's) — sender A queries through their own
    # signature and should see an empty list regardless of B's data.
    # Two `register_sender` + one `signup` keeps us inside the shared
    # "signup" rate-limit bucket (3/60s).
    sender_a_id, priv_a = await _register_sender(app_client)
    sender_b_id, _ = await _register_sender(app_client)
    sender_b_uuid = uuid.UUID(sender_b_id)

    user_b = await _signup_user(app_client, "obs-iso@example.com")
    await _seed_pairing(db_session, user_id=user_b, sender_uuid=sender_b_uuid)
    plugin_b = await _seed_plugin(db_session, sender_b_uuid)

    now = datetime.now(timezone.utc)
    db_session.add(Message(
        id=uuid.uuid4(), sender_id=sender_b_uuid, user_id=user_b, plugin_id=plugin_b,
        encrypted_body_pointer="B-secret-metadata",
        expires_at=now + timedelta(days=7), sent_at=now,
    ))
    await db_session.commit()

    headers = _sign_senders_me(priv_a, endpoint="messages", sender_id=sender_a_id, query="limit=20")
    r = await app_client.get("/v1/senders/me/messages?limit=20", headers=headers)
    assert r.status_code == 200
    assert r.json()["items"] == []
