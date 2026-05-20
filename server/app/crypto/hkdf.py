"""HKDF reference derivations shared by clients and SDKs."""

from __future__ import annotations

from typing import Final

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

HKDF_LABEL_PAIRING_KEY: Final[str] = "syncler-v1-pairing-key"
HKDF_LABEL_NOTIFICATION_KEY: Final[str] = "syncler-v1-notification-key"

HKDF_SHA256_LENGTH_BYTES: Final[int] = 32


def derive_pairing_key(master_key: bytes, sender_id: bytes) -> bytes:
    """Derive the per-sender pairing key from a client master key."""

    info = f"{HKDF_LABEL_PAIRING_KEY}:".encode("ascii") + sender_id
    return HKDF(
        algorithm=hashes.SHA256(),
        length=HKDF_SHA256_LENGTH_BYTES,
        salt=sender_id,
        info=info,
    ).derive(master_key)

