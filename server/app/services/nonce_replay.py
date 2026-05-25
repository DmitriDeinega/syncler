"""Phase 7 — durable per-sender nonce-replay registry.

Replaces the in-memory `NonceRegistry` (`app/crypto/nonce.py`) with a
Postgres-backed implementation that survives worker restarts and
synchronizes across multiple uvicorn workers.

The public surface is one async function, `record_nonce_or_reject`,
which performs an atomic INSERT ON CONFLICT DO NOTHING against the
`nonce_replay` table. Callers replace the legacy ``has() + mark()``
pair with a single call:

    if not await record_nonce_or_reject(db, sender_id, nonce):
        raise HTTPException(409, "nonce already used")

Transaction lifecycle (critical — see consultation 95):
- The insert lives in the *same* AsyncSession the caller passes in.
- ``store_message`` / ``store_card`` commit the session internally
  when they succeed, so the nonce row commits ALONGSIDE the message
  / card row in one atomic step.
- If the caller's downstream operation raises before that commit,
  FastAPI's request-scoped session lifecycle (``app/db.py:get_db``)
  closes the session on the way out, rolling back the pending nonce
  insert. The sender can then retry the same nonce on the next
  request — replay protection is only "real" once the surrounding
  message / card actually persists.

Retention:
- Hard TTL = `MAX_RETENTION` (30 days). Envelopes older than that
  would already be rejected by the message / card expiry checks,
  so the registry can safely forget them.
- Cleanup is hybrid: best-effort on-write batch in this module
  (cheap, traffic-driven) plus a periodic task in
  ``app/jobs/retention.py`` (covers idle deployments and runs
  under a Postgres advisory lock so multi-worker deployments
  don't fight over the DELETE).
"""

from __future__ import annotations

import logging
import random
from datetime import UTC, datetime, timedelta
from typing import Final
from uuid import UUID

from sqlalchemy import delete, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.nonce import NONCE_BYTES
from app.models import NonceReplay

_log = logging.getLogger(__name__)

# Tied to message + card MAX_RETENTION (30 days). See
# `services/messages.py:MAX_RETENTION` and `services/cards.py:MAX_TTL`
# — the latter is 48h, the former is the upper bound on accepted
# envelope lifetime. 30 days is mathematically safe for both since
# any envelope older than `now - 30d` would already fail the
# `expires_at <= now` check at the service layer.
NONCE_REPLAY_TTL: Final[timedelta] = timedelta(days=30)

# Probability that any given successful insert also runs an on-write
# cleanup of expired nonces. 1/1000 = ~one DELETE per 1k writes,
# which is enough to keep the table from growing unboundedly on
# active deployments without forcing an HTTP request to absorb the
# latency of a bulk DELETE every time.
_ONWRITE_CLEANUP_PROBABILITY: Final[float] = 0.001


class NonceReplayError(Exception):
    """Raised by ``record_nonce_or_reject`` is the caller wants an
    exception instead of a False return; not currently used in the
    routers (they prefer the boolean form for tighter HTTP mapping)."""


async def record_nonce_or_reject(
    db: AsyncSession,
    sender_id: UUID,
    nonce: bytes,
) -> bool:
    """Atomically record a (sender_id, nonce) pair if it's not already
    present. Returns True if the row was newly inserted (i.e. NOT a
    replay); False if the (sender_id, nonce) pair was already in the
    registry.

    The insert is added to the caller's session but NOT committed —
    the caller's surrounding flow (``store_message``, ``store_card``,
    etc.) commits the session along with its own writes. This couples
    nonce persistence to the actual side effect, so a failed-after-
    nonce-recorded scenario rolls back the nonce too and the sender
    can legitimately retry.

    Raises:
        ValueError: if ``nonce`` is not exactly 12 bytes (the
            AES-GCM nonce length the rest of the system uses).
    """
    if len(nonce) != NONCE_BYTES:
        raise ValueError(f"nonce must be {NONCE_BYTES} bytes, got {len(nonce)}")

    stmt = (
        pg_insert(NonceReplay)
        .values(sender_id=sender_id, nonce=nonce)
        .on_conflict_do_nothing(index_elements=["sender_id", "nonce"])
    )
    result = await db.execute(stmt)
    inserted = (result.rowcount or 0) > 0

    # Best-effort on-write cleanup. Fire under low probability so we
    # don't penalize every request with a DELETE. Idle deployments
    # rely on the retention job (`app/jobs/retention.py`).
    #
    # The cleanup runs inside a SAVEPOINT (Codex 96 YELLOW) so a
    # DELETE failure rolls back ONLY the cleanup, not the surrounding
    # caller transaction. Without the SAVEPOINT, a Postgres statement
    # error would leave the outer transaction in an aborted state
    # and the subsequent `store_message` / `upsert_live_card` commit
    # would fail — exactly the request-killing behavior we want to
    # avoid.
    if inserted and random.random() < _ONWRITE_CLEANUP_PROBABILITY:
        try:
            async with db.begin_nested():
                await _cleanup_expired(db)
        except Exception as exc:
            # Outer transaction is intact (SAVEPOINT rolled back).
            # Log + swallow — cleanup is opportunistic.
            _log.warning("on-write nonce_replay cleanup failed: %s", exc)

    return inserted


async def _cleanup_expired(db: AsyncSession) -> int:
    """Delete `nonce_replay` rows older than NONCE_REPLAY_TTL.
    Returns the number of rows deleted. Safe to call concurrently
    across workers — each invocation operates on a distinct snapshot
    via ``ix_nonce_replay_seen_at``.
    """
    cutoff = datetime.now(UTC) - NONCE_REPLAY_TTL
    stmt = delete(NonceReplay).where(NonceReplay.seen_at < cutoff)
    result = await db.execute(stmt)
    deleted = result.rowcount or 0
    if deleted:
        _log.info("nonce_replay cleanup: deleted %d expired rows", deleted)
    return deleted


# Postgres advisory-lock key for the retention job. Arbitrary 64-bit
# integer; needs to be stable across workers so they all try the
# same lock. Use a hash of the constant name so it's distinct from
# whatever other advisory locks the codebase grows.
_RETENTION_ADVISORY_LOCK_KEY: Final[int] = 0x4E4F4E43455F524C  # b'NONCE_RL'


async def cleanup_expired_with_lock(db: AsyncSession) -> int:
    """Periodic cleanup entry point for ``app/jobs/retention.py``.

    Acquires a Postgres advisory lock so only one worker runs the
    DELETE per invocation (other workers no-op). Returns the number
    of rows deleted, or -1 if another worker held the lock.

    Multi-worker safe: pg_try_advisory_lock returns False without
    blocking when contended. Advisory locks are released on session
    close (transaction-level) or on explicit pg_advisory_unlock.
    """
    result = await db.execute(
        text("SELECT pg_try_advisory_lock(:k)"),
        {"k": _RETENTION_ADVISORY_LOCK_KEY},
    )
    got_lock = bool(result.scalar())
    if not got_lock:
        return -1
    try:
        return await _cleanup_expired(db)
    finally:
        await db.execute(
            text("SELECT pg_advisory_unlock(:k)"),
            {"k": _RETENTION_ADVISORY_LOCK_KEY},
        )
