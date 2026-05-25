"""Tests for the V1.5 automated pairing broker FastAPI app.

Schema-level only — no actual networking, no real device. Builds
envelopes with the canonical test helpers, posts via fastapi.testclient,
asserts the response status + storage state.
"""

from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi import HTTPException, Request

from syncler.bootstrap import (
    assemble_bootstrap_aad,
    bootstrap_key_id,
    x25519_keypair_pem,
)
from syncler.broker import make_app
from syncler.broker_storage import (
    BrokerEntry,
    InMemoryBrokerStorage,
)

# Static test fixtures.
_SENDER_BROKER_URL = "https://sender.example.com/syncler/bootstrap"
_PAIRING_ID = "00000000-1111-2222-3333-444444444444"
_SENDER_ID = "55555555-6666-7777-8888-999999999999"
_USER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def _build_envelope(
    *,
    sender_bootstrap_priv: X25519PrivateKey,
    sender_bootstrap_pub_raw: bytes,
    pairing_id: str = _PAIRING_ID,
    sender_id: str = _SENDER_ID,
    sender_broker_url: str = _SENDER_BROKER_URL,
    user_id: str = _USER_ID,
    pairing_key: bytes | None = None,
    exp_iso: str | None = None,
) -> dict[str, Any]:
    """Build a real bootstrap envelope using the Phase 5a-1 spec.
    Mirrors the Android `BootstrapEnvelope.build(...)` step-by-step
    so the test client posts the same wire shape a real device
    would.
    """
    if pairing_key is None:
        pairing_key = b"\x42" * 32
    if exp_iso is None:
        exp_iso = (datetime.now(UTC) + timedelta(seconds=60)).strftime("%Y-%m-%dT%H:%M:%SZ")

    # 1. Ephemeral X25519.
    eph_priv = X25519PrivateKey.generate()
    eph_pub_raw = eph_priv.public_key().public_bytes_raw()

    # 2. ECDH against sender's bootstrap pub.
    sender_bootstrap_pub_obj = X25519PublicKey.from_public_bytes(sender_bootstrap_pub_raw)
    shared = eph_priv.exchange(sender_bootstrap_pub_obj)

    # 3. HKDF-SHA256 → aead_key.
    salt = eph_pub_raw + sender_bootstrap_pub_raw
    aead_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=b"syncler-v1-bootstrap-aead",
    ).derive(shared)

    # 4. Canonical AAD.
    kid_b64 = _b64(bootstrap_key_id(sender_bootstrap_pub_raw))
    aad = assemble_bootstrap_aad(
        bootstrap_key_id_b64=kid_b64,
        exp_iso=exp_iso,
        pairing_id=pairing_id,
        sender_broker_url=sender_broker_url,
        sender_id=sender_id,
    )

    # 5. Encrypt {user_id, pairing_key}.
    plaintext = json.dumps(
        {"user_id": user_id, "pairing_key": _b64(pairing_key)},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    nonce = b"\x01" * 12  # deterministic for replay tests
    ciphertext = AESGCM(aead_key).encrypt(nonce, plaintext, aad)

    return {
        "protocol_version": 1,
        "pairing_id": pairing_id,
        "sender_id": sender_id,
        "bootstrap_key_id": kid_b64,
        "exp": exp_iso,
        "ephemeral_pubkey": _b64(eph_pub_raw),
        "nonce": _b64(nonce),
        "ciphertext": _b64(ciphertext),
    }


@pytest.fixture
def keypair() -> tuple[X25519PrivateKey, bytes]:
    return x25519_keypair_pem()


@pytest.fixture
def storage() -> InMemoryBrokerStorage:
    return InMemoryBrokerStorage()


@pytest.fixture
def app(keypair: tuple[X25519PrivateKey, bytes], storage: InMemoryBrokerStorage):
    priv, pub = keypair
    return make_app(
        bootstrap_private_key=priv,
        bootstrap_public_key_raw=pub,
        sender_broker_url=_SENDER_BROKER_URL,
        storage=storage,
    )


@pytest.fixture
def client(app):  # type: ignore[no-untyped-def]
    from fastapi.testclient import TestClient
    return TestClient(app, raise_server_exceptions=False)


# --------------------------- happy paths ---------------------------


def test_first_completion_returns_201(client, keypair, storage):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    response = client.post("/", json=envelope)
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["status"] == "ok"
    assert body["pairing_id"] == _PAIRING_ID
    entry = storage.fetch(_PAIRING_ID)
    assert entry is not None
    assert entry.user_id == _USER_ID
    assert entry.pairing_key == b"\x42" * 32


def test_idempotent_replay_same_values_returns_200(client, keypair, storage):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    # Same envelope twice — needs a fresh ephemeral key per build but
    # same plaintext. Build with a stable user_id/pairing_key so the
    # CAS sees the second call as idempotent.
    pk = b"\xab" * 32
    env1 = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub, pairing_key=pk,
    )
    env2 = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub, pairing_key=pk,
    )
    r1 = client.post("/", json=env1)
    assert r1.status_code == 201, r1.text
    r2 = client.post("/", json=env2)
    assert r2.status_code == 200, r2.text


def test_replay_with_different_values_returns_409(client, keypair, storage):
    priv, pub = keypair
    env1 = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub,
        pairing_key=b"\x11" * 32,
    )
    env2 = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub,
        pairing_key=b"\x22" * 32,
    )
    r1 = client.post("/", json=env1)
    assert r1.status_code == 201, r1.text
    r2 = client.post("/", json=env2)
    assert r2.status_code == 409, r2.text


# --------------------------- decrypt failures (opaque 401) ---------------------------


