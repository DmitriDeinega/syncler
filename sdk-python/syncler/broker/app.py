"""FastAPI app factory for the V1.5 automated pairing broker.

Receives encrypted bootstrap envelopes from the Android device,
decrypts them against the sender's stored X25519 private key, and
writes ``(user_id, pairing_key)`` into the configured
[BrokerStorage]. The sender's ``Client.wait_for_pairing(...)`` polls
the same storage to learn when a pairing has completed.

Wire format: see ``docs/crypto-spec.md §9`` step 7. HTTP status
semantics:

- ``201 Created`` — first completion for this ``pairing_id``.
- ``200 OK`` — idempotent replay with the same ``(user_id,
  pairing_key)`` values.
- ``400 Bad Request`` — envelope shape is invalid (missing field,
  wrong type, base64 field decodes to wrong length).
- ``401 Unauthorized`` — decrypt failed (AEAD tag mismatch, AAD
  mismatch, exp outside ±5min window, bootstrap key id mismatch).
  Response body is opaque on purpose so an attacker can't probe
  which field caused the failure.
- ``409 Conflict`` — replay with DIFFERENT ``(user_id,
  pairing_key)`` values for the same ``pairing_id``. Indicates a
  replay attack or a sender bug.

Security framing (Phase 5a-2.1 / consultation 87):

The V1.5 broker ships with a SINGLE fixed ``sender_broker_url`` (the
URL the sender registered + signed at ``pairing/initiate``). The
trusted state per ``pairing_id`` is therefore byte-equal to the
configured URL. This satisfies the AAD-binding rule in
``docs/crypto-spec.md §9.3``.

What this DOES NOT satisfy: the spec also says the broker SHOULD
reject envelopes whose ``pairing_id`` is unknown to the sender. The
V1.5 broker accepts ANY ``pairing_id`` because [BrokerStorage] has no
pending-pairing registry. AEAD prevents tampering of an existing
envelope, but anyone who knows the public bootstrap key can mint a
cryptographically valid envelope for an arbitrary uuid4
``pairing_id`` (they pick the AAD; AEAD doesn't authenticate that
the ``pairing_id`` came from a real ``/initiate``).

Mitigations:

- ``pairing_id`` uses ``uuid4`` with ~122 bits of entropy, so an
  attacker can't enumerate real pending IDs.
- The optional ``rate_limiter`` hook is **mandatory in production**
  to defend against decrypt-spam DOS.
- V2 will add a pending-pairing registry as a first-class storage
  method, after which this comment block can shrink.

Public hosts using a CDN / load balancer in front of this app:
terminate TLS at the LB, keep the broker on a private network,
forward client IP via X-Forwarded-For so the rate limiter can key
on it. ``InMemoryBrokerStorage`` is single-process only — production
multi-worker deployments need Redis or Postgres.
"""

from __future__ import annotations

import base64
import logging
from collections.abc import Awaitable, Callable
from typing import Any

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from fastapi import FastAPI, HTTPException, Request, Response

from ..bootstrap import (
    BootstrapDecryptError,
    decrypt_bootstrap_envelope,
)
from ..broker_storage import (
    BrokerEntry,
    BrokerStorage,
    BrokerStorageConflictError,
)

_log = logging.getLogger(__name__)


# Wire envelope shape — see docs/crypto-spec.md §9 step 7.
_REQUIRED_FIELDS: tuple[str, ...] = (
    "protocol_version",
    "pairing_id",
    "sender_id",
    "bootstrap_key_id",
    "exp",
    "ephemeral_pubkey",
    "nonce",
    "ciphertext",
)
_EXPECTED_EPHEMERAL_PUB_BYTES = 32
_EXPECTED_NONCE_BYTES = 12
_EXPECTED_KEY_ID_BYTES = 16
# AES-GCM tag is 16 bytes; ciphertext = plaintext + 16. Empty
# plaintext would be 16 bytes; require strictly more so we never
# accept a zero-plaintext envelope (the protocol's plaintext is
# {"user_id":"...","pairing_key":"..."}, never empty).
_MIN_CIPHERTEXT_BYTES = 17


