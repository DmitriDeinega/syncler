"""Phase 9b HPKE helpers for per-device envelope encryption.

Implements ``docs/crypto-spec.md §11``:

- HPKE Suite = DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM
  (RFC 9180 IDs 0x0020 / 0x0001 / 0x0002).
- The sender generates a 32-byte CEK per message, encrypts the payload
  ONCE with AES-256-GCM under the CEK, then wraps the CEK once per
  recipient device via HPKE Base mode.
- PyCA's single-shot HPKE returns ``enc || ciphertext``; this module
  splits and re-joins so wire JSON can carry two distinct base64 fields
  (`hpke_kem_output` + `hpke_ciphertext`) for schema-level validation
  cleanliness.
- HPKE ``aad`` is not exposed by PyCA's single-shot API. All per-
  recipient authenticated context lives in HPKE ``info`` instead (see
  ``build_hpke_info``).

Cross-platform invariant: byte-equivalent ``info`` JSON on Python + Tink
(Android). Canonical JSON encoding matches ``app.crypto.aead._canonical``.
"""

from __future__ import annotations

import hashlib
import json
import secrets
from dataclasses import dataclass
from typing import Any, Final

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.hpke import AEAD, KDF, KEM, Suite
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PrivateFormat,
    PublicFormat,
    NoEncryption,
)

# RFC 9180 0x0020 / 0x0001 / 0x0002. Spec §11.2.
SUITE: Final[Suite] = Suite(KEM.X25519, KDF.HKDF_SHA256, AEAD.AES_256_GCM)

# X25519 KEM `enc` size is fixed at 32 bytes. The HPKE wrap of a 32-byte
# CEK under AES-256-GCM is 32 (plaintext) + 16 (AEAD tag) = 48 bytes.
# Total PyCA `encrypt()` output = 80 bytes. These constants drive the
# wire-split / re-join, NOT vice-versa — see crypto-spec.md §11.2.
HPKE_KEM_OUTPUT_BYTES: Final[int] = 32
CEK_BYTES: Final[int] = 32
HPKE_WRAP_BYTES: Final[int] = CEK_BYTES + 16
HPKE_OUTPUT_BYTES: Final[int] = HPKE_KEM_OUTPUT_BYTES + HPKE_WRAP_BYTES

X25519_PUBLIC_KEY_BYTES: Final[int] = 32
X25519_PRIVATE_KEY_BYTES: Final[int] = 32

# Cap per-message recipient count (spec §11.10).
RECIPIENT_CAP_PER_MESSAGE: Final[int] = 32


@dataclass(frozen=True)
class RecipientEnvelope:
    """One per-device HPKE wrap of the CEK.

    Wire form: see ``LiveCardUpsertRequestV2``/``MessageSendRequestV2``
    in ``app.schemas``. ``hpke_kem_output`` is the 32-byte X25519
    encapsulated key; ``hpke_ciphertext`` is the 48-byte HPKE-AEAD-
    wrapped CEK (32 plaintext + 16 tag).
    """

    device_id: str
    hpke_kem_output: bytes  # 32 bytes
    hpke_ciphertext: bytes  # 48 bytes


def x25519_public_key_from_raw(raw: bytes) -> X25519PublicKey:
    """Parse a 32-byte X25519 public key. Raises ValueError if invalid."""
    if len(raw) != X25519_PUBLIC_KEY_BYTES:
        raise ValueError(f"X25519 public key must be exactly {X25519_PUBLIC_KEY_BYTES} bytes")
    return X25519PublicKey.from_public_bytes(raw)


def x25519_private_key_from_raw(raw: bytes) -> X25519PrivateKey:
    if len(raw) != X25519_PRIVATE_KEY_BYTES:
        raise ValueError(f"X25519 private key must be exactly {X25519_PRIVATE_KEY_BYTES} bytes")
    return X25519PrivateKey.from_private_bytes(raw)


def generate_x25519_keypair() -> tuple[bytes, bytes]:
    """Generate a fresh X25519 keypair. Returns (private, public) as raw 32-byte values."""
    sk = X25519PrivateKey.generate()
    pk_raw = sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    sk_raw = sk.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    return sk_raw, pk_raw


def generate_cek() -> bytes:
    """Generate a fresh 32-byte CEK for one message's payload."""
    return secrets.token_bytes(CEK_BYTES)


def build_hpke_info(
    *,
    protocol_version: int,
    envelope_kind: str,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    expires_at: str,
    min_plugin_version: str,
    payload_nonce_b64: str,
    payload_ciphertext_sha256_hex: str,
    device_id: str,
    card_key: str | None = None,
    card_type: str | None = None,
    sequence_number: int | None = None,
) -> bytes:
    """Build the canonical HPKE `info` blob for one recipient.

    Spec §11.3. Sorted keys, UTF-8, compact separators. Card-specific
    fields are OMITTED (not emitted as null) when envelope_kind is
    ``"event"`` — the presence/absence is part of the canonical bytes,
    so an event-info cannot collide with an upsert-info even if the
    other fields are identical.
    """
    info: dict[str, Any] = {
        "device_id": device_id,
        "envelope_kind": envelope_kind,
        "expires_at": expires_at,
        "min_plugin_version": min_plugin_version,
        "payload_ciphertext_sha256": payload_ciphertext_sha256_hex,
        "payload_nonce": payload_nonce_b64,
        "plugin_id": plugin_id,
        "protocol_version": protocol_version,
        "sender_id": sender_id,
        "user_id": user_id,
    }
    if envelope_kind == "live_card_upsert":
        if card_key is None or card_type is None or sequence_number is None:
            raise ValueError("live_card_upsert info requires card_key, card_type, sequence_number")
        info["card_key"] = card_key
        info["card_type"] = card_type
        info["sequence_number"] = sequence_number
    return _canonical_json(info)


