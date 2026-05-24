"""V1.5 automated pairing — broker storage interface.

The sender-operated broker persists `(user_id, pairing_key)` keyed on
`pairing_id` after successfully decrypting one bootstrap envelope.
``Client.wait_for_pairing(...)`` polls this storage to learn when a
pairing has completed.

The protocol is sender-private. Production callers can plug in Redis,
Postgres, or any other backing store; the SDK ships only the protocol
+ a default in-memory implementation for dev.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class BrokerEntry:
    """Completed pairing tuple stored by the broker after a successful
    decrypt. The fields are exactly what `Client.set_pairing(...)`
    needs to bring the SDK out of the pending state."""
    user_id: str
    pairing_key: bytes  # 32 bytes


class BrokerStorageConflictError(Exception):
    """Raised on compare-and-set conflict: same `pairing_id` already
    completed with DIFFERENT `(user_id, pairing_key)` values. Indicates
    a replay attack or a buggy device. Same values is idempotent —
    NOT a conflict (no exception)."""


class BrokerStorage(Protocol):
    """The broker storage protocol.

    Implementations MUST be thread-safe AND compare-and-set safe:
    `complete(...)` is the syncronization point. The first call wins;
    subsequent calls with identical values are idempotent; calls with
    different values raise [BrokerStorageConflictError]. This semantics
    is what defeats envelope replay (spec §9.3).
    """

    def reserve(self, pairing_id: str) -> None:
        """Called at `pairing/initiate` time to mark the slot. Optional
        for in-memory storage; required for storages where you need
        explicit slot creation (e.g. DB rows with not-null
        constraints). Idempotent."""

    def complete(self, pairing_id: str, entry: BrokerEntry) -> None:
        """Compare-and-set. Behavior:

        - First call for `pairing_id` → stores entry, returns.
        - Second call with same `entry` → idempotent, returns.
        - Second call with different `entry` →
          [BrokerStorageConflictError].

        Implementations MUST be atomic at the storage layer (Postgres
        UPSERT WHERE NOT EXISTS or equivalent — NOT a Python-level
        check-then-store)."""

    def fetch(self, pairing_id: str) -> BrokerEntry | None:
        """Returns the completed entry or `None` if still pending.
        Called repeatedly by `Client.wait_for_pairing(...)`."""


class InMemoryBrokerStorage:
    """Default in-process implementation. Suitable for dev / tests /
    single-process toy deployments. Loses state on restart. Not safe
    across multiple uvicorn workers — use a real storage backend
    (Redis, Postgres) for production multi-worker setups.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: dict[str, BrokerEntry] = {}

    def reserve(self, pairing_id: str) -> None:
        # No-op: dict permits any key. `complete` is the CAS point.
        return None

    def complete(self, pairing_id: str, entry: BrokerEntry) -> None:
        with self._lock:
            existing = self._entries.get(pairing_id)
            if existing is None:
                self._entries[pairing_id] = entry
                return
            # Idempotent: same value is fine.
            if existing.user_id == entry.user_id and existing.pairing_key == entry.pairing_key:
                return
            raise BrokerStorageConflictError(
                f"pairing_id {pairing_id} already completed with different values",
            )

    def fetch(self, pairing_id: str) -> BrokerEntry | None:
        with self._lock:
            return self._entries.get(pairing_id)


__all__ = [
    "BrokerEntry",
    "BrokerStorage",
    "BrokerStorageConflictError",
    "InMemoryBrokerStorage",
]
