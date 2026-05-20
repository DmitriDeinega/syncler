# M1.8 — M1 round-up tests + dev infrastructure

You completed M1.1–M1.7. Final M1 sub-task: testing infrastructure that lets the test suite actually run end-to-end with a real Postgres (or compatible) and tests covering the integration points between auth, devices, rate limiting, and retention.

## Scope

### Test infrastructure
- `server/tests/conftest.py` — NEW: pytest fixtures
  - `event_loop` — function-scope event loop (pytest-asyncio compatibility)
  - `test_settings` — `Settings` instance with test JWT secret + a test DATABASE_URL pointing to an isolated test DB
  - `engine` — async engine bound to test DB, runs migrations on setup, drops all tables on teardown (use `Base.metadata.create_all` + `drop_all` via `engine.begin()` — bypass Alembic for test speed)
  - `db_session` — `AsyncSession` rolled-back after each test (transactional fixture)
  - `app_client` — `httpx.AsyncClient` with ASGITransport, overrides `get_db` dependency to use the test session

### Docker compose for local dev
- `server/docker-compose.yml` — Postgres 16 service exposed on 5432 with `POSTGRES_USER=syncler POSTGRES_PASSWORD=syncler POSTGRES_DB=syncler`; data in a named volume
- `server/README.md` — UPDATE: section "Local development" — `docker compose up -d` + `alembic upgrade head` + `pytest` + `uvicorn app.main:app --reload`

### Integration tests
- `server/tests/test_integration_auth_devices.py` — NEW: walks the full flow:
  1. Signup with email + auth_key_hash + encrypted_master_key
  2. Login, capture session_token
  3. Enroll a device using the session token
  4. List devices, confirm the enrolled one is there
  5. Revoke the device
  6. Delete the account, confirm cascade by re-logging-in fails with 401
- `server/tests/test_integration_rate_limit.py` — NEW: hammer the signup route from the same IP, confirm 429 after threshold; advance the clock past the window, confirm reset
- `server/tests/test_integration_retention.py` — NEW: insert messages with various `expires_at`, run `prune_expired`, confirm only expired ones are removed

### Migration sanity test
- `server/tests/test_migration.py` — NEW: runs `alembic upgrade head` against a fresh test DB then `alembic downgrade base`, confirms both succeed and that `Base.metadata.tables.keys()` matches the tables created. (This catches migration/model drift.)

## Constraints

- All tests must pass against the docker-compose Postgres (assume it's running)
- Tests use a separate `syncler_test` DB; create+drop per test session
- For the rate-limit clock advance, use `freezegun` or `time-machine` (add to dev deps in pyproject.toml `[project.optional-dependencies] dev = [...]`)
- Don't reuse sessions across tests
- If you find any bugs in M1.1–M1.7 while writing these integration tests, fix them in the same exec and call it out in the summary

## Print summary
- Files created/modified
- Any bugs in earlier milestones you fixed (with file:line references)
- How to run the test suite locally
