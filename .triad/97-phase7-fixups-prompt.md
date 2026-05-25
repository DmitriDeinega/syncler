=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
=================================================================

# Consultation 97 — Phase 7 fix-ups (post-96)

Consultation 96 voted: **Gemini GREEN, Codex RED on tests +
YELLOW on three things**. This consultation reviews the
fix-ups.

## Codex 96 RED

> `_make_sender()` always uses `public_key=b"\x00" * 32`, but
> `senders.public_key` is unique. `test_isolates_senders` calls
> it twice in one test, so the second flush should violate
> `uq_senders_public_key`. This likely makes the new test
> file fail.

### Fix

`test_nonce_replay.py` has a module-level `_sender_pubkey_counter`
that increments per `_make_sender()` call. The bytes used are
`counter.to_bytes(32, "big")`, so every sender gets a unique
32-byte pub key. Doc-string in `_make_sender` calls out the
uniqueness invariant + the Codex 96 reference.

## Codex 96 YELLOWs

### 1. Model missing the `octet_length(nonce) = 12` CheckConstraint

The migration creates `ck_nonce_replay_nonce_length` but the ORM
model didn't. `test_migration.py` only compares table names, so
this would have been a silent metadata drift.

**Fix.** `NonceReplay.__table_args__` now declares the matching
`CheckConstraint`. Also imported `CheckConstraint` from
`sqlalchemy` at the top of `models.py`.

### 2. On-write cleanup can leave the transaction aborted

Original code wrapped `_cleanup_expired(db)` in a bare
`try/except Exception` — but if the DELETE fails in Postgres
with a statement-aborts-transaction error, swallowing it
doesn't restore the transaction. The subsequent `store_message`
/ `upsert_live_card` commit would then fail.

**Fix.** Wrap the cleanup in `db.begin_nested()` so the SAVEPOINT
rolls back automatically on exception, leaving the outer
transaction intact:

```python
if inserted and random.random() < _ONWRITE_CLEANUP_PROBABILITY:
    try:
        async with db.begin_nested():
            await _cleanup_expired(db)
    except Exception as exc:
        _log.warning("on-write nonce_replay cleanup failed: %s", exc)
```

### 3. Advisory-lock contention not actually tested

The existing `test_cleanup_with_advisory_lock_runs_once` only
hit the uncontended path. Codex noted the cross-session
contention wasn't exercised.

**Fix.** New
`test_cleanup_advisory_lock_contention_across_sessions`:
holds the lock in one session, calls
`cleanup_expired_with_lock` from a second session, asserts the
second call returns -1 (lock contended, not a row count).

## Codex 96 cosmetic (not fixed)

> Some new comments/docstrings appear mojibaked in the working
> tree, which is cosmetic but worth cleaning before commit.

The "mojibake" is em-dash `—` (U+2014) and section mark `§`
(U+00A7), both well-formed UTF-8 used pervasively across the
codebase since V1. Codex's terminal rendering on Windows is the
issue; the file bytes are correct. Skipping.

## Unused imports

Removed `NonceReplay` and `NONCE_REPLAY_TTL` from
`server/app/jobs/retention.py` — Codex 96 noted both were
imported but unused after the refactor.

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED on the four fixes.
2. Anything still missing.
3. Anything new.
4. Commit-readiness vote.

Reply text only. Do NOT call any write/mutation tool.
