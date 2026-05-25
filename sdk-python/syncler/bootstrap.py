"""V1.5 automated pairing — bootstrap envelope decrypt helpers.

Phase 5a-2 (`.triad/70-phase5-agreement.md`). Spec:
`docs/crypto-spec.md §9`. The sender's broker handler receives an
encrypted envelope from the device, looks up its trusted
`sender_broker_url` for `pairing_id`, reconstructs the canonical AAD,
and AES-GCM-decrypts to learn `(user_id, pairing_key)`.

This module ships the building blocks. A ready-to-mount FastAPI app
sits behind an optional extra (`syncler[broker]`), not here.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import UTC, datetime
from typing import Any

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)

# Domain-separated HKDF info, matching `docs/crypto-spec.md §9.2`.
_HKDF_INFO_BOOTSTRAP_AEAD = b"syncler-v1-bootstrap-aead"
# Hard cap on broker clock-skew tolerance. Spec: §9 — broker accepts
# envelopes whose `exp` is within ±5 minutes of broker wall time.
BROKER_CLOCK_SKEW_SECONDS = 5 * 60


class BootstrapDecryptError(Exception):
    """Raised when the broker can't decrypt the envelope. Covers bad
    sig, expired/clock-skewed exp, AAD mismatch, AEAD tag failure."""


class BootstrapEnvelopeReplayError(BootstrapDecryptError):
    """Raised when a bootstrap envelope was already consumed (CAS
    second-write with the same pairing_id and different contents)."""


def _canon_uuid(value: Any) -> str:
    """str(uuid.UUID(value)) — lowercase no-brace canonical form."""
    return str(uuid.UUID(str(value)))


def assemble_bootstrap_aad(
    *,
    bootstrap_key_id_b64: str,
    exp_iso: str,
    pairing_id: str,
    sender_broker_url: str,
    sender_id: str,
) -> bytes:
    """Canonical AAD bytes for the V1.5 bootstrap envelope.

    Spec: §9.3. Keys are sorted alphabetically; `protocol_version` is
    a JSON integer literal (no quotes); `exp_iso` must use the `Z`
    suffix. ``sender_broker_url`` MUST be sourced from the broker's
    trusted storage for ``pairing_id`` (NOT echoed from any client-
    supplied envelope field) — otherwise a syncler-server substitution
    attack is possible.
    """
    return json.dumps(
        {
            "bootstrap_key_id": bootstrap_key_id_b64,
            "exp": exp_iso,
            "pairing_id": _canon_uuid(pairing_id),
            "protocol_version": 1,
            "sender_broker_url": sender_broker_url,
            "sender_id": _canon_uuid(sender_id),
        },
        sort_keys=True,
        ensure_ascii=True,
        separators=(",", ":"),
    ).encode("utf-8")


def bootstrap_key_id(bootstrap_pub_raw: bytes) -> bytes:
    """SHA-256(bootstrap_pub)[:16] — the broker uses this as a stable
    identifier for the key version it should decrypt with. Lets the
    sender rotate the key without affecting in-flight envelopes."""
    if len(bootstrap_pub_raw) != 32:
        raise ValueError("bootstrap_pub_raw must be 32 bytes")
    return hashlib.sha256(bootstrap_pub_raw).digest()[:16]


def decrypt_bootstrap_envelope(
    *,
    sender_bootstrap_priv: X25519PrivateKey,
    sender_bootstrap_pub: bytes,
    pairing_id: str,
    sender_id: str,
    sender_broker_url_from_trusted_state: str,
    ephemeral_pubkey: bytes,
    nonce: bytes,
    ciphertext: bytes,
    exp_iso: str,
    bootstrap_key_id_b64_from_envelope: str,
    now: datetime | None = None,
) -> tuple[str, bytes]:
    """Decrypt one bootstrap envelope and return ``(user_id, pairing_key)``.

    ``sender_broker_url_from_trusted_state`` MUST come from the broker's
    own pairing state — NOT echoed from the envelope. Spec §9.3
    security rule.

    Raises [BootstrapDecryptError] when:
    - the bootstrap key id doesn't match (key was rotated between
      device-build and broker-decrypt);
    - the envelope's `exp` is outside ±5min of `now`;
    - AAD JSON canonical bytes diverge (i.e. ciphertext was bound to
      different metadata, OR the syncler server tampered with
      sender_broker_url);
    - AES-GCM tag verification fails.
    """
    # Bootstrap key id rotation guard.
    expected_kid = bootstrap_key_id(sender_bootstrap_pub)
    expected_kid_b64 = _b64_std(expected_kid)
    if bootstrap_key_id_b64_from_envelope != expected_kid_b64:
        raise BootstrapDecryptError(
            "bootstrap_key_id mismatch — key was rotated since the device built this envelope",
        )

    # Clock-skew window check (real replay guard is CAS at the broker).
    now = now or datetime.now(UTC)
    try:
        exp = datetime.fromisoformat(exp_iso.replace("Z", "+00:00"))
    except Exception as exc:
        raise BootstrapDecryptError(f"invalid exp_iso: {exp_iso!r}") from exc
    delta = (exp - now).total_seconds()
    if abs(delta) > BROKER_CLOCK_SKEW_SECONDS:
        raise BootstrapDecryptError(
            f"exp outside ±{BROKER_CLOCK_SKEW_SECONDS}s tolerance: delta={delta:.1f}s",
        )

    # X25519 ECDH. `from_public_bytes` validates input length and
    # `exchange` rejects low-order / invalid points — both can raise
    # ValueError / cryptography-library exceptions. Wrap as
    # BootstrapDecryptError so the broker's exception handler doesn't
    # let an opaque-401 path leak a 500 (Codex consultation 89 RED).
    try:
        eph_pub = X25519PublicKey.from_public_bytes(ephemeral_pubkey)
        shared_secret = sender_bootstrap_priv.exchange(eph_pub)
    except Exception as exc:
        raise BootstrapDecryptError(
            "X25519 ECDH failed — invalid ephemeral_pubkey",
        ) from exc

    # HKDF derivation. salt = eph_pub || sender_bootstrap_pub.
    salt = ephemeral_pubkey + sender_bootstrap_pub
    aead_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=_HKDF_INFO_BOOTSTRAP_AEAD,
    ).derive(shared_secret)

    # Canonical AAD. sender_broker_url comes from TRUSTED STATE, not
    # the envelope. This is the syncler-server-substitution guard.
    aad = assemble_bootstrap_aad(
        bootstrap_key_id_b64=expected_kid_b64,
        exp_iso=exp_iso,
        pairing_id=pairing_id,
        sender_broker_url=sender_broker_url_from_trusted_state,
        sender_id=sender_id,
    )

    try:
        plaintext = AESGCM(aead_key).decrypt(nonce, ciphertext, aad)
    except InvalidTag as exc:
        raise BootstrapDecryptError(
            "AES-GCM tag verification failed — AAD mismatch or tampered ciphertext",
        ) from exc

    # Parse plaintext: {"user_id": "...", "pairing_key": "<base64 32>"}
    try:
        decoded = json.loads(plaintext.decode("utf-8"))
        user_id = _canon_uuid(decoded["user_id"])
        import base64 as _b64mod
        pairing_key = _b64mod.b64decode(decoded["pairing_key"], validate=True)
    except Exception as exc:
        raise BootstrapDecryptError(
            "bootstrap plaintext is not the expected {user_id, pairing_key} shape",
        ) from exc
    if len(pairing_key) != 32:
        raise BootstrapDecryptError("decoded pairing_key is not 32 bytes")
    return user_id, pairing_key


def _b64_std(value: bytes) -> str:
    import base64 as _b64mod
    return _b64mod.b64encode(value).decode("ascii")


def x25519_keypair_pem() -> tuple[X25519PrivateKey, bytes]:
    """Generate a fresh X25519 bootstrap keypair. Returns
    ``(private_key, public_key_raw_32_bytes)``. Senders call this
    once at adoption, persist the private key (e.g. in their
    secrets manager), and register the public key + signature via
    `Client.register_bootstrap_key`. Rotation is the same call.
    """
    priv = X25519PrivateKey.generate()
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv, pub


def load_x25519_private_key_from_raw(raw: bytes) -> X25519PrivateKey:
    """Parse a 32-byte raw X25519 private key. Senders persist the
    raw bytes (or PEM-wrap them — see `cryptography` docs) and
    rehydrate via this helper before broker operations."""
    if len(raw) != 32:
        raise ValueError("X25519 raw private key must be 32 bytes")
    return X25519PrivateKey.from_private_bytes(raw)


__all__ = [
    "BootstrapDecryptError",
    "BootstrapEnvelopeReplayError",
    "BROKER_CLOCK_SKEW_SECONDS",
    "assemble_bootstrap_aad",
    "bootstrap_key_id",
    "decrypt_bootstrap_envelope",
    "x25519_keypair_pem",
    "load_x25519_private_key_from_raw",
]
