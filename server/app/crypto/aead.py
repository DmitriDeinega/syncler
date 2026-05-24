"""AES-256-GCM helpers and canonical AAD assembly."""

from __future__ import annotations

import json
from typing import Any, Final

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

AES_256_KEY_BYTES: Final[int] = 32
AES_GCM_NONCE_BYTES: Final[int] = 12
AES_GCM_TAG_BYTES: Final[int] = 16

AAD_REQUIRED_FIELDS: Final[tuple[str, ...]] = (
    "sender_id",
    "user_id",
    "plugin_id",
    "min_plugin_version",
    "expires_at",
)
AAD_REQUIRED_FIELD_SET: Final[frozenset[str]] = frozenset(AAD_REQUIRED_FIELDS)

ENVELOPE_REQUIRED_FIELDS: Final[tuple[str, ...]] = AAD_REQUIRED_FIELDS + (
    "encrypted_body",
    "nonce",
)
ENVELOPE_REQUIRED_FIELD_SET: Final[frozenset[str]] = frozenset(ENVELOPE_REQUIRED_FIELDS)


def decrypt_message_body(pairing_key: bytes, wire: bytes, aad: bytes) -> bytes:
    """Decrypt a packed AES-GCM message body."""

    if len(pairing_key) != AES_256_KEY_BYTES:
        raise ValueError("pairing_key must be 32 bytes")
    if len(wire) < AES_GCM_NONCE_BYTES + AES_GCM_TAG_BYTES:
        raise ValueError("wire is too short")

    nonce = wire[:AES_GCM_NONCE_BYTES]
    ciphertext_with_tag = wire[AES_GCM_NONCE_BYTES:]
    return AESGCM(pairing_key).decrypt(nonce, ciphertext_with_tag, aad)


def assemble_aad(fields: dict[str, Any]) -> bytes:
    """Assemble canonical JSON AAD for AES-GCM encryption.

    AAD authenticates the protocol context bound to the ciphertext but does
    NOT include the ciphertext itself. See ``assemble_envelope`` for the
    Ed25519 signing input.
    """
    return _canonical(fields)


def assemble_envelope(fields: dict[str, Any]) -> bytes:
    """Assemble canonical envelope JSON for Ed25519 sender signing.

    Envelope = AAD + ``encrypted_body`` (base64) + ``nonce`` (base64).
    """
    return _canonical(fields)


def _canonical(fields: dict[str, Any]) -> bytes:
    return json.dumps(
        fields,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
