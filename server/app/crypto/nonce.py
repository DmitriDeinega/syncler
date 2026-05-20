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
        """Return True if the nonce was already seen, and mark it as seen otherwise.

        Legacy API. Prefer ``has()`` + ``mark()`` for code paths that need
        atomic check-then-store coupled to durable commit success.
        """

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

    def has(self, sender_id: bytes, nonce: bytes) -> bool:
        """Return True if the nonce was previously marked seen for this sender."""

        if len(nonce) != NONCE_BYTES:
            raise ValueError("nonce must be 12 bytes")
        sender_nonces = self._nonces_by_sender.get(sender_id)
        return sender_nonces is not None and nonce in sender_nonces

    def mark(self, sender_id: bytes, nonce: bytes) -> None:
        """Record the nonce as seen for this sender."""

        if len(nonce) != NONCE_BYTES:
            raise ValueError("nonce must be 12 bytes")
        sender_nonces = self._nonces_by_sender[sender_id]
        sender_nonces[nonce] = None
        sender_nonces.move_to_end(nonce)
        if len(sender_nonces) > self._window:
            sender_nonces.popitem(last=False)


_global_registry: NonceRegistry | None = None


def get_global_registry() -> NonceRegistry:
    """Process-wide nonce registry.

    V1 is process-local; M11 / V1.5 migrates to a DB-backed registry so
    multi-worker deployments share state and replay protection survives
    restarts.
    """
    global _global_registry
    if _global_registry is None:
        _global_registry = NonceRegistry()
    return _global_registry


def reset_global_registry() -> None:
    """Test helper — drop the global registry."""
    global _global_registry
    _global_registry = None

