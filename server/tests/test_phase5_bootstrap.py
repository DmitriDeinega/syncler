"""Phase 5a-2 — automated pairing protocol foundation server tests.

Targeted regression coverage for the new endpoints + the modified
pairing routes. Mirrors the patterns in `test_plugins.py` and
`test_phase3.py`. Spec: `docs/crypto-spec.md §9`. Agreement:
`.triad/70-phase5-agreement.md`.
"""

from __future__ import annotations

import base64

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from httpx import AsyncClient


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _register_sender(client: AsyncClient) -> tuple[str, Ed25519PrivateKey]:
    priv = Ed25519PrivateKey.generate()
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    response = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(pub), "name": "Bootstrap Test"},
    )
    assert response.status_code == 201, response.text
    return response.json()["sender_id"], priv


def _build_bootstrap_register_body(sender_id: str, priv: Ed25519PrivateKey, bootstrap_pub_raw: bytes) -> dict:
    sig_input = b"syncler-v1-bootstrap-key:" + bootstrap_pub_raw
    sig = priv.sign(sig_input)
    return {
        "sender_id": sender_id,
        "bootstrap_key": _b64(bootstrap_pub_raw),
        "bootstrap_key_signature": _b64(sig),
    }


@pytest.mark.asyncio
async def test_bootstrap_key_register_success(app_client: AsyncClient) -> None:
    """Codex 75 RED test #1: register success path."""
    sender_id, priv = await _register_sender(app_client)
    x_priv = X25519PrivateKey.generate()
    x_pub = x_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    body = _build_bootstrap_register_body(sender_id, priv, x_pub)

    response = await app_client.post("/v1/senders/me/bootstrap-key", json=body)
    assert response.status_code == 201, response.text
    # bootstrap_key_id is base64 SHA-256(pub)[:16] — 16 bytes raw = 24 b64 chars (with padding).
    import hashlib
    expected_kid = base64.b64encode(hashlib.sha256(x_pub).digest()[:16]).decode("ascii")
    assert response.json()["bootstrap_key_id"] == expected_kid


@pytest.mark.asyncio
async def test_bootstrap_key_register_rejects_bad_signature(app_client: AsyncClient) -> None:
    """Codex 75 RED test #2: bad signature → 401."""
    sender_id, _ = await _register_sender(app_client)
    x_priv = X25519PrivateKey.generate()
    x_pub = x_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    # Use a DIFFERENT Ed25519 key to sign — the server must reject.
    wrong = Ed25519PrivateKey.generate()
    body = _build_bootstrap_register_body(sender_id, wrong, x_pub)

    response = await app_client.post("/v1/senders/me/bootstrap-key", json=body)
    assert response.status_code == 401, response.text


@pytest.mark.asyncio
async def test_bootstrap_key_register_rotation_overwrites(app_client: AsyncClient) -> None:
    """Codex 75 RED test #3: re-registering rotates (overwrites) the key."""
    sender_id, priv = await _register_sender(app_client)
    x1_priv = X25519PrivateKey.generate()
    x1_pub = x1_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    r1 = await app_client.post(
        "/v1/senders/me/bootstrap-key",
        json=_build_bootstrap_register_body(sender_id, priv, x1_pub),
    )
    assert r1.status_code == 201
    kid1 = r1.json()["bootstrap_key_id"]

    x2_priv = X25519PrivateKey.generate()
    x2_pub = x2_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    r2 = await app_client.post(
        "/v1/senders/me/bootstrap-key",
        json=_build_bootstrap_register_body(sender_id, priv, x2_pub),
    )
    assert r2.status_code == 201
    kid2 = r2.json()["bootstrap_key_id"]
    # New key id => rotation took effect.
    assert kid1 != kid2


