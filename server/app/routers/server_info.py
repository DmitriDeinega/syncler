"""Server discovery + metadata endpoints.

Triad 160 codex/gemini consensus: items partners need to
discover about the deployment (version, public keys,
capabilities) belong here, NOT nested under feature
routers like `live.py`. New endpoints (build version,
signing key fingerprint, advertised feature flags) land
here.

All endpoints in this module are intentionally
unauthenticated when the data is public-by-design —
e.g. the webhook public key partners use to verify
`X-Syncler-Signature` on live-channel forwarder POSTs.
"""

from __future__ import annotations

import base64

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, ConfigDict

from app.config import get_settings

router = APIRouter(tags=["server"])


class WebhookPublicKeyResponse(BaseModel):
    """V3 #14 webhook signing key discovery (triad 160).

    Partners that registered a `live_inbound_url` use this
    to verify the server's `X-Syncler-Signature` on every
    delivered webhook. Pin the returned key at startup; the
    server does not rotate it casually (a rotation requires
    a coordinated config change + partner re-fetch).
    """

    model_config = ConfigDict(extra="forbid")

    public_key_base64: str
    algorithm: str = "ed25519"


@router.get(
    "/webhook-public-key",
    response_model=WebhookPublicKeyResponse,
)
async def webhook_public_key() -> WebhookPublicKeyResponse:
    """Returns the Ed25519 public key for the live-channel
    webhook forwarder. Unauthenticated by design — the key
    is public information; hiding it only makes integration
    worse.

    503 (Service Unavailable) when the server's signing seed
    is not configured. Production deployments MUST configure
    `SERVER_SIGNING_SEED_B64`; codex 160 preferred 503 over
    404 because the missing-seed case is a server
    misconfiguration, not a missing resource.
    """
    seed_b64 = get_settings().server_signing_seed_b64
    if not seed_b64:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="server webhook signing seed not configured",
        )
    try:
        seed = base64.b64decode(seed_b64)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="server webhook signing seed is malformed",
        ) from exc
    if len(seed) != 32:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="server webhook signing seed is not 32 bytes",
        )
    priv = Ed25519PrivateKey.from_private_bytes(seed)
    pub = priv.public_key()
    raw = pub.public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return WebhookPublicKeyResponse(
        public_key_base64=base64.b64encode(raw).decode("ascii"),
    )
