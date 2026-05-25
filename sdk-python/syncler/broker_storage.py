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


class UnknownPairingIdError(Exception):
    """Phase 6: raised by `complete()` when the `pairing_id` was never
    reserved by a prior `reserve()` call. The broker handler maps this
    to HTTP 404 (opaque) so an attacker who knows the public bootstrap
    key can't get past the pending check by minting a valid envelope
    for a random uuid4 — the pairing must have been initiated by a
    legitimate `Client.create_pairing_qr(sender_broker_url=...)` call
    first.

    NOTE this is a Protocol-breaking change for third-party
    `BrokerStorage` implementations that landed in V1.5: custom stores
    must now track pending IDs atomically alongside completed entries,
    OR they must accept this behavior change (no silent fall-through
    when complete is called for an unreserved ID).
    """


class BrokerStorage(Protocol):
    """The broker storage protocol.

    Implementations MUST be thread-safe AND compare-and-set safe:
    `complete(...)` is the syncronization point. The first call wins;
    subsequent calls with identical values are idempotent; calls with
    different values raise [BrokerStorageConflictError]. This semantics
    is what defeats envelope replay (spec §9.3).

    Phase 6 — pending-pairing registry. The protocol now tracks
    *pending* pairing IDs in addition to completed entries:

    - `Client.create_pairing_qr(sender_broker_url=...)` calls
      `reserve(pairing_id)` so the broker side knows which IDs are
      legitimately in flight.
    - The broker handler calls `is_reserved(pairing_id)` BEFORE
      attempting decrypt; unknown IDs are rejected with HTTP 404.
    - `complete()` raises [UnknownPairingIdError] when called for an
      ID that was never reserved (defense-in-depth — the handler
      check should already have fired, but a buggy caller or race
      shouldn't silently complete an unknown slot).

    This closes the "V1.5 fixed-config deviation" documented in
    earlier crypto-spec drafts.
    """

    def reserve(self, pairing_id: str) -> None:
        """Mark `pairing_id` as a legitimate pending slot. Called by
        `Client.create_pairing_qr(sender_broker_url=...)` after the
        Syncler server issues the pairing_id, so the broker side can
        distinguish "real pending pairing" from "attacker-minted
        envelope for a random UUID".

        Idempotent (re-reserving an already-reserved or already-
        completed ID is a no-op). Implementations MUST be atomic at
        the storage layer.
        """

    def is_reserved(self, pairing_id: str) -> bool:
        """Returns ``True`` if `pairing_id` was previously reserved
        OR has already been completed. Used by the broker handler
        for the pre-decrypt 404 gate.

        Returning True for completed IDs is intentional — `complete()`
        itself enforces the CAS, so a re-POST for an idempotent or
        conflicting completion needs to reach `complete()` to get the
        right 200/409 response (not get bounced as 404).
        """

    def complete(self, pairing_id: str, entry: BrokerEntry) -> bool:
        """Compare-and-set. Returns ``True`` when this call was the
        first completion (the storage was previously empty for this
        ``pairing_id``); returns ``False`` on idempotent replay of the
        same values.

        - First call for a reserved `pairing_id` → stores entry, returns ``True``.
        - Second call with same `entry` → idempotent, returns ``False``.
        - Second call with different `entry` →
          [BrokerStorageConflictError].
        - Call for a `pairing_id` that was never reserved →
          [UnknownPairingIdError].

        The FastAPI broker uses the return value to distinguish HTTP
        201 (first completion) from 200 (idempotent replay).

        Implementations MUST be atomic at the storage layer (Postgres
        UPSERT WHERE NOT EXISTS or equivalent — NOT a Python-level
        check-then-store)."""

    def fetch(self, pairing_id: str) -> BrokerEntry | None:
        """Returns the completed entry or `None` if still pending.
        Called repeatedly by `Client.wait_for_pairing(...)`."""


class InMemoryBrokerStorage:
    """Default in-process implementation. Suitable for dev / tests /
    single-process toy deployments. Loses state on restart. NOT safe
    across multiple uvicorn workers AND NOT safe across multiple
    Python processes — each worker has its own state, so a `reserve()`
    from the sender's Client process won't be visible to a broker
    running in a separate worker. Single-process deployments (Client
    + broker app in the same Python — e.g. the trading-bot example
    via a background thread) work fine. Production multi-worker
    setups need a real storage backend (Redis, Postgres).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._entries: dict[str, BrokerEntry] = {}
        self._reserved: set[str] = set()

    def reserve(self, pairing_id: str) -> None:
        """Mark a pairing_id as pending. Idempotent; reserving an
        already-completed pairing is also a no-op (an immediate
        re-pair after wait_for_pairing returned would otherwise be
        racy)."""
        with self._lock:
            self._reserved.add(pairing_id)

    def is_reserved(self, pairing_id: str) -> bool:
        """True if pairing_id was reserved OR already completed."""
        with self._lock:
            return pairing_id in self._reserved or pairing_id in self._entries

    def complete(self, pairing_id: str, entry: BrokerEntry) -> bool:
        with self._lock:
            # Defense-in-depth: handler already checks is_reserved
            # before reaching here. A buggy caller or race shouldn't
            # silently complete an unreserved ID — flag loudly.
            if (
                pairing_id not in self._reserved
                and pairing_id not in self._entries
            ):
                raise UnknownPairingIdError(
                    f"pairing_id {pairing_id} was never reserved",
                )
            existing = self._entries.get(pairing_id)
            if existing is None:
                self._entries[pairing_id] = entry
                return True
            # Idempotent: same value is fine.
            if existing.user_id == entry.user_id and existing.pairing_key == entry.pairing_key:
                return False
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
    "UnknownPairingIdError",
]
