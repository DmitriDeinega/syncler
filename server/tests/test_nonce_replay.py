"""Phase 7 — durable per-sender nonce-replay registry tests.

Schema-level + concurrency tests for the new Postgres-backed
nonce-replay table. The end-to-end replay rejection through
`POST /v1/messages/send` is covered by
`test_messages.py::test_send_rejects_replayed_nonce`; this file
focuses on the storage primitive itself.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from app.models import NonceReplay, Sender
from app.services.nonce_replay import (
    NONCE_REPLAY_TTL,
    _cleanup_expired,
    cleanup_expired_with_lock,
    record_nonce_or_reject,
)


_sender_pubkey_counter = 0


async def _make_sender(db: AsyncSession) -> uuid.UUID:
    """Create a minimal sender row so the FK constraint on
    nonce_replay.sender_id resolves. Each call gets a fresh
    public_key — the `senders` table enforces uniqueness on that
    column, so reusing a constant byte string across calls in the
    same test would fail (Codex 96 RED).
    """
    global _sender_pubkey_counter
    _sender_pubkey_counter += 1
    sender = Sender(
        id=uuid.uuid4(),
        name=f"Nonce Test {_sender_pubkey_counter}",
        public_key=_sender_pubkey_counter.to_bytes(32, "big"),
    )
    db.add(sender)
    await db.flush()
    return sender.id


@pytest.mark.asyncio
async def test_first_insert_returns_true(db_session: AsyncSession) -> None:
    sender_id = await _make_sender(db_session)
    assert await record_nonce_or_reject(db_session, sender_id, b"a" * 12) is True
    row = await db_session.scalar(
        select(NonceReplay).where(NonceReplay.sender_id == sender_id),
    )
    assert row is not None
    assert row.nonce == b"a" * 12


@pytest.mark.asyncio
async def test_replay_returns_false(db_session: AsyncSession) -> None:
    sender_id = await _make_sender(db_session)
    nonce = b"b" * 12
    assert await record_nonce_or_reject(db_session, sender_id, nonce) is True
    # Same (sender, nonce) → ON CONFLICT DO NOTHING returns rowcount 0.
    assert await record_nonce_or_reject(db_session, sender_id, nonce) is False


@pytest.mark.asyncio
async def test_isolates_senders(db_session: AsyncSession) -> None:
    sender_a = await _make_sender(db_session)
    sender_b = await _make_sender(db_session)
    nonce = b"c" * 12
    assert await record_nonce_or_reject(db_session, sender_a, nonce) is True
    # Same nonce, different sender → not a replay.
    assert await record_nonce_or_reject(db_session, sender_b, nonce) is True


@pytest.mark.asyncio
async def test_rejects_non_12_byte_nonce(db_session: AsyncSession) -> None:
    sender_id = await _make_sender(db_session)
    with pytest.raises(ValueError, match="12 bytes"):
        await record_nonce_or_reject(db_session, sender_id, b"short")
    with pytest.raises(ValueError, match="12 bytes"):
        await record_nonce_or_reject(db_session, sender_id, b"a" * 16)


@pytest.mark.asyncio
async def test_cleanup_removes_only_expired_rows(db_session: AsyncSession) -> None:
    sender_id = await _make_sender(db_session)
    # Insert one fresh + one stale row. Manually backdate the stale
    # row's seen_at past NONCE_REPLAY_TTL via UPDATE so we don't have
    # to wait 30 days.
    await record_nonce_or_reject(db_session, sender_id, b"f" * 12)
    await record_nonce_or_reject(db_session, sender_id, b"o" * 12)
    stale_cutoff = datetime.now(UTC) - NONCE_REPLAY_TTL - timedelta(hours=1)
    from sqlalchemy import update
    await db_session.execute(
        update(NonceReplay)
        .where(NonceReplay.nonce == b"o" * 12)
        .values(seen_at=stale_cutoff),
    )
    await db_session.flush()

    deleted = await _cleanup_expired(db_session)
    assert deleted == 1

    remaining = await db_session.scalars(
        select(NonceReplay).where(NonceReplay.sender_id == sender_id),
    )
    nonces = [r.nonce for r in remaining]
    assert b"f" * 12 in nonces
    assert b"o" * 12 not in nonces


@pytest.mark.asyncio
async def test_concurrent_inserts_via_separate_sessions(
    engine: AsyncEngine,
) -> None:
    """Codex 95 missing-test: prove cross-worker safety. Two
    independent sessions racing the same (sender, nonce) must produce
    exactly one True and one False, regardless of which lands first.
    """
    # Bootstrap the sender via a third session committed up front so
    # both racing sessions can FK-reference it.
    async with AsyncSession(bind=engine, expire_on_commit=False) as setup:
        sender = Sender(
            id=uuid.uuid4(),
            name="Concurrent Test",
            public_key=b"\x01" * 32,
        )
        setup.add(sender)
        await setup.commit()
        sender_id = sender.id

    nonce = b"r" * 12

    async def worker() -> bool:
        async with AsyncSession(bind=engine, expire_on_commit=False) as s:
            result = await record_nonce_or_reject(s, sender_id, nonce)
            await s.commit()
            return result

    # Fire two concurrent workers. The Postgres unique constraint
    # serializes them — first commit wins; second sees the conflict
    # and returns False.
    a, b = await asyncio.gather(worker(), worker())
    # Exactly one of the two should claim the insert.
    assert sorted([a, b]) == [False, True]


@pytest.mark.asyncio
async def test_cleanup_with_advisory_lock_runs_once(
    db_session: AsyncSession,
) -> None:
    """Uncontended-path test: lock plumbing returns a real row count,
    not -1, when no other session holds the lock.
    """
    sender_id = await _make_sender(db_session)
    await record_nonce_or_reject(db_session, sender_id, b"x" * 12)
    deleted = await cleanup_expired_with_lock(db_session)
    # All entries fresh → 0 deletions, but NOT -1 (which would mean
    # the lock was contended).
    assert deleted >= 0


@pytest.mark.asyncio
async def test_cleanup_advisory_lock_contention_across_sessions(
    engine: AsyncEngine,
) -> None:
    """Codex 96 RED: prove the advisory lock actually serializes
    concurrent cleanups. Hold the lock in one session, then call
    `cleanup_expired_with_lock` from a separate session — it MUST
    return -1 (lock contended), not a row count.
    """
    async with AsyncSession(bind=engine, expire_on_commit=False) as holder:
        # Acquire the same advisory lock the cleanup uses.
        from app.services.nonce_replay import (
            _RETENTION_ADVISORY_LOCK_KEY,
        )
        await holder.execute(
            text("SELECT pg_advisory_lock(:k)"),
            {"k": _RETENTION_ADVISORY_LOCK_KEY},
        )
        try:
            async with AsyncSession(bind=engine, expire_on_commit=False) as other:
                result = await cleanup_expired_with_lock(other)
                assert result == -1, (
                    f"expected -1 (lock contended) but got {result}"
                )
        finally:
            await holder.execute(
                text("SELECT pg_advisory_unlock(:k)"),
                {"k": _RETENTION_ADVISORY_LOCK_KEY},
            )