def build_payload_aad(
    *,
    protocol_version: int,
    envelope_kind: str,
    sender_id: str,
    user_id: str,
    plugin_id: str,
    expires_at: str,
    min_plugin_version: str,
    card_key: str | None = None,
    card_type: str | None = None,
    sequence_number: int | None = None,
) -> bytes:
    """Build the canonical payload AAD for AES-GCM(payload, CEK, ...).

    Spec §11.7. Shared across all recipients (one ciphertext per
    message); per-recipient binding is in HPKE `info`.
    """
    aad: dict[str, Any] = {
        "envelope_kind": envelope_kind,
        "expires_at": expires_at,
        "min_plugin_version": min_plugin_version,
        "plugin_id": plugin_id,
        "protocol_version": protocol_version,
        "sender_id": sender_id,
        "user_id": user_id,
    }
    if envelope_kind == "live_card_upsert":
        if card_key is None or card_type is None or sequence_number is None:
            raise ValueError("live_card_upsert aad requires card_key, card_type, sequence_number")
        aad["card_key"] = card_key
        aad["card_type"] = card_type
        aad["sequence_number"] = sequence_number
    return _canonical_json(aad)


def seal_cek_for_device(
    *,
    cek: bytes,
    recipient_public_key: bytes,
    info: bytes,
    device_id: str,
) -> RecipientEnvelope:
    """HPKE-seal the CEK to one recipient.

    PyCA's ``Suite.encrypt`` returns ``enc || ciphertext``. We split at
    the KEM output length so the wire JSON carries two clean fields.
    """
    if len(cek) != CEK_BYTES:
        raise ValueError(f"CEK must be exactly {CEK_BYTES} bytes")
    pk = x25519_public_key_from_raw(recipient_public_key)
    out = SUITE.encrypt(plaintext=cek, public_key=pk, info=info)
    if len(out) != HPKE_OUTPUT_BYTES:
        # Should never happen unless PyCA's API changes underneath us.
        raise RuntimeError(
            f"HPKE output unexpected size: {len(out)} (expected {HPKE_OUTPUT_BYTES})"
        )
    return RecipientEnvelope(
        device_id=device_id,
        hpke_kem_output=out[:HPKE_KEM_OUTPUT_BYTES],
        hpke_ciphertext=out[HPKE_KEM_OUTPUT_BYTES:],
    )


def open_cek_for_device(
    *,
    private_key: bytes,
    hpke_kem_output: bytes,
    hpke_ciphertext: bytes,
    info: bytes,
) -> bytes:
    """HPKE-open a recipient's CEK wrap. Returns the 32-byte CEK."""
    if len(hpke_kem_output) != HPKE_KEM_OUTPUT_BYTES:
        raise ValueError(
            f"hpke_kem_output must be {HPKE_KEM_OUTPUT_BYTES} bytes, got {len(hpke_kem_output)}"
        )
    if len(hpke_ciphertext) != HPKE_WRAP_BYTES:
        raise ValueError(
            f"hpke_ciphertext must be {HPKE_WRAP_BYTES} bytes, got {len(hpke_ciphertext)}"
        )
    sk = x25519_private_key_from_raw(private_key)
    full = hpke_kem_output + hpke_ciphertext
    cek = SUITE.decrypt(ciphertext=full, private_key=sk, info=info)
    if len(cek) != CEK_BYTES:
        raise RuntimeError(f"opened CEK unexpected size: {len(cek)}")
    return cek


def encrypt_payload(
    *,
    payload: bytes,
    cek: bytes,
    payload_nonce: bytes,
    payload_aad: bytes,
) -> bytes:
    """AES-256-GCM(payload, cek, nonce, aad) → ciphertext_with_tag."""
    if len(cek) != CEK_BYTES:
        raise ValueError(f"CEK must be exactly {CEK_BYTES} bytes")
    if len(payload_nonce) != 12:
        raise ValueError("payload_nonce must be 12 bytes")
    return AESGCM(cek).encrypt(payload_nonce, payload, payload_aad)


def decrypt_payload(
    *,
    payload_ciphertext: bytes,
    cek: bytes,
    payload_nonce: bytes,
    payload_aad: bytes,
) -> bytes:
    """Reverse of ``encrypt_payload``. Raises if the AEAD tag is wrong."""
    if len(cek) != CEK_BYTES:
        raise ValueError(f"CEK must be exactly {CEK_BYTES} bytes")
    if len(payload_nonce) != 12:
        raise ValueError("payload_nonce must be 12 bytes")
    return AESGCM(cek).decrypt(payload_nonce, payload_ciphertext, payload_aad)


def sha256_hex(data: bytes) -> str:
    """Lowercase hex SHA-256 — used in HPKE info to bind the CEK wrap
    to a specific payload (spec §11.3 ``payload_ciphertext_sha256``)."""
    return hashlib.sha256(data).hexdigest()


def _canonical_json(fields: dict[str, Any]) -> bytes:
    """Canonical JSON encoding for HPKE info / payload AAD / signed
    envelope. Matches ``app.crypto.aead._canonical`` byte-for-byte:
    sorted keys, UTF-8, compact separators, ASCII escape.
    """
    return json.dumps(
        fields,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