def test_expired_envelope_returns_401_opaque(client, keypair):
    priv, pub = keypair
    # exp_iso 10 minutes in the past → outside ±5min window.
    stale = (datetime.now(UTC) - timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    envelope = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub, exp_iso=stale,
    )
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == "bootstrap decrypt failed"


def test_substituted_sender_broker_url_returns_401_opaque(client, keypair):
    priv, pub = keypair
    # Build envelope with a DIFFERENT broker URL — the broker's
    # trusted state is _SENDER_BROKER_URL, so AAD reconstruction
    # diverges and AEAD tag fails.
    envelope = _build_envelope(
        sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub,
        sender_broker_url="https://attacker.example.com/syncler/bootstrap",
    )
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text


def test_aead_tag_flip_returns_401_opaque(client, keypair):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    # Flip one bit in the ciphertext.
    ct_raw = base64.b64decode(envelope["ciphertext"])
    flipped = bytearray(ct_raw)
    flipped[0] ^= 0x01
    envelope["ciphertext"] = base64.b64encode(bytes(flipped)).decode("ascii")
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text


def test_wrong_bootstrap_key_id_returns_401_opaque(client, keypair):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    # Replace the bootstrap_key_id with a bogus 16-byte value —
    # rotation guard fires.
    envelope["bootstrap_key_id"] = _b64(b"\x00" * 16)
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text


def test_invalid_low_order_ephemeral_pubkey_returns_401_opaque(client, keypair):
    """Codex 89 RED: X25519 low-order/invalid points must surface as
    opaque 401, not a 500 from the X25519 library bubbling up."""
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    # All-zero is a well-known low-order point that cryptography
    # rejects at exchange() time.
    envelope["ephemeral_pubkey"] = _b64(b"\x00" * 32)
    response = client.post("/", json=envelope)
    assert response.status_code == 401, response.text
    assert response.json()["detail"] == "bootstrap decrypt failed"


# --------------------------- shape validation (400) ---------------------------


def test_missing_field_returns_400(client, keypair):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    del envelope["nonce"]
    response = client.post("/", json=envelope)
    assert response.status_code == 400


def test_unexpected_extra_field_returns_400(client, keypair):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope["unknown_field"] = "leak"
    response = client.post("/", json=envelope)
    assert response.status_code == 400


def test_wrong_protocol_version_returns_400(client, keypair):
    priv, pub = keypair
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope["protocol_version"] = 2
    response = client.post("/", json=envelope)
    assert response.status_code == 400


def test_invalid_base64_lengths_return_400(client, keypair):
    priv, pub = keypair
    # ephemeral_pubkey must decode to 32 bytes; pass 31.
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope["ephemeral_pubkey"] = _b64(b"\x00" * 31)
    response = client.post("/", json=envelope)
    assert response.status_code == 400

    # nonce must decode to 12 bytes; pass 8.
    envelope2 = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope2["nonce"] = _b64(b"\x00" * 8)
    response = client.post("/", json=envelope2)
    assert response.status_code == 400

    # bootstrap_key_id must decode to 16 bytes; pass 15.
    envelope3 = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope3["bootstrap_key_id"] = _b64(b"\x00" * 15)
    response = client.post("/", json=envelope3)
    assert response.status_code == 400

    # ciphertext must decode to ≥17 bytes; pass 10.
    envelope4 = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    envelope4["ciphertext"] = _b64(b"\x00" * 10)
    response = client.post("/", json=envelope4)
    assert response.status_code == 400


def test_invalid_json_returns_400(client):
    response = client.post("/", data=b"not json")
    assert response.status_code == 400


def test_non_object_json_returns_400(client):
    response = client.post("/", json=["not", "an", "object"])
    assert response.status_code == 400


# --------------------------- rate limiter ---------------------------


def test_rate_limiter_http_exception_propagates(keypair, storage):
    priv, pub = keypair

    async def deny(request: Request) -> None:
        raise HTTPException(status_code=429, detail="too many requests")

    app = make_app(
        bootstrap_private_key=priv,
        bootstrap_public_key_raw=pub,
        sender_broker_url=_SENDER_BROKER_URL,
        storage=storage,
        rate_limiter=deny,
    )
    from fastapi.testclient import TestClient
    client = TestClient(app, raise_server_exceptions=False)
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    response = client.post("/", json=envelope)
    assert response.status_code == 429
    assert response.json()["detail"] == "too many requests"


def test_rate_limiter_unexpected_exception_returns_500(keypair, storage):
    priv, pub = keypair

    async def kaboom(request: Request) -> None:
        raise RuntimeError("oops")

    app = make_app(
        bootstrap_private_key=priv,
        bootstrap_public_key_raw=pub,
        sender_broker_url=_SENDER_BROKER_URL,
        storage=storage,
        rate_limiter=kaboom,
    )
    from fastapi.testclient import TestClient
    # raise_server_exceptions=False lets us OBSERVE the 500 instead of
    # the TestClient propagating the RuntimeError (consult 88 note).
    client = TestClient(app, raise_server_exceptions=False)
    envelope = _build_envelope(sender_bootstrap_priv=priv, sender_bootstrap_pub_raw=pub)
    response = client.post("/", json=envelope)
    assert response.status_code == 500


# --------------------------- factory arg validation ---------------------------


def test_make_app_rejects_wrong_pub_key_length(storage):
    priv = X25519PrivateKey.generate()
    with pytest.raises(ValueError, match="32 bytes"):
        make_app(
            bootstrap_private_key=priv,
            bootstrap_public_key_raw=b"\x00" * 31,
            sender_broker_url=_SENDER_BROKER_URL,
            storage=storage,
        )
