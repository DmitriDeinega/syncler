"""Client-side crypto: Ed25519 signing + AES-256-GCM encryption.

Matches docs/crypto-spec.md V1.1 (5-field AAD + 7-field envelope).
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import uuid
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


# --- Live cards (Phase 3b) -------------------------------------------------


def _canon_uuid(value: Any) -> str:
    """Normalize a UUID to the lowercase no-brace form the server stores
    via ``str(uuid.UUID(payload.*))``. Required before canonicalization so
    that uppercase or braced caller input produces the same bytes the
    server's ``_publish_envelope`` / ``_build_*_envelope_bytes`` produce.
    """
    return str(uuid.UUID(str(value)))


def assemble_live_card_aad(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    sequence_number: int,
    expires_at: Any,  # datetime; coerced to ISO8601 with Z suffix
) -> bytes:
    return canonical_json(
        {
            "card_key": card_key,
            "card_type": "live",
            "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
            "plugin_id": _canon_uuid(plugin_id),
            "sender_id": _canon_uuid(sender_id),
            "user_id": _canon_uuid(user_id),
            "sequence_number": sequence_number,
        }
    )


def assemble_live_card_upsert_envelope(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    encrypted_payload_b64: str,
    nonce_b64: str,
    sequence_number: int,
    expires_at: Any,
) -> bytes:
    return canonical_json(
        {
            "card_key": card_key,
            "card_type": "live",
            "encrypted_payload": encrypted_payload_b64,
            "expires_at": expires_at.isoformat().replace("+00:00", "Z"),
            "nonce": nonce_b64,
            "plugin_id": _canon_uuid(plugin_id),
            "sender_id": _canon_uuid(sender_id),
            "sequence_number": sequence_number,
            "user_id": _canon_uuid(user_id),
        }
    )


def assemble_live_card_delete_envelope(
    *,
    sender_id: str,
    user_id: str,
    card_key: str,
    nonce: str,
    expires_at: str,
) -> bytes:
    """Canonical Ed25519 signing input for ``POST /v1/cards/delete``.

    Phase 12 (Codex 95): the delete envelope now binds ``nonce`` and
    ``expires_at`` so a captured delete can't be replayed indefinitely
    against any future card with the same
    ``(sender_id, user_id, card_key)``. Pass base64 of 12 random
    bytes for ``nonce`` and an ISO-8601 UTC instant ≤ 48 h ahead for
    ``expires_at``.
    """
    return canonical_json(
        {
            "card_key": card_key,
            "expires_at": expires_at,
            "nonce": nonce,
            "sender_id": _canon_uuid(sender_id),
            "user_id": _canon_uuid(user_id),
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


# ---------------------------------------------------------------------------
# Phase 9b — V2 per-device envelope encryption (docs/crypto-spec.md §11).
#
# This module's V1 helpers above stay for the pairing-bootstrap flow (which
# remains V1 — see §9). Payload encryption switches entirely to V2.
# ---------------------------------------------------------------------------


import hashlib
from dataclasses import dataclass
from typing import Iterable

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.hpke import AEAD, KDF, KEM, Suite

V2_HPKE_SUITE = Suite(KEM.X25519, KDF.HKDF_SHA256, AEAD.AES_256_GCM)
V2_HPKE_KEM_OUTPUT_BYTES = 32
V2_CEK_BYTES = 32
V2_HPKE_WRAP_BYTES = V2_CEK_BYTES + 16  # AES-256-GCM tag


@dataclass(frozen=True)
class DirectoryDevice:
    """One row from POST /v1/senders/me/devices (spec §11.9)."""

    device_id: str  # canonical lowercase UUID string
    encryption_public_key: bytes  # raw 32-byte X25519


@dataclass(frozen=True)
class V2RecipientEnvelope:
    device_id: str
    hpke_kem_output: bytes  # 32 bytes
    hpke_ciphertext: bytes  # 48 bytes


@dataclass(frozen=True)
class V2PublishMaterial:
    """Everything the V2 publish HTTP body needs, ready to base64-encode."""

    payload_nonce: bytes
    payload_ciphertext: bytes
    recipient_envelopes: list[V2RecipientEnvelope]


def _canonical_uuid(value: str) -> str:
    return str(uuid.UUID(value)).lower()


def build_v2_payload_aad(
    *,
    envelope_kind: str,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    expires_at: str | None = None,
    min_plugin_version: str | None = None,
    card_key: str | None = None,
    card_type: str | None = None,
    sequence_number: int | None = None,
    card_id: str | None = None,
    base_seq: int | None = None,
    patch_seq: int | None = None,
) -> bytes:
    """Spec §11.7. Shared payload AAD (same for every recipient).

    V3 #16: ``envelope_kind="card_patch"`` binds the encrypted
    body to (card_id, base_seq, patch_seq) instead of
    expires_at/min_plugin_version — patches don't carry a TTL
    on the envelope; their lifetime is the parent card's.
    """
    aad: dict[str, Any] = {
        "envelope_kind": envelope_kind,
        "plugin_id": _canonical_uuid(plugin_id),
        "protocol_version": 2,
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
    }
    if envelope_kind == "card_patch":
        if card_id is None or base_seq is None or patch_seq is None:
            raise ValueError("card_patch aad needs card_id/base_seq/patch_seq")
        aad["card_id"] = _canonical_uuid(card_id)
        aad["base_seq"] = base_seq
        aad["patch_seq"] = patch_seq
    else:
        if expires_at is None or min_plugin_version is None:
            raise ValueError("non-patch aad needs expires_at/min_plugin_version")
        aad["expires_at"] = expires_at
        aad["min_plugin_version"] = min_plugin_version
        if envelope_kind == "live_card_upsert":
            if card_key is None or card_type is None or sequence_number is None:
                raise ValueError("live_card_upsert aad needs card_key/card_type/sequence_number")
            aad["card_key"] = card_key
            aad["card_type"] = card_type
            aad["sequence_number"] = sequence_number
    return canonical_json(aad)


def build_v2_hpke_info(
    *,
    envelope_kind: str,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    payload_nonce_b64: str,
    payload_ciphertext_sha256_hex: str,
    device_id: str,
    expires_at: str | None = None,
    min_plugin_version: str | None = None,
    card_key: str | None = None,
    card_type: str | None = None,
    sequence_number: int | None = None,
    card_id: str | None = None,
    base_seq: int | None = None,
    patch_seq: int | None = None,
) -> bytes:
    """Spec §11.3. Per-recipient HPKE info. Card fields are OMITTED
    (not null) for envelope_kind="event".

    V3 #16: ``card_patch`` binds info to (card_id, base_seq,
    patch_seq) — same shape choice as the payload AAD."""
    info: dict[str, Any] = {
        "device_id": _canonical_uuid(device_id),
        "envelope_kind": envelope_kind,
        "payload_ciphertext_sha256": payload_ciphertext_sha256_hex,
        "payload_nonce": payload_nonce_b64,
        "plugin_id": _canonical_uuid(plugin_id),
        "protocol_version": 2,
        "sender_id": _canonical_uuid(sender_id),
        "user_id": _canonical_uuid(user_id),
    }
    if envelope_kind == "card_patch":
        if card_id is None or base_seq is None or patch_seq is None:
            raise ValueError("card_patch info needs card_id/base_seq/patch_seq")
        info["card_id"] = _canonical_uuid(card_id)
        info["base_seq"] = base_seq
        info["patch_seq"] = patch_seq
    else:
        if expires_at is None or min_plugin_version is None:
            raise ValueError("non-patch info needs expires_at/min_plugin_version")
        info["expires_at"] = expires_at
        info["min_plugin_version"] = min_plugin_version
        if envelope_kind == "live_card_upsert":
            if card_key is None or card_type is None or sequence_number is None:
                raise ValueError("live_card_upsert info needs card_key/card_type/sequence_number")
            info["card_key"] = card_key
            info["card_type"] = card_type
            info["sequence_number"] = sequence_number
    return canonical_json(info)


def seal_v2_envelopes(
    *,
    plaintext: bytes,
    devices: Iterable[DirectoryDevice],
    envelope_kind: str,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    expires_at: str | None = None,
    min_plugin_version: str | None = None,
    card_key: str | None = None,
    card_type: str | None = None,
    sequence_number: int | None = None,
    card_id: str | None = None,
    base_seq: int | None = None,
    patch_seq: int | None = None,
) -> V2PublishMaterial:
    """Spec §11.4 / §11.5 sender-side seal.

    1. Generate per-message CEK (32-byte AES-256 key).
    2. AES-256-GCM encrypt payload under CEK + payload_nonce + payload_aad.
    3. For each recipient device, build per-recipient HPKE info + seal
       CEK with HPKE.encrypt(plaintext=CEK, info=info).
    4. Return material the caller wraps into the HTTP body.
    """
    devices_list = list(devices)
    if not devices_list:
        raise ValueError("at least one recipient device required")

    cek = secrets.token_bytes(V2_CEK_BYTES)
    payload_nonce = random_nonce()
    payload_aad = build_v2_payload_aad(
        envelope_kind=envelope_kind,
        sender_id=sender_id,
        user_id=user_id,
        plugin_id=plugin_id,
        expires_at=expires_at,
        min_plugin_version=min_plugin_version,
        card_key=card_key,
        card_type=card_type,
        sequence_number=sequence_number,
        card_id=card_id,
        base_seq=base_seq,
        patch_seq=patch_seq,
    )
    payload_ciphertext = AESGCM(cek).encrypt(payload_nonce, plaintext, payload_aad)

    payload_nonce_b64 = b64(payload_nonce)
    payload_ciphertext_sha256_hex = hashlib.sha256(payload_ciphertext).hexdigest()

    envelopes: list[V2RecipientEnvelope] = []
    for device in devices_list:
        info = build_v2_hpke_info(
            envelope_kind=envelope_kind,
            sender_id=sender_id,
            user_id=user_id,
            plugin_id=plugin_id,
            expires_at=expires_at,
            min_plugin_version=min_plugin_version,
            payload_nonce_b64=payload_nonce_b64,
            payload_ciphertext_sha256_hex=payload_ciphertext_sha256_hex,
            device_id=device.device_id,
            card_key=card_key,
            card_type=card_type,
            sequence_number=sequence_number,
            card_id=card_id,
            base_seq=base_seq,
            patch_seq=patch_seq,
        )
        pk = X25519PublicKey.from_public_bytes(device.encryption_public_key)
        enc_concat_ct = V2_HPKE_SUITE.encrypt(
            plaintext=cek, public_key=pk, info=info
        )
        if len(enc_concat_ct) != V2_HPKE_KEM_OUTPUT_BYTES + V2_HPKE_WRAP_BYTES:
            raise RuntimeError(
                f"HPKE output unexpected size: {len(enc_concat_ct)}"
            )
        envelopes.append(
            V2RecipientEnvelope(
                device_id=_canonical_uuid(device.device_id),
                hpke_kem_output=enc_concat_ct[:V2_HPKE_KEM_OUTPUT_BYTES],
                hpke_ciphertext=enc_concat_ct[V2_HPKE_KEM_OUTPUT_BYTES:],
            )
        )

    return V2PublishMaterial(
        payload_nonce=payload_nonce,
        payload_ciphertext=payload_ciphertext,
        recipient_envelopes=envelopes,
    )


def _serialize_recipient_envelopes_for_signing(
    envelopes: list[V2RecipientEnvelope],
) -> list[dict[str, str]]:
    """Sorted-by-device_id (lowercase UUID lex). Matches server-side
    `_serialize_recipient_envelopes` in app/services/envelopes_v2.py."""
    items = [
        {
            "device_id": env.device_id,
            "hpke_ciphertext": b64(env.hpke_ciphertext),
            "hpke_kem_output": b64(env.hpke_kem_output),
        }
        for env in envelopes
    ]
    items.sort(key=lambda item: item["device_id"])
    return items


def assemble_event_envelope_v2(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    expires_at: str,
    min_plugin_version: str,
    payload_nonce_b64: str,
    payload_ciphertext_b64: str,
    recipient_envelopes: list[V2RecipientEnvelope],
    recipient_directory_version: int,
) -> bytes:
    """Spec §11.8 — Ed25519 signing input for `envelope_kind="event"`."""
    return canonical_json(
        {
            "envelope_kind": "event",
            "expires_at": expires_at,
            "min_plugin_version": min_plugin_version,
            "payload_ciphertext": payload_ciphertext_b64,
            "payload_nonce": payload_nonce_b64,
            "plugin_id": _canonical_uuid(plugin_id),
            "protocol_version": 2,
            "recipient_directory_version": recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes_for_signing(
                recipient_envelopes
            ),
            "sender_id": _canonical_uuid(sender_id),
            "user_id": _canonical_uuid(user_id),
        }
    )


def assemble_live_card_upsert_envelope_v2(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    card_type: str,
    sequence_number: int,
    expires_at: str,
    min_plugin_version: str,
    payload_nonce_b64: str,
    payload_ciphertext_b64: str,
    recipient_envelopes: list[V2RecipientEnvelope],
    recipient_directory_version: int,
) -> bytes:
    """Spec §11.8 — Ed25519 signing input for live_card_upsert."""
    return canonical_json(
        {
            "card_key": card_key,
            "card_type": card_type,
            "envelope_kind": "live_card_upsert",
            "expires_at": expires_at,
            "min_plugin_version": min_plugin_version,
            "payload_ciphertext": payload_ciphertext_b64,
            "payload_nonce": payload_nonce_b64,
            "plugin_id": _canonical_uuid(plugin_id),
            "protocol_version": 2,
            "recipient_directory_version": recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes_for_signing(
                recipient_envelopes
            ),
            "sender_id": _canonical_uuid(sender_id),
            "sequence_number": sequence_number,
            "user_id": _canonical_uuid(user_id),
        }
    )


def assemble_card_patch_envelope_v2(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_id: str,
    base_seq: int,
    patch_seq: int,
    payload_nonce_b64: str,
    payload_ciphertext_b64: str,
    recipient_envelopes: list[V2RecipientEnvelope],
    recipient_directory_version: int,
) -> bytes:
    """V3 #16 — Ed25519 signing input for envelope_kind="card_patch".

    Mirrors server-side ``build_card_patch_envelope_bytes`` in
    app/services/envelopes_v2.py. The patches themselves live
    inside the per-recipient HPKE ciphertext; the canonical
    envelope only commits to routing + sequence metadata.
    """
    return canonical_json(
        {
            "base_seq": base_seq,
            "card_id": _canonical_uuid(card_id),
            "envelope_kind": "card_patch",
            "patch_seq": patch_seq,
            "payload_ciphertext": payload_ciphertext_b64,
            "payload_nonce": payload_nonce_b64,
            "plugin_id": _canonical_uuid(plugin_id),
            "protocol_version": 2,
            "recipient_directory_version": recipient_directory_version,
            "recipient_envelopes": _serialize_recipient_envelopes_for_signing(
                recipient_envelopes
            ),
            "sender_id": _canonical_uuid(sender_id),
            "user_id": _canonical_uuid(user_id),
        }
    )


def assemble_live_card_delete_envelope_v2(
    *,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    card_key: str,
    nonce: str,
    expires_at: str,
) -> bytes:
    """Spec §11.6 — Ed25519 signing input for live_card_delete (V2)."""
    return canonical_json(
        {
            "card_key": card_key,
            "envelope_kind": "live_card_delete",
            "expires_at": expires_at,
            "nonce": nonce,
            "plugin_id": _canonical_uuid(plugin_id),
            "protocol_version": 2,
            "sender_id": _canonical_uuid(sender_id),
            "user_id": _canonical_uuid(user_id),
        }
    )


def assemble_directory_fetch_envelope(
    *,
    sender_id: str,
    user_id: str,
) -> bytes:
    """Spec §11.9 — Ed25519 signing input for the sender directory fetch."""
    return canonical_json(
        {
            "endpoint_kind": "directory_fetch",
            "sender_id": _canonical_uuid(sender_id),
            "user_id": _canonical_uuid(user_id),
        }
    )
