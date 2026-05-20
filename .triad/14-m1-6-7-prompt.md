# M1.6 + M1.7 — Rate limiting middleware + retention pruning job

You have already authored M1.1–M1.5. Touch only `server/`.

## M1.6 — Rate limiting middleware

Token-bucket style limits backed by the `rate_limit_events` table.

### Design
- A `RateLimitConfig` dataclass: `(name: str, max_count: int, window_seconds: int)`
- `app/middleware/rate_limit.py` with a function `rate_limit(name: str)` returning a FastAPI dependency that:
  1. Identifies the actor: `(actor_type, actor_id)` — for unauthenticated routes use IP (`actor_type="ip"`); for authenticated routes use `user_id` (`actor_type="user"`); for senders posting messages use `sender_id` (`actor_type="sender"`)
  2. Computes the current window: `window_start = floor(now / window_seconds) * window_seconds`
  3. Upserts into `rate_limit_events` (ON CONFLICT DO UPDATE count = count + 1)
  4. Reads the resulting count; if > `max_count` → `HTTPException(429, "rate limited", headers={"Retry-After": ...})`
- A central config dict in `app/middleware/rate_limit_config.py` mapping route name → `RateLimitConfig`. Initial config:
  - `login`: max=5 per 60s per (ip + email — but for simplicity, just IP since email is in body)
  - `signup`: max=3 per 60s per IP
  - `pairing_initiate`: max=10 per 60s per sender
  - `message_send`: max=60 per 60s per sender; AND max=600 per hour per (sender, user) — the dependency should compose multiple limits via stacking
  - `manifest_fetch`: max=30 per 60s per device
  - `action_callback`: max=120 per 60s per pairing
- Wire `rate_limit("login")` as `Depends(...)` on the login route (it already accepted a placeholder Depends in M1.4 — replace with the real one)
- Same for signup, pairing routes (M1.5+), message send (M2+), etc. For routes not yet authored, leave a TODO comment.

### Files
- `app/middleware/__init__.py` — NEW
- `app/middleware/rate_limit.py` — NEW
- `app/middleware/rate_limit_config.py` — NEW
- `app/routers/auth.py` — UPDATE: wire `rate_limit("login")` and `rate_limit("signup")` Depends on the relevant routes
- `tests/test_rate_limit.py` — NEW: test that exceeding the configured login limit returns 429 with Retry-After

### Constraints
- Use raw SQL `INSERT ... ON CONFLICT ... DO UPDATE SET count = count + 1 RETURNING count` for atomic increment-and-read (Postgres-specific). Wrap in `text(...)` or use SQLAlchemy core.
- For the SQLite test path, fall back to `SELECT + UPDATE` (less atomic but acceptable for tests).
- Don't add Redis. The DB-backed limiter is V1; faster backends are V1.5.

## M1.7 — Retention pruning job

Background job that deletes expired data per the V1 retention policy.

### Retention targets (per V1 contract)
- Messages: undismissed older than 30d delete; dismissed older than 7d delete
- Delivery_status: cascades with message delete (no separate work)
- Pairing audit metadata: 180 days from `created_at` if `revoked_at IS NOT NULL`
- Revoked sender/plugin records: NEVER deleted (blocklist)
- Rate limit events: older than 7 days delete (cleanup, not retention-policy-driven)

### Design
- `app/jobs/__init__.py` — NEW
- `app/jobs/retention.py` — NEW with function `prune_expired(session: AsyncSession) -> dict[str, int]` that:
  1. Deletes messages where (`dismissed_at IS NULL AND sent_at < now() - 30d`) OR (`dismissed_at IS NOT NULL AND dismissed_at < now() - 7d`). Use `delivery_status.dismissed_at` joined via subquery — actually simpler: messages have `expires_at` set by sender; check that field directly. For V1, retention is enforced via `expires_at` set at send time (sender SDK sets it). Pruning job deletes `messages WHERE expires_at < now()`.
  2. Deletes revoked pairings with `revoked_at < now() - 180d`
  3. Deletes rate_limit_events where `window_start < now() - 7d`
  4. Returns dict like `{"messages": 12, "pairings": 0, "rate_limit_events": 245}`

### Scheduling
- For V1, the pruning job runs as a separate process invoked by a cron-style call. Add an entrypoint `python -m app.jobs.retention`.
- Don't add APScheduler or background worker frameworks. The user deploys the cron externally (e.g., AWS EventBridge or a systemd timer).
- `app/jobs/retention.py` should have:
  ```python
  if __name__ == "__main__":
      asyncio.run(_main())
  ```
  where `_main()` opens a session, calls `prune_expired`, prints the summary, exits.

### Files
- `app/jobs/__init__.py` — NEW
- `app/jobs/retention.py` — NEW with `prune_expired` + CLI entry
- `server/README.md` — UPDATE: add a "Background jobs" section mentioning `python -m app.jobs.retention` and that it should be cron'd externally
- `tests/test_retention.py` — NEW: insert a message with `expires_at = now() - 1h`, run prune, assert deletion

## Constraints across both
- Async session usage everywhere
- Service layer pattern: pure async functions; routes / CLI entries are thin shells
- All new modules typed (use `Mapped`-style returns where possible)
- New deps if needed: probably none (SQLAlchemy + asyncio is enough); if you add aiosqlite for tests, add to pyproject.toml dev deps

## Print summary
- Files created/modified
- Any policy you adjusted from the spec (with reason)
