"""Phase 9b V2 test helpers — shared across the test suite.

Wraps `sdk-python/syncler/crypto.py` so server tests can:
- Enroll a device with a real X25519 keypair (Phase 9b §11.12
  requires `encryption_public_key`).
- Build V2 message/upsert/delete request bodies that pass server
  signature verification AND the recipient classifier
  (sdk-python's `seal_v2_envelopes` produces byte-identical
  canonical bytes to `app/services/envelopes_v2.py`).

The helpers are tiny on purpose — most of the cryptographic work
lives in the SDK so the server tests don't drift from the
production-shape encoding.
"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)

from syncler.crypto import (
    DirectoryDevice,
    assemble_event_envelope_v2,
    assemble_live_card_delete_envelope_v2,
    assemble_live_card_upsert_envelope_v2,
    canonical_json,
    seal_v2_envelopes,
)


@dataclass
class DeviceKeyMaterial:
    """X25519 keypair generated for one test device. The raw private
    key stays test-local (no equivalent on real Android devices'
    AndroidKeystore-isolated key). Tests that need to decrypt round-
    trip a sealed envelope use these bytes via PyCA's HPKE.decrypt."""

    private_key_raw: bytes  # 32 bytes
    public_key_raw: bytes   # 32 bytes


def fresh_x25519_keypair() -> DeviceKeyMaterial:
    sk = X25519PrivateKey.generate()
    pk = sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    sk_raw = sk.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    return DeviceKeyMaterial(private_key_raw=sk_raw, public_key_raw=pk)


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def enroll_body(public_key_raw: bytes, *, encryption_public_key_raw: bytes | None = None,
                fcm_token: str | None = None) -> dict[str, Any]:
    """Phase 9b V2-shaped enroll body.

    Generates a real X25519 keypair if one isn't supplied — tests that
    only need to satisfy schema validation don't care about the key.
    Tests that exercise the V2 publish round-trip pass the device's
    keypair so the directory + seal flow lines up.
    """
    if encryption_public_key_raw is None:
        encryption_public_key_raw = fresh_x25519_keypair().public_key_raw
    body: dict[str, Any] = {
        "public_key": b64(public_key_raw),
        "encryption_public_key": b64(encryption_public_key_raw),
    }
    if fcm_token is not None:
        body["fcm_token"] = fcm_token
    return body


def directory_fetch_body(
    *, sender_id: str, user_id: str, sender_ed25519: Ed25519PrivateKey
) -> dict[str, Any]:
    """Build a signed `POST /v1/senders/me/devices` body (spec §11.9).

    Signature is over canonical JSON of
    `{endpoint_kind: "directory_fetch", sender_id, user_id}`.
    """
    canonical = canonical_json(
        {
            "endpoint_kind": "directory_fetch",
            "sender_id": _canonical_uuid(sender_id),
            "user_id": _canonical_uuid(user_id),
        }
    )
    signature = sender_ed25519.sign(canonical)
    return {
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
        "request_signature": b64(signature),
    }


def build_event_publish_body(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    payload: dict[str, Any] | bytes,
    recipient_devices: list[DirectoryDevice],
    recipient_directory_version: int,
    sender_ed25519: Ed25519PrivateKey,
    min_plugin_version: str = "",
    ttl_seconds: int = 3600,
    expires_at: datetime | None = None,
) -> dict[str, Any]:
    """Build a V2 event publish body (spec §11.4)."""
    if expires_at is None:
        expires_at = datetime.now(UTC) + timedelta(seconds=ttl_seconds)
    expires_at_str = expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
    plaintext = payload if isinstance(payload, bytes) else json.dumps(
        payload, separators=(",", ":")
    ).encode("utf-8")

    material = seal_v2_envelopes(
        plaintext=plaintext,
        devices=recipient_devices,
        envelope_kind="event",
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        expires_at=expires_at_str,
        min_plugin_version=min_plugin_version,
    )

    envelope_bytes = assemble_event_envelope_v2(
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        expires_at=expires_at_str,
        min_plugin_version=min_plugin_version,
        payload_nonce_b64=b64(material.payload_nonce),
        payload_ciphertext_b64=b64(material.payload_ciphertext),
        recipient_envelopes=material.recipient_envelopes,
        recipient_directory_version=recipient_directory_version,
    )
    signature = sender_ed25519.sign(envelope_bytes)

    return {
        "protocol_version": 2,
        "envelope_kind": "event",
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
        "plugin_id": _canonical_uuid(plugin_id),
        "expires_at": expires_at_str,
        "min_plugin_version": min_plugin_version or None,
        "payload_nonce": b64(material.payload_nonce),
        "payload_ciphertext": b64(material.payload_ciphertext),
        "recipient_envelopes": [
            {
                "device_id": env.device_id,
                "hpke_kem_output": b64(env.hpke_kem_output),
                "hpke_ciphertext": b64(env.hpke_ciphertext),
            }
            for env in material.recipient_envelopes
        ],
        "recipient_directory_version": recipient_directory_version,
        "envelope_signature": b64(signature),
    }


