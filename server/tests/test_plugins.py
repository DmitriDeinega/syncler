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


def _sign_revoke(
    private_key: Ed25519PrivateKey,
    *,
    sender_id: uuid.UUID,
    plugin_row_id: uuid.UUID,
    reason: str | None = None,
) -> str:
    envelope: dict = {"sender_id": str(sender_id), "plugin_row_id": str(plugin_row_id)}
    if reason is not None:
        envelope["reason"] = reason
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
async def test_publish_with_only_body_sender_id_succeeds(app_client: AsyncClient) -> None:
    """Regression: the Python SDK puts sender_id in the JSON body only — no
    X-Sender-ID header. Pre-fix, the publish route's rate-limit dependency
    borrowed the `pairing_initiate` actor, which required header/state to be
    set and returned 400 "missing rate limit actor" before the handler ran.

    The fix moved the per-sender bucket to *after* signature verification (so a
    spoofer can't inflate someone else's bucket via the body), and added a
    `plugin_publish` actor that reads sender_id from state. This test ensures
    SDK-shape calls — no header, sender_id only in body — succeed.
    """
    sender_id, private_key = await _register_sender(app_client)

    response = await app_client.post(
        "/v1/plugins/publish",
        json=_body(sender_id, private_key, version="1.0.0"),
        # Explicitly no X-Sender-ID header — mirrors what the Python SDK sends.
    )
    assert response.status_code == 201, response.text

    # And the /latest lookup that the plugin host hits — no X-Device-ID header
    # — should also succeed (was 400 with the same root cause).
    latest = await app_client.get(f"/v1/plugins/{sender_id}/com.lottery.app/latest")
    assert latest.status_code == 200, latest.text


@pytest.mark.asyncio
async def test_revoke_records_and_promotes_reason(app_client: AsyncClient) -> None:
    """M11.4: revoke endpoint accepts an optional classified ``reason`` so
    devices can render differentiated UX. The reason is persisted with the
    row AND is monotonic — a higher-severity reason can overwrite a lower
    one (so ``superseded`` -> ``compromised`` works when a vuln surfaces
    in an already-superseded version), but the reverse silently no-ops.
    The ``revoked_at`` timestamp is also stable — re-revoking does NOT
    push the original revocation time forward.
    """
    sender_id, private_key = await _register_sender(app_client)
    publish = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    plugin_row_id = uuid.UUID(publish.json()["plugin_row_id"])

    # First revoke: superseded. Row gets revoked_at + reason persisted.
    sig_super = _sign_revoke(
        private_key, sender_id=sender_id, plugin_row_id=plugin_row_id, reason="superseded",
    )
    r1 = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig_super,
            "reason": "superseded",
        },
    )
    assert r1.status_code == 204

    by_id_1 = await app_client.get(f"/v1/plugins/by-id/{plugin_row_id}")
    assert by_id_1.status_code == 200
    body1 = by_id_1.json()
    assert body1["revocation_reason"] == "superseded"
    revoked_at_first = body1["revoked_at"]
    assert revoked_at_first is not None

    # Promote to compromised. Reason MUST change; revoked_at MUST be pinned.
    sig_compromised = _sign_revoke(
        private_key, sender_id=sender_id, plugin_row_id=plugin_row_id, reason="compromised",
    )
    r2 = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig_compromised,
            "reason": "compromised",
        },
    )
    assert r2.status_code == 204

    by_id_2 = await app_client.get(f"/v1/plugins/by-id/{plugin_row_id}")
    body2 = by_id_2.json()
    assert body2["revocation_reason"] == "compromised"
    assert body2["revoked_at"] == revoked_at_first, "revoked_at must be pinned across re-revokes"

    # Downgrade attempt: re-revoke with superseded MUST be a silent no-op.
    # A leaked sender key cannot rewrite a security revoke down to a
    # harmless one.
    sig_downgrade = _sign_revoke(
        private_key, sender_id=sender_id, plugin_row_id=plugin_row_id, reason="superseded",
    )
    r3 = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig_downgrade,
            "reason": "superseded",
        },
    )
    assert r3.status_code == 204

    by_id_3 = await app_client.get(f"/v1/plugins/by-id/{plugin_row_id}")
    assert by_id_3.json()["revocation_reason"] == "compromised", (
        "downgrade attempt must be silently ignored"
    )


@pytest.mark.asyncio
async def test_by_id_returns_revoked_with_reason(app_client: AsyncClient) -> None:
    """M11.4: the /v1/plugins/by-id/{row_id} endpoint returns historical
    manifests (active OR revoked) so the device can render an old message
    against the EXACT bundle it was originally validated for. Revoked
    rows surface ``revoked_at`` and ``revocation_reason``."""
    sender_id, private_key = await _register_sender(app_client)
    publish = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    plugin_row_id = uuid.UUID(publish.json()["plugin_row_id"])

    # Active row: by-id returns manifest with revocation fields null.
    active = await app_client.get(f"/v1/plugins/by-id/{plugin_row_id}")
    assert active.status_code == 200
    assert active.json()["revoked_at"] is None
    assert active.json()["revocation_reason"] is None

    # Revoke + re-fetch.
    sig = _sign_revoke(
        private_key, sender_id=sender_id, plugin_row_id=plugin_row_id, reason="sender_disabled",
    )
    await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig,
            "reason": "sender_disabled",
        },
    )

    revoked = await app_client.get(f"/v1/plugins/by-id/{plugin_row_id}")
    assert revoked.status_code == 200
    body = revoked.json()
    assert body["revoked_at"] is not None
    assert body["revocation_reason"] == "sender_disabled"
    assert body["plugin_row_id"] == str(plugin_row_id)
    assert "signed_bundle_url" in body
    assert "bundle_hash" in body

    # /latest still 404s — it filters revoked rows out by design.
    latest = await app_client.get(f"/v1/plugins/{sender_id}/com.lottery.app/latest")
    assert latest.status_code == 404


@pytest.mark.asyncio
async def test_by_id_404_for_unknown_row(app_client: AsyncClient) -> None:
    unknown = uuid.uuid4()
    resp = await app_client.get(f"/v1/plugins/by-id/{unknown}")
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_revoke_rejects_invalid_reason(app_client: AsyncClient) -> None:
    sender_id, private_key = await _register_sender(app_client)
    publish = await app_client.post(
        "/v1/plugins/publish", json=_body(sender_id, private_key, version="1.0.0")
    )
    plugin_row_id = uuid.UUID(publish.json()["plugin_row_id"])
    sig = _sign_revoke(
        private_key, sender_id=sender_id, plugin_row_id=plugin_row_id, reason="totally_made_up",
    )
    resp = await app_client.post(
        "/v1/plugins/revoke",
        json={
            "sender_id": str(sender_id),
            "plugin_row_id": str(plugin_row_id),
            "sender_signature": sig,
            "reason": "totally_made_up",
        },
    )
    # Pydantic field_validator wraps non-enum values as 400 via our shared
    # validation_exception_handler.
    assert resp.status_code == 400, resp.text


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
