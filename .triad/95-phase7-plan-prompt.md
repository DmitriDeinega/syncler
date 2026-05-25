=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Your ONLY output is a text reply to the
prompt below. You are FORBIDDEN from writing, editing, or running
any mutation tool (no git commit, no add, no checkout, no file
writes, no pip/npm install, etc.). You MAY read files, grep, and
inspect git history read-only. Reply text only.
=================================================================

# Consultation 95 — Phase 7 plan (V1.5 runtime #5: durable nonce-replay)

V1.5 ROADMAP item #5: "Durable nonce-replay protection. Move the
per-sender nonce registry from in-memory to Postgres so a worker
restart can't accept a replayed envelope."

## Current state (audit-confirmed)

- **Registry**: `server/app/crypto/nonce.py` — process-local
  `NonceRegistry` singleton. Per-sender `OrderedDict[bytes, None]`,
  LRU-capped at 100,000 entries per sender (`NONCE_REGISTRY_WINDOW`).
  Two-step API: `has(sender_id, nonce) -> bool` then
  `mark(sender_id, nonce)`. There's also a legacy `seen(...)`
  combined method.
- **Call sites**: `server/app/routers/messages.py` only. Lines
  119-149: `has()` returns 409 if replay; `mark()` AFTER
  `store_message()` returns. `server/app/routers/cards.py` does
  NOT replay-check at all (separate gap).
- **Tests**: `test_crypto.py` (LRU isolation), `test_messages.py`
  (`test_send_rejects_replayed_nonce`), `test_phase3.py` (uses
  the same `_reset_nonce_registry` fixture).
- **Sender side**: `sdk-python/syncler/crypto.py:random_nonce()`
  returns `secrets.token_bytes(12)`. Fresh per-message, never
  cached. No sender-side TTL.
- **Migrations**: Most recent is `0008_sender_bootstrap_key_and_pending_pairing_broker.py`.
  Next will be `0009_nonce_replay.py`.

## Failure model the ROADMAP item targets

Worker restart drops the in-memory dict → an attacker who
captured an envelope before the restart can resend it after the
restart and get accepted, because the new worker has never seen
that nonce. Multi-worker deployments have the same gap right now
(every worker has its own dict).

## Proposed plan

### Schema (new `0009_nonce_replay` migration)

```sql
CREATE TABLE nonce_replay (
    sender_id UUID NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sender_id, nonce)
);
CREATE INDEX nonce_replay_seen_at_idx ON nonce_replay (seen_at);
```

Composite PK gives atomic INSERT ON CONFLICT DO NOTHING semantics.
Index on `seen_at` for the TTL cleanup query.

### Atomic record-or-reject

Replace the two-step `has() + mark()` pair with a single
async method:

```python
async def record_nonce_or_reject(
    db: AsyncSession, sender_id: UUID, nonce: bytes,
) -> bool:
    """Returns True if the nonce was newly recorded (i.e. not a
    replay); False if the (sender_id, nonce) pair already
    existed. Atomic — race-free across concurrent workers."""
    stmt = insert(NonceReplay).values(...).on_conflict_do_nothing()
    result = await db.execute(stmt)
    return result.rowcount > 0
```

`messages.py` becomes:

```python
if not await record_nonce_or_reject(db, sender_id, nonce_bytes):
    raise HTTPException(409, "nonce_already_used")
# ... continue with store_message
```

No more separate `mark()` call — the insert IS the mark.

### TTL cleanup

Three options:

**A. On-write batch.** Every N inserts (say, 1000), the writer
also runs a `DELETE FROM nonce_replay WHERE seen_at < NOW() -
INTERVAL '...'`. Cheap; piggybacks on traffic; no separate
scheduler. Drawback: idle deployments don't clean up.

**B. Periodic FastAPI startup task.** A background `asyncio.Task`
running `DELETE` every N seconds. Drawback: only one worker
needs to do it but FastAPI gives every worker its own startup
hook — need a leader-election or `pg_try_advisory_lock` to
avoid all workers fighting.

**C. External cron / sidecar.** Document it as ops responsibility.
Drawback: ops surface.

I lean **A** for V1.5 simplicity. **B** is nicer architecturally
but adds locking complexity. **C** punts to docs.

TTL value: tie to the message-expiry window. Currently
`messages.py` uses `MESSAGE_EXPIRY_SECONDS` (need to check actual
const name). Nonces can safely be deleted once their associated
message has expired — that's the latest a sender could
legitimately replay (and the server would reject the message
itself on expiry grounds anyway).

### Cards.py scope question

`cards.py` (live-card upsert) doesn't replay-check. Live cards
have `sequence_number` CAS so replay is partially mitigated
(replaying a card with the same sequence_number gets rejected
for not being strictly greater). But replay of an OLDER
sequence_number could theoretically resurrect a stale card if
the current row was deleted.

I lean **scope cards.py IN** since the nonce machinery is right
there. But it's tangential to the ROADMAP item.

### NonceRegistry retention

Two paths:

**A. Delete the in-memory `NonceRegistry` entirely.** Cleanest.
The DB IS the registry now. Tests rewritten against the DB
fixture (already present in conftest for Phase 5d tests).

**B. Keep `NonceRegistry` as a fast L1 cache in front of the DB.**
Saves a round-trip on cache hits, but means two sources of
truth and a cache-invalidation problem on rollback. NOT WORTH
IT for V1.5.

I lean **A**.

### Backward compat

The migration touches no existing tables. Senders see no API
change. The only behavioral difference: after a worker restart,
a replay is now correctly rejected.

### Tests

- `server/tests/test_crypto.py`: rewrite
  `test_nonce_registry_detects_replay_and_isolates_senders` to
  use the new DB-backed call.
- `server/tests/test_messages.py`: `test_send_rejects_replayed_nonce`
  works as-is (already exercises the full path); just remove the
  `_reset_nonce_registry` fixture since there's no global state
  anymore. Add a new test asserting replay rejection ACROSS A
  WORKER RESTART (mock the lifecycle).
- `server/tests/test_phase3.py`: same fixture cleanup.
- New: `test_nonce_replay_is_atomic_under_concurrent_inserts` —
  fire N concurrent inserts of the same (sender, nonce), assert
  exactly one returns True.

## Open questions for reviewers

1. **TTL cleanup mechanism — A (on-write batch), B (background
   task with advisory lock), or C (ops cron)?** I lean A.
2. **Cards.py replay protection — scope in or punt to a separate
   phase?** I lean in.
3. **TTL value — tie to message expiry, or shorter/longer?**
   I lean tied (delete when message expired).
4. **Keep in-memory `NonceRegistry` as L1 cache?** I lean no
   (delete it entirely).
5. **Anything I'm missing about the multi-worker concurrent
   write semantics?** ON CONFLICT DO NOTHING with composite PK
   should handle it, but flag if there's a Postgres gotcha I
   should know about (e.g. with default-not-null seen_at +
   transaction isolation level).

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED on the plan.
2. Answers to the five open questions.
3. Anything missing.
4. Anything new (security, footgun).

If dual-GREEN, implement → code review → commit.

Reply text only. Do NOT call any write/mutation tool.
