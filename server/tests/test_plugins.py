"""Tests for /v1/plugins/* publish + latest + revoke."""

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
    plugin_id: uuid.UUID,
    sender_id: uuid.UUID,
    version: str,
    manifest_hash: str,
    bundle_hash: str,
    signature: str,
    signed_bundle_url: str,
    capabilities: list[str],
    endpoints: list[str],
) -> str:
    envelope = {
        "plugin_id": str(plugin_id),
        "sender_id": str(sender_id),
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


@pytest.mark.asyncio
async def test_publish_and_fetch_latest(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = uuid.uuid4()
    manifest_hash = _b64(b"M" * 32)
    bundle_hash = _b64(b"B" * 32)
    signature = _b64(b"S" * 64)

    body_v1 = {
        "plugin_id": str(plugin_id),
        "sender_id": str(sender_id),
        "version": "1.0.0",
        "manifest_hash": manifest_hash,
        "bundle_hash": bundle_hash,
        "signature": signature,
        "signed_bundle_url": "https://lottery.app/plugin.js",
        "capabilities": ["network"],
        "endpoints": ["https://lottery.app/api/*"],
    }
    body_v1["sender_signature"] = _sign_publish(private_key, **{k: v for k, v in body_v1.items() if k != "sender_signature"} | {"plugin_id": plugin_id, "sender_id": sender_id})

    publish_v1 = await app_client.post("/v1/plugins/publish", json=body_v1)
    assert publish_v1.status_code == 201, publish_v1.text

    latest = await app_client.get(f"/v1/plugins/{plugin_id}/latest")
    assert latest.status_code == 200, latest.text
    assert latest.json()["version"] == "1.0.0"


@pytest.mark.asyncio
async def test_publish_rejects_version_regression(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    plugin_id = uuid.uuid4()
    manifest_hash = _b64(b"M" * 32)
    bundle_hash = _b64(b"B" * 32)
    signature = _b64(b"S" * 64)

    def body_for(version: str) -> dict:
        b = {
            "plugin_id": str(plugin_id),
            "sender_id": str(sender_id),
            "version": version,
            "manifest_hash": manifest_hash,
            "bundle_hash": bundle_hash,
            "signature": signature,
            "signed_bundle_url": "https://lottery.app/plugin.js",
            "capabilities": ["network"],
            "endpoints": ["https://lottery.app/api/*"],
        }
        b["sender_signature"] = _sign_publish(
            private_key,
            plugin_id=plugin_id,
            sender_id=sender_id,
            version=version,
            manifest_hash=manifest_hash,
            bundle_hash=bundle_hash,
            signature=signature,
            signed_bundle_url=b["signed_bundle_url"],
            capabilities=b["capabilities"],
            endpoints=b["endpoints"],
        )
        return b

    await app_client.post("/v1/plugins/publish", json=body_for("1.0.0"))
    regress = await app_client.post("/v1/plugins/publish", json=body_for("0.9.0"))
    assert regress.status_code == 409


@pytest.mark.asyncio
async def test_publish_rejects_invalid_signature(app_client: AsyncClient) -> None:
    sender_id, _ = await _register_sender(app_client)
    plugin_id = uuid.uuid4()
    body = {
        "plugin_id": str(plugin_id),
        "sender_id": str(sender_id),
        "version": "1.0.0",
        "manifest_hash": _b64(b"M" * 32),
        "bundle_hash": _b64(b"B" * 32),
        "signature": _b64(b"S" * 64),
        "signed_bundle_url": "https://lottery.app/plugin.js",
        "capabilities": [],
        "endpoints": [],
        "sender_signature": _b64(b"\xff" * 64),
    }
    response = await app_client.post("/v1/plugins/publish", json=body)
    assert response.status_code == 401
