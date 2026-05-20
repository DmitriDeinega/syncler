"""Tests for /v1/plugins/* publish + latest + revoke (M8.1)."""

from __future__ import annotations

import base64
import json
import uuid

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _register_sender(client: AsyncClient) -> tuple[uuid.UUID, Ed25519PrivateKey]:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    response = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": "Lottery", "contact": "ops@lottery.app"},
    )
    return uuid.UUID(response.json()["sender_id"]), private_key


def _sign_publish(
    private_key: Ed25519PrivateKey,
    *,
    sender_id: uuid.UUID,
    plugin_identifier: str,
    version: str,
    manifest_hash: str,
    bundle_hash: str,
    signature: str,
    signed_bundle_url: str,
    capabilities: list[str],
    endpoints: list[str],
) -> str:
    envelope = {
        "sender_id": str(sender_id),
        "plugin_identifier": plugin_identifier,
        "version": version,
        "manifest_hash": manifest_hash,
        "bundle_hash": bundle_hash,
        "signature": signature,
        "signed_bundle_url": signed_bundle_url,
        "capabilities": capabilities,
        "endpoints": endpoints,
    }
    canonical = json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _b64(private_key.sign(canonical))


def _sign_revoke(private_key: Ed25519PrivateKey, *, sender_id: uuid.UUID, plugin_row_id: uuid.UUID) -> str:
    envelope = {"sender_id": str(sender_id), "plugin_row_id": str(plugin_row_id)}
    canonical = json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _b64(private_key.sign(canonical))


def _body(
    sender_id: uuid.UUID,
    private_key: Ed25519PrivateKey,
    *,
    version: str,
    plugin_identifier: str = "com.lottery.app",
) -> dict:
    manifest_hash = _b64(b"M" * 32)
    bundle_hash = _b64(b"B" * 32)
    signature = _b64(b"S" * 64)
    capabilities = ["network"]
    endpoints = ["https://lottery.app/api/*"]
    url = "https://lottery.app/plugin.js"
    return {
        "sender_id": str(sender_id),
        "plugin_identifier": plugin_identifier,
        "version": version,
        "manifest_hash": manifest_hash,
        "bundle_hash": bundle_hash,
        "signature": signature,
        "signed_bundle_url": url,
        "capabilities": capabilities,
        "endpoints": endpoints,
        "sender_signature": _sign_publish(
            private_key,
            sender_id=sender_id,
            plugin_identifier=plugin_identifier,
            version=version,
            manifest_hash=manifest_hash,
            bundle_hash=bundle_hash,
            signature=signature,
            signed_bundle_url=url,
            capabilities=capabilities,
            endpoints=endpoints,
        ),
    }


@pytest.mark.asyncio
async def test_publish_then_upgrade(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)

    publish_v1 = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    assert publish_v1.status_code == 201, publish_v1.text

    # M8.1 critical fix: upgrading must work (v1.0.1 after v1.0.0).
    publish_v2 = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.1")
    )
    assert publish_v2.status_code == 201, publish_v2.text

    latest = await app_client.get(f"/v1/plugins/{sender_id}/com.lottery.app/latest")
    assert latest.status_code == 200, latest.text
    assert latest.json()["version"] == "1.0.1"


@pytest.mark.asyncio
async def test_publish_rejects_version_regression(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    regress = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="0.9.0")
    )
    assert regress.status_code == 409


@pytest.mark.asyncio
async def test_publish_rejects_invalid_signature(app_client: AsyncClient) -> None:
    sender_id, _ = await _register_sender(app_client)
    body = _body(sender_id, Ed25519PrivateKey.generate(), version="1.0.0")  # wrong key
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_revoke_requires_sender_signature(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    publish = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    plugin_row_id = uuid.UUID(publish.json()["plugin_row_id"])

    # Unauthed call without sender_signature is rejected as 4xx (was a
    # CRITICAL pre-M8.1: anyone with the UUID could revoke).
    naked = await app_client.post(
        "/v1/plugins/revoke",
        json={"sender_id": str(sender_id), "plugin_row_id": str(plugin_row_id)},
    )
    assert naked.status_code in (400, 422)

    # Authenticated revoke succeeds.
    sig = _sign_revoke(private_key, sender_id=sender_id, plugin_row_id=plugin_row_id)
    authed = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig,
        },
    )
    assert authed.status_code == 204


@pytest.mark.asyncio
async def test_revoke_rejects_foreign_sender(app_client: AsyncClient) -> None:
    sender_a, key_a = await _register_sender(app_client)
    sender_b, key_b = await _register_sender(app_client)
    publish = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_a, key_a, version="1.0.0")
    )
    plugin_row_id = uuid.UUID(publish.json()["plugin_row_id"])

    # Sender B signs a valid revoke envelope for sender A's plugin row.
    # Sig valid against B's key but row belongs to A → 404 (no leak).
    sig = _sign_revoke(key_b, sender_id=sender_b, plugin_row_id=plugin_row_id)
    response = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_b),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig,
        },
    )
    assert response.status_code == 404