def make_app(
    *,
    bootstrap_private_key: X25519PrivateKey,
    bootstrap_public_key_raw: bytes,
    sender_broker_url: str,
    storage: BrokerStorage,
    rate_limiter: Callable[[Request], Awaitable[None]] | None = None,
) -> FastAPI:
    """Build a FastAPI app that handles one bootstrap envelope POST.

    The app mounts a single ``POST /`` endpoint. The full broker URL
    (``sender_broker_url``) is the public form the device POSTs to —
    e.g. ``https://sender.example.com/syncler/bootstrap`` — and the
    caller decides path prefix at uvicorn / reverse-proxy layer.

    Args:
        bootstrap_private_key: The sender's X25519 private key. Loads
            via ``syncler.bootstrap.load_x25519_private_key_from_raw``
            from however the sender persists it (file, KMS, secrets
            manager).
        bootstrap_public_key_raw: The matching 32-byte raw X25519
            public key, exactly as registered via
            ``Client.register_bootstrap_key``. The broker uses this
            to compute the expected ``bootstrap_key_id`` and to
            mirror the salt the device used.
        sender_broker_url: The single trusted broker URL for this
            sender. MUST be byte-equal to the URL the sender supplied
            in ``Client.create_pairing_qr(sender_broker_url=...)``.
            Used as the trusted-state half of the AAD reconstruction.
        storage: A [BrokerStorage] (in-memory for dev; Redis / Postgres
            for production multi-worker).
        rate_limiter: Optional async callable invoked per request
            BEFORE body parsing. Raise ``HTTPException`` to short-
            circuit with a chosen status (e.g. 429). Any non-
            ``HTTPException`` raise propagates as a 500 via FastAPI's
            default exception handler. **Strongly recommended in
            production** to defend against decrypt-spam DOS.

    Returns:
        A configured ``FastAPI`` instance. Caller hands it to
        uvicorn / gunicorn / ASGI runner of choice.
    """
    if len(bootstrap_public_key_raw) != 32:
        raise ValueError("bootstrap_public_key_raw must be exactly 32 bytes")

    app = FastAPI(title="syncler-broker", version="0.1.0")

    @app.post("/", status_code=201)
    async def handle_bootstrap(  # noqa: D401, ANN201
        request: Request,
        response: Response,
    ) -> dict[str, Any]:
        if rate_limiter is not None:
            await rate_limiter(request)

        try:
            body = await request.json()
        except Exception as exc:
            raise HTTPException(status_code=400, detail="invalid JSON body") from exc

        if not isinstance(body, dict):
            raise HTTPException(status_code=400, detail="body must be a JSON object")

        missing = [k for k in _REQUIRED_FIELDS if k not in body]
        if missing:
            raise HTTPException(
                status_code=400,
                detail=f"missing required fields: {sorted(missing)}",
            )
        # Strict 8-field envelope: reject unknown keys (consult 88).
        extras = set(body) - set(_REQUIRED_FIELDS)
        if extras:
            raise HTTPException(
                status_code=400,
                detail=f"unexpected fields: {sorted(extras)}",
            )

        # Type checks.
        if not isinstance(body["protocol_version"], int) or body["protocol_version"] != 1:
            raise HTTPException(status_code=400, detail="protocol_version must be 1")
        for field in ("pairing_id", "sender_id", "bootstrap_key_id", "exp",
                      "ephemeral_pubkey", "nonce", "ciphertext"):
            if not isinstance(body[field], str):
                raise HTTPException(
                    status_code=400,
                    detail=f"{field} must be a string",
                )

        # Base64 length validation BEFORE decrypt (consult 80/87).
        try:
            ephemeral_pubkey_raw = base64.b64decode(
                body["ephemeral_pubkey"], validate=True,
            )
            nonce_raw = base64.b64decode(body["nonce"], validate=True)
            ciphertext_raw = base64.b64decode(body["ciphertext"], validate=True)
            bootstrap_key_id_raw = base64.b64decode(
                body["bootstrap_key_id"], validate=True,
            )
        except Exception as exc:
            raise HTTPException(
                status_code=400,
                detail="base64 decode failed for one or more envelope fields",
            ) from exc

        if len(ephemeral_pubkey_raw) != _EXPECTED_EPHEMERAL_PUB_BYTES:
            raise HTTPException(
                status_code=400,
                detail=f"ephemeral_pubkey must decode to {_EXPECTED_EPHEMERAL_PUB_BYTES} bytes",
            )
        if len(nonce_raw) != _EXPECTED_NONCE_BYTES:
            raise HTTPException(
                status_code=400,
                detail=f"nonce must decode to {_EXPECTED_NONCE_BYTES} bytes",
            )
        if len(bootstrap_key_id_raw) != _EXPECTED_KEY_ID_BYTES:
            raise HTTPException(
                status_code=400,
                detail=f"bootstrap_key_id must decode to {_EXPECTED_KEY_ID_BYTES} bytes",
            )
        if len(ciphertext_raw) < _MIN_CIPHERTEXT_BYTES:
            raise HTTPException(
                status_code=400,
                detail=f"ciphertext must decode to at least {_MIN_CIPHERTEXT_BYTES} bytes",
            )

        try:
            user_id, pairing_key = decrypt_bootstrap_envelope(
                sender_bootstrap_priv=bootstrap_private_key,
                sender_bootstrap_pub=bootstrap_public_key_raw,
                pairing_id=body["pairing_id"],
                sender_id=body["sender_id"],
                sender_broker_url_from_trusted_state=sender_broker_url,
                ephemeral_pubkey=ephemeral_pubkey_raw,
                nonce=nonce_raw,
                ciphertext=ciphertext_raw,
                exp_iso=body["exp"],
                bootstrap_key_id_b64_from_envelope=body["bootstrap_key_id"],
            )
        except BootstrapDecryptError as exc:
            # OPAQUE 401. We log the cause server-side so the sender's
            # ops team can debug, but the response body carries no
            # detail — leaking which field mismatched would help an
            # attacker probe the envelope (consult 80/82 spec).
            _log.warning("bootstrap decrypt rejected: %s", exc)
            raise HTTPException(status_code=401, detail="bootstrap decrypt failed")

        try:
            was_first = storage.complete(
                body["pairing_id"],
                BrokerEntry(user_id=user_id, pairing_key=pairing_key),
            )
        except BrokerStorageConflictError as exc:
            _log.warning("bootstrap CAS conflict: %s", exc)
            raise HTTPException(status_code=409, detail="pairing_id replay with different values")

        # Status: 201 first completion, 200 idempotent replay.
        if not was_first:
            response.status_code = 200
        return {"status": "ok", "pairing_id": body["pairing_id"]}

    return app


__all__ = ["make_app"]
