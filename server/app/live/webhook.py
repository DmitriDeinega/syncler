"""V3 #14 step 5 — sender-webhook forwarder.

When a device sends a frame on a live channel, the server
forwards it to the sender's registered webhook URL with an
Ed25519 signature so the sender can verify provenance.

Request shape (POST):

```json
{
  "plugin_row_id": "...",
  "channel": "...",
  "envelope": "<opaque V2-style envelope sealed for sender>",
  "received_at": 1234567890,
  "device_pseudonym": "<opaque>"
}
```

Headers:

- `X-Syncler-Signature: <base64 Ed25519>` over the canonical
  JSON body bytes.
- `Content-Type: application/json`.

device_pseudonym = HMAC(server_signing_seed, device_id ||
sender_id). Stable per (device, sender) pair without leaking
the raw device_id.

Delivery:
- 5s timeout.
- 3 retries with exponential backoff (1s / 4s / 16s) on
  network errors or 5xx responses.
- 4xx is terminal (sender misconfigured); single attempt.

V0.1: webhook URL discovery uses the plugin's
`live_inbound_url` field added in step 6. Until step 6 lands
the URL is None and forwarding is a no-op.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import time
import uuid
from dataclasses import dataclass

import httpx
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

from app.config import get_settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class WebhookDeliveryResult:
    delivered: bool
    final_status: int | None  # last HTTP status seen, or None if all attempts errored
    attempts: int


_SIGNING_KEY: Ed25519PrivateKey | None = None
_SIGNING_KEY_LOADED: bool = False


def _load_signing_key() -> Ed25519PrivateKey | None:
    """Cache the server signing key parsed from the env seed.
    Returns None if the seed isn't configured."""
    global _SIGNING_KEY, _SIGNING_KEY_LOADED
    if _SIGNING_KEY_LOADED:
        return _SIGNING_KEY
    _SIGNING_KEY_LOADED = True
    seed_b64 = get_settings().server_signing_seed_b64
    if not seed_b64:
        return None
    try:
        seed = base64.b64decode(seed_b64)
        if len(seed) != 32:
            logger.warning(
                "SERVER_SIGNING_SEED_B64 length %d not 32; webhook signing disabled",
                len(seed),
            )
            return None
        _SIGNING_KEY = Ed25519PrivateKey.from_private_bytes(seed)
        return _SIGNING_KEY
    except Exception:
        logger.exception("failed to parse SERVER_SIGNING_SEED_B64")
        return None


def _reset_for_test() -> None:
    """pytest hook — drop the cached signing key between tests
    that monkey-patch the env."""
    global _SIGNING_KEY, _SIGNING_KEY_LOADED
    _SIGNING_KEY = None
    _SIGNING_KEY_LOADED = False


def device_pseudonym(device_id: uuid.UUID, sender_id: uuid.UUID) -> str:
    """Stable per-(device, sender) pseudonym; HMAC-SHA256 over
    the server signing seed. Senders use it as the identifier
    for this specific device without learning the raw UUID.

    Falls back to a constant value if no signing seed is
    configured (dev only — production MUST set the seed)."""
    seed_b64 = get_settings().server_signing_seed_b64
    if not seed_b64:
        return "dev-no-pseudonym"
    try:
        seed = base64.b64decode(seed_b64)
    except Exception:
        return "dev-no-pseudonym"
    mac = hmac.new(
        seed,
        msg=f"{device_id}|{sender_id}".encode("utf-8"),
        digestmod=hashlib.sha256,
    )
    return base64.urlsafe_b64encode(mac.digest()).decode("ascii")[:32]


def _sign(body_bytes: bytes) -> str | None:
    """Returns the base64-encoded Ed25519 signature, or None if
    no signing key is available."""
    key = _load_signing_key()
    if key is None:
        return None
    sig = key.sign(body_bytes)
    return base64.b64encode(sig).decode("ascii")


async def forward_to_sender(
    *,
    sender_webhook_url: str,
    plugin_row_id: str,
    channel: str,
    envelope_json: str,
    device_id: uuid.UUID,
    sender_id: uuid.UUID,
) -> WebhookDeliveryResult:
    """Forward a device-originated live frame to the sender's
    webhook. Retries with backoff on network / 5xx errors.
    Returns a structured result the caller can ack/error with."""
    body = {
        "plugin_row_id": plugin_row_id,
        "channel": channel,
        "envelope": envelope_json,
        "received_at": int(time.time()),
        "device_pseudonym": device_pseudonym(device_id, sender_id),
    }
    body_bytes = json.dumps(body, separators=(",", ":")).encode("utf-8")
    signature = _sign(body_bytes)
    headers = {"Content-Type": "application/json"}
    if signature is not None:
        headers["X-Syncler-Signature"] = signature

    backoffs = (1.0, 4.0, 16.0)
    final_status: int | None = None
    last_attempt = 0
    async with httpx.AsyncClient(timeout=5.0) as client:
        for attempt in range(1 + len(backoffs)):
            last_attempt = attempt + 1
            try:
                resp = await client.post(
                    sender_webhook_url, content=body_bytes, headers=headers
                )
                final_status = resp.status_code
                if 200 <= resp.status_code < 300:
                    return WebhookDeliveryResult(
                        delivered=True,
                        final_status=resp.status_code,
                        attempts=last_attempt,
                    )
                # 4xx is terminal — sender misconfigured.
                if 400 <= resp.status_code < 500:
                    return WebhookDeliveryResult(
                        delivered=False,
                        final_status=resp.status_code,
                        attempts=last_attempt,
                    )
                # 5xx falls through to retry.
            except (httpx.RequestError, asyncio.TimeoutError):
                final_status = None
            if attempt < len(backoffs):
                await asyncio.sleep(backoffs[attempt])
    return WebhookDeliveryResult(
        delivered=False,
        final_status=final_status,
        attempts=last_attempt,
    )


def server_signing_public_key_b64() -> str | None:
    """V0.1 helper for the admin/well-known endpoint senders
    will use to fetch the public key. Returns None if no
    signing key is configured."""
    key = _load_signing_key()
    if key is None:
        return None
    pub = key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return base64.b64encode(pub).decode("ascii")
