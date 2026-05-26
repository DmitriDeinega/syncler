"""V3 #14 / triad 160 — webhook-public-key endpoint tests.

Verifies the new `GET /v1/server/webhook-public-key`:
- Returns the Ed25519 public key matching the configured
  signing seed.
- Returns 503 when the seed is missing or malformed.
- Is unauthenticated (no Authorization header required).

In-process only; no PG or live socket required.
"""

from __future__ import annotations

import base64

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient

from app.config import get_settings


pytestmark = pytest.mark.asyncio


@pytest.fixture(autouse=True)
def reset_settings():
    """Clear the lru-cached settings between tests so env
    monkeypatches take effect."""
    yield
    get_settings.cache_clear()


def _seeded_app(seed_b64: str | None, monkeypatch: pytest.MonkeyPatch):
    if seed_b64 is None:
        monkeypatch.delenv("SERVER_SIGNING_SEED_B64", raising=False)
    else:
        monkeypatch.setenv("SERVER_SIGNING_SEED_B64", seed_b64)
    monkeypatch.setenv("JWT_SECRET", "test-secret-with-at-least-32-bytes")
    get_settings.cache_clear()
    # Import after env is set so the cached Settings picks up
    # the seed.
    from app.main import app as _app
    return _app


async def test_webhook_public_key_returns_matching_pubkey(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Generate a known seed; assert the endpoint returns the
    # derived public key.
    private = Ed25519PrivateKey.generate()
    seed_bytes = private.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    seed_b64 = base64.b64encode(seed_bytes).decode("ascii")
    expected_pub_b64 = base64.b64encode(
        private.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )
    ).decode("ascii")

    app = _seeded_app(seed_b64, monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 200
    body = resp.json()
    assert body["algorithm"] == "ed25519"
    assert body["public_key_base64"] == expected_pub_b64


async def test_webhook_public_key_503_when_seed_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    app = _seeded_app(None, monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 503


async def test_webhook_public_key_503_when_seed_malformed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Not base64.
    app = _seeded_app("not-valid-base64===", monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 503


async def test_webhook_public_key_503_when_seed_wrong_length(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Valid base64 but not 32 bytes after decode.
    short_seed = base64.b64encode(b"too-short").decode("ascii")
    app = _seeded_app(short_seed, monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 503


async def test_webhook_public_key_rejects_polluted_base64(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Triad 161 codex hardening: with validate=True, base64
    # decode rejects strings containing non-alphabet characters
    # (here, whitespace mixed in). Without validate=True,
    # b64decode would silently drop the whitespace and could
    # accept a polluted 32-byte result.
    private = Ed25519PrivateKey.generate()
    seed_bytes = private.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    clean_b64 = base64.b64encode(seed_bytes).decode("ascii")
    # Inject whitespace mid-string — invalid base64 alphabet.
    polluted = clean_b64[:5] + "  " + clean_b64[5:]
    app = _seeded_app(polluted, monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 503


async def test_webhook_public_key_does_not_require_auth(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Sanity: no Authorization header should still 200 when
    # the seed is configured. The endpoint is intentionally
    # public.
    private = Ed25519PrivateKey.generate()
    seed_bytes = private.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    seed_b64 = base64.b64encode(seed_bytes).decode("ascii")
    app = _seeded_app(seed_b64, monkeypatch)
    with TestClient(app) as client:
        resp = client.get("/v1/server/webhook-public-key")
    assert resp.status_code == 200
