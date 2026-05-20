"""Message wire format helpers."""

from __future__ import annotations

from typing import Final

WIRE_NONCE_BYTES: Final[int] = 12
WIRE_TAG_BYTES: Final[int] = 16
WIRE_MIN_BYTES: Final[int] = WIRE_NONCE_BYTES + WIRE_TAG_BYTES


def pack_message(nonce: bytes, ciphertext_with_tag: bytes) -> bytes:
    """Pack nonce and ciphertext into V1 wire format."""

    if len(nonce) != WIRE_NONCE_BYTES:
        raise ValueError("nonce must be 12 bytes")
    if len(ciphertext_with_tag) < WIRE_TAG_BYTES:
        raise ValueError("ciphertext_with_tag must include a 16-byte tag")
    return nonce + ciphertext_with_tag


def unpack_message(wire: bytes) -> tuple[bytes, bytes]:
    """Unpack V1 wire format into nonce and ciphertext-with-tag."""

    if len(wire) < WIRE_MIN_BYTES:
        raise ValueError("wire is too short")
    return wire[:WIRE_NONCE_BYTES], wire[WIRE_NONCE_BYTES:]
