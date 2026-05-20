"""Nonce generation and in-memory replay detection."""

from __future__ import annotations

import secrets
from collections import OrderedDict, defaultdict
from typing import Final

NONCE_BYTES: Final[int] = 12
NONCE_REGISTRY_WINDOW: Final[int] = 100_000


def generate_nonce() -> bytes:
    """Generate a 96-bit AES-GCM nonce."""

    return secrets.token_bytes(NONCE_BYTES)


class NonceRegistry:
    """Per-sender LRU replay detector for recently seen nonces."""

    def __init__(self, window: int = NONCE_REGISTRY_WINDOW) -> None:
        if window <= 0:
            raise ValueError("window must be positive")
        self._window = window
        self._nonces_by_sender: defaultdict[bytes, OrderedDict[bytes, None]] = defaultdict(OrderedDict)

    def seen(self, sender_id: bytes, nonce: bytes) -> bool:
        """Return True if the nonce was already seen for this sender."""

        if len(nonce) != NONCE_BYTES:
            raise ValueError("nonce must be 12 bytes")

        sender_nonces = self._nonces_by_sender[sender_id]
        if nonce in sender_nonces:
            sender_nonces.move_to_end(nonce)
            return True

        sender_nonces[nonce] = None
        if len(sender_nonces) > self._window:
            sender_nonces.popitem(last=False)
        return False