def build_live_card_upsert_body(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    card_type: str,
    sequence_number: int,
    payload: dict[str, Any] | bytes,
    recipient_devices: list[DirectoryDevice],
    recipient_directory_version: int,
    sender_ed25519: Ed25519PrivateKey,
    min_plugin_version: str = "",
    ttl_seconds: int = 24 * 3600,
    expires_at: datetime | None = None,
) -> dict[str, Any]:
    """Build a V2 live-card upsert body (spec §11.5)."""
    if expires_at is None:
        expires_at = datetime.now(UTC) + timedelta(seconds=ttl_seconds)
    expires_at_str = expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
    plaintext = payload if isinstance(payload, bytes) else json.dumps(
        payload, separators=(",", ":")
    ).encode("utf-8")

    material = seal_v2_envelopes(
        plaintext=plaintext,
        devices=recipient_devices,
        envelope_kind="live_card_upsert",
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        expires_at=expires_at_str,
        min_plugin_version=min_plugin_version,
        card_key=card_key,
        card_type=card_type,
        sequence_number=sequence_number,
    )

    envelope_bytes = assemble_live_card_upsert_envelope_v2(
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        card_key=card_key,
        card_type=card_type,
        sequence_number=sequence_number,
        expires_at=expires_at_str,
        min_plugin_version=min_plugin_version,
        payload_nonce_b64=b64(material.payload_nonce),
        payload_ciphertext_b64=b64(material.payload_ciphertext),
        recipient_envelopes=material.recipient_envelopes,
        recipient_directory_version=recipient_directory_version,
    )
    signature = sender_ed25519.sign(envelope_bytes)

    return {
        "protocol_version": 2,
        "envelope_kind": "live_card_upsert",
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
        "plugin_id": _canonical_uuid(plugin_id),
        "card_key": card_key,
        "card_type": card_type,
        "sequence_number": sequence_number,
        "expires_at": expires_at_str,
        "min_plugin_version": min_plugin_version or None,
        "payload_nonce": b64(material.payload_nonce),
        "payload_ciphertext": b64(material.payload_ciphertext),
        "recipient_envelopes": [
            {
                "device_id": env.device_id,
                "hpke_kem_output": b64(env.hpke_kem_output),
                "hpke_ciphertext": b64(env.hpke_ciphertext),
            }
            for env in material.recipient_envelopes
        ],
        "recipient_directory_version": recipient_directory_version,
        "envelope_signature": b64(signature),
    }


def build_live_card_delete_body(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    sender_ed25519: Ed25519PrivateKey,
    nonce: bytes | None = None,
    ttl_seconds: int = 3600,
    expires_at: datetime | None = None,
) -> dict[str, Any]:
    """Build a V2 live-card delete body (spec §11.6).

    Adds `plugin_id` over the V1 shape per Codex 125 fix
    (cross-plugin-replay defense).
    """
    import secrets

    if nonce is None:
        nonce = secrets.token_bytes(12)
    if expires_at is None:
        expires_at = datetime.now(UTC) + timedelta(seconds=ttl_seconds)
    expires_at_str = expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
    nonce_b64 = b64(nonce)

    envelope_bytes = assemble_live_card_delete_envelope_v2(
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        card_key=card_key,
        nonce=nonce_b64,
        expires_at=expires_at_str,
    )
    signature = sender_ed25519.sign(envelope_bytes)
    return {
        "protocol_version": 2,
        "envelope_kind": "live_card_delete",
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
        "plugin_id": _canonical_uuid(plugin_id),
        "card_key": card_key,
        "nonce": nonce_b64,
        "expires_at": expires_at_str,
        "envelope_signature": b64(signature),
    }


def _canonical_uuid(value: str) -> str:
    return str(UUID(value))
