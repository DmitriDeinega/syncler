"""Client-side crypto: Ed25519 signing + AES-256-GCM encryption.

Matches docs/crypto-spec.md V1.1 (5-field AAD + 7-field envelope).
"""

from __future__ import annotations

import base64
import json
import os
import secrets
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

AES_GCM_NONCE_BYTES = 12


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def b64d(value: str) -> bytes:
    return base64.b64decode(value)


def random_nonce() -> bytes:
    return secrets.token_bytes(AES_GCM_NONCE_BYTES)


def canonical_json(fields: dict[str, Any]) -> bytes:
    return json.dumps(fields, sort_keys=True, separators=(",", ":")).encode("utf-8")


def assemble_aad(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    min_plugin_version: str,
    expires_at: str,
) -> bytes:
    return canonical_json(
        {
            "sender_id": sender_id,
            "user_id": user_id,
            "plugin_id": plugin_id,
            "min_plugin_version": min_plugin_version,
            "expires_at": expires_at,
        }
    )


def assemble_envelope(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    min_plugin_version: str,
    expires_at: str,
    encrypted_body_b64: str,
    nonce_b64: str,
) -> bytes:
    return canonical_json(
        {
            "sender_id": sender_id,
            "user_id": user_id,
            "plugin_id": plugin_id,
            "min_plugin_version": min_plugin_version,
            "expires_at": expires_at,
            "encrypted_body": encrypted_body_b64,
            "nonce": nonce_b64,
        }
    )


def load_private_key(path: str) -> Ed25519PrivateKey:
    """Load an Ed25519 private key from a PEM file. Generate one if absent."""
    expanded = os.path.expanduser(path)
    if not os.path.exists(expanded):
        return generate_and_save_private_key(expanded)
    with open(expanded, "rb") as f:
        data = f.read()
    return serialization.load_pem_private_key(data, password=None)


def generate_and_save_private_key(path: str) -> Ed25519PrivateKey:
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "wb") as f:
        f.write(pem)
    os.chmod(path, 0o600)
    return key


def public_key_raw(key: Ed25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )


def encrypt_payload(
    *,
    pairing_key: bytes,
    plaintext: bytes,
    aad: bytes,
) -> tuple[bytes, bytes]:
    """Returns (nonce, ciphertext_with_tag)."""
    if len(pairing_key) != 32:
        raise ValueError("pairing_key must be 32 bytes")
    nonce = random_nonce()
    ciphertext = AESGCM(pairing_key).encrypt(nonce, plaintext, aad)
    return nonce, ciphertext