@pytest.mark.asyncio
async def test_pairing_initiate_rejects_broker_url_without_registered_key(app_client: AsyncClient) -> None:
    """Codex 75 RED test #4: initiate requires registered bootstrap key
    when sender_broker_url is supplied. Otherwise preview can't surface
    the crypto material — confirmed at the spec level too (§9)."""
    import json
    sender_id, priv = await _register_sender(app_client)
    # NOTE: deliberately skip the bootstrap-key register step.

    canonical = {
        "sender_id": sender_id,
        "ttl_seconds": 300,
        "metadata": {},
        "sender_broker_url": "https://broker.example.com/api/v1",
    }
    sig = priv.sign(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    )
    body = {**canonical, "signature": _b64(sig)}

    response = await app_client.post("/v1/pairing/initiate", json=body)
    assert response.status_code == 400, response.text
    assert "bootstrap key" in response.json()["detail"].lower()


@pytest.mark.asyncio
async def test_pairing_initiate_then_preview_round_trip(app_client: AsyncClient) -> None:
    """Codex 75 RED test #5: preview surfaces all four bootstrap fields
    together when initiate set sender_broker_url and a key is registered.
    """
    import json
    sender_id, priv = await _register_sender(app_client)
    x_priv = X25519PrivateKey.generate()
    x_pub = x_priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    # Step 1: register bootstrap key.
    r = await app_client.post(
        "/v1/senders/me/bootstrap-key",
        json=_build_bootstrap_register_body(sender_id, priv, x_pub),
    )
    assert r.status_code == 201, r.text

    # Step 2: initiate with sender_broker_url.
    canonical = {
        "sender_id": sender_id,
        "ttl_seconds": 300,
        "metadata": {},
        "sender_broker_url": "https://broker.example.com/api/v1",
    }
    sig = priv.sign(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    )
    init_response = await app_client.post(
        "/v1/pairing/initiate",
        json={**canonical, "signature": _b64(sig)},
    )
    assert init_response.status_code == 201, init_response.text
    init_data = init_response.json()
    assert init_data["sender_broker_url"] == "https://broker.example.com/api/v1"
    assert init_data["bootstrap_protocol_version"] == 1
    token = init_data["pairing_token"]

    # Step 3: preview surfaces all four bootstrap fields.
    preview = await app_client.get("/v1/pairing/preview", params={"token": token})
    assert preview.status_code == 200, preview.text
    pdata = preview.json()
    assert pdata["sender_broker_url"] == "https://broker.example.com/api/v1"
    assert pdata["bootstrap_key"] == _b64(x_pub)
    assert pdata["bootstrap_protocol_version"] == 1
    # bootstrap_key_signature is non-null and 88 chars of base64 (64 bytes raw).
    assert pdata["bootstrap_key_signature"] is not None
    assert len(base64.b64decode(pdata["bootstrap_key_signature"])) == 64


@pytest.mark.asyncio
async def test_pairing_preview_v1_path_unchanged(app_client: AsyncClient) -> None:
    """Regression guard: V1 senders (no sender_broker_url) get the
    same preview shape they got before Phase 5a-2 — bootstrap fields
    absent / null."""
    import json
    sender_id, priv = await _register_sender(app_client)

    canonical = {"sender_id": sender_id, "ttl_seconds": 300, "metadata": {}}
    sig = priv.sign(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8"),
    )
    init_response = await app_client.post(
        "/v1/pairing/initiate",
        json={**canonical, "signature": _b64(sig)},
    )
    assert init_response.status_code == 201
    init_data = init_response.json()
    # V1 senders: bootstrap_protocol_version and sender_broker_url are null.
    assert init_data["sender_broker_url"] is None
    assert init_data["bootstrap_protocol_version"] is None
    token = init_data["pairing_token"]

    preview = await app_client.get("/v1/pairing/preview", params={"token": token})
    assert preview.status_code == 200
    pdata = preview.json()
    assert pdata["sender_broker_url"] is None
    assert pdata["bootstrap_key"] is None
    assert pdata["bootstrap_key_signature"] is None
    assert pdata["bootstrap_protocol_version"] is None
