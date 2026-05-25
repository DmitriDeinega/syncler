=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Reply with text only. You are FORBIDDEN
from any write/mutation tool (no git commit, no file writes,
no shell mutation, no pip/npm install). You MAY read files,
grep, and inspect git history read-only.
=================================================================

# Consultation 96 — Phase 7 code review (durable nonce-replay)

Phase 7 plan reached dual-GREEN at consult 95. This consultation
reviews the actual code.

## Files changed

### New / replaced

- **`server/alembic/versions/0009_nonce_replay.py`** — migration
  creates `nonce_replay` table. Composite PK
  `(sender_id, nonce)`, FK `sender_id -> senders.id ON DELETE
  CASCADE`, CHECK `octet_length(nonce) = 12`, index on
  `seen_at`. Downgrade drops both.
- **`server/app/models.py`** — new `NonceReplay` ORM class
  matching the migration shape.
- **`server/app/services/nonce_replay.py`** (NEW) — owns the
  public surface:
  - `record_nonce_or_reject(db, sender_id, nonce) -> bool` —
    INSERT ON CONFLICT DO NOTHING. Returns True if newly
    inserted (not a replay). Doc-string explains the
    transactional coupling (insert lives in caller's session;
    commits with `store_message` / `upsert_live_card`).
  - `_cleanup_expired(db) -> int` — DELETE older than
    `NONCE_REPLAY_TTL` (30 days).
  - `cleanup_expired_with_lock(db) -> int` — wraps the cleanup
    in `pg_try_advisory_lock` so multi-worker retention jobs
    don't fight; returns -1 if lock contended.
  - Best-effort on-write cleanup with 0.001 probability — every
    ~1000 successful inserts also runs a DELETE.
- **`server/app/crypto/nonce.py`** — `NonceRegistry`,
  `get_global_registry`, `reset_global_registry` ALL DELETED.
  Only `NONCE_BYTES` constant and `generate_nonce()` helper
  remain. Module doc-string points at the new service.

### Updated

- **`server/app/routers/messages.py`** — `has() + mark()` pair
  replaced with one `record_nonce_or_reject(...)` call BEFORE
  `store_message`. The legacy "mark after store" pattern is
  gone — the insert commits with `store_message`'s commit.
- **`server/app/routers/cards.py`** — same change applied to
  the upsert handler. Card-delete is OUT OF SCOPE per
  consultation 95 (tracked as a separate security item — no
  nonce / expiry / sequence on that envelope at all).
- **`server/app/jobs/retention.py`** — `prune_expired` now also
  calls `cleanup_expired_with_lock`. The summary dict gains a
  `nonce_replay` count.
- **`docs/crypto-spec.md §7`** — replaced the V1 LRU language
  with the V1.5 durable registry description. Explicit call-out
  that card-delete remains a replay gap.
- **`docs/ROADMAP.md`** V1.5 #5 — marked shipped with the
  durable-registry summary.

### Tests

- **`server/tests/test_nonce_replay.py`** (NEW) — 7 cases:
  - `test_first_insert_returns_true`
  - `test_replay_returns_false`
  - `test_isolates_senders`
  - `test_rejects_non_12_byte_nonce`
  - `test_cleanup_removes_only_expired_rows` (manually backdates
    seen_at via UPDATE to avoid waiting 30 days)
  - `test_concurrent_inserts_via_separate_sessions` (Codex 95
    missing-test — fires two `asyncio.gather`'d workers, each
    in its own `AsyncSession` bound to the engine; asserts
    exactly one returns True)
  - `test_cleanup_with_advisory_lock_runs_once`
- **`server/tests/test_crypto.py`** —
  `test_nonce_registry_detects_replay_and_isolates_senders`
  replaced with a tiny `test_generate_nonce_yields_12_bytes`
  since the legacy registry is gone.
- **`server/tests/test_messages.py`** + **`server/tests/test_phase3.py`**
  — `_reset_nonce_registry` autouse fixtures removed (the test
  DB fixture in conftest handles per-test isolation; each test
  uses distinct nonces / senders anyway). The existing
  `test_send_rejects_replayed_nonce` exercises the full
  POST→DB→POST→409 path against the new registry.

## Transactional lifecycle (Codex 95 concern)

The session lifecycle in `app/db.py:get_db()` is `async with
session_factory() as session: yield session`. SQLAlchemy
`AsyncSession.__aexit__` calls `close()` which rolls back any
uncommitted pending writes. `store_message` calls
`await db.commit()` at the end of its happy path. So:

- Replay attempt → `record_nonce_or_reject` returns False →
  handler 409s → session close rolls back the no-op (no row
  was inserted).
- Newly recorded nonce → `record_nonce_or_reject` returns True
  → `store_message` validates → validation raises (e.g.
  `PairingMissingError`) → exception bubbles out → session
  close rolls back the pending nonce insert. Sender can retry.
- Newly recorded nonce → `store_message` reaches its
  `await db.commit()` → both nonce row AND message row commit
  atomically.

The same applies to cards.py / `upsert_live_card`.

## Open questions

1. **Cards-delete replay gap.** Codex 95 flagged this as worse
   than upsert (no nonce, no expiry, no sequence). Tracked as
   backlog item, NOT fixed in Phase 7. Confirm this is the
   right scoping decision and not a release-blocker.

2. **On-write cleanup probability.** 1/1000 = ~one DELETE per
   1k message+card writes. Idle deployments rely on
   `app/jobs/retention.py`. Acceptable?

3. **Advisory lock key.** `0x4E4F4E43455F524C` (b'NONCE_RL').
   Arbitrary 64-bit constant. Any collision risk with other
   advisory locks in the codebase, present or planned?

4. **Test DB metadata vs alembic parity.** `test_migration.py`
   asserts `alembic_tables == Base.metadata.tables`. The new
   `NonceReplay` model + the new migration both add the same
   table — should pass. Did not run pytest in this dev env
   (Postgres isn't running here), so this is unverified. Flag
   if you spot an obvious metadata/migration mismatch.

## Output

Per reviewer, per file area (model + migration, service, routers,
retention, docs, tests):
1. GREEN / YELLOW / RED.
2. Anything missing from the consult 95 plan.
3. Anything new (security, footgun, performance).
4. Commit-readiness vote.

Reply text only. Do NOT call any write/mutation tool.
