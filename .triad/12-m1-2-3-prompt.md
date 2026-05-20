# M1.2 + M1.3 — Database + Alembic + initial schema migration

You authored M1.1 (server scaffold). Workspace-write granted to `d:\Projects\syncler\`. Touch only `server/`.

## Scope of this exec

Add database support to the existing FastAPI scaffold, plus the FULL initial schema as one Alembic migration.

### M1.2 — DB connection + Alembic plumbing
- Add `server/app/db.py` with SQLAlchemy 2 async engine, `AsyncSession` factory, and FastAPI dependency `get_db() -> AsyncIterator[AsyncSession]`.
- Initialize Alembic in `server/alembic/` (set up `alembic.ini`, `env.py` configured for async + reading `DATABASE_URL` from `app.config.Settings`, `script.py.mako`).
- Wire engine to be created on FastAPI startup, disposed on shutdown.

### M1.3 — initial schema (one migration named `0001_initial_schema`)

All tables in one migration. Schema per the V1 contract (`d:\Projects\syncler\.triad\08-codex-final-plan.txt`):

- `users(id UUID PK, email TEXT UNIQUE NOT NULL, auth_key_hash BYTEA NOT NULL, encrypted_master_key BYTEA NOT NULL, auth_salt BYTEA NOT NULL, argon2_params_version INT NOT NULL, created_at TIMESTAMPTZ DEFAULT now())`
- `devices(id UUID PK, user_id UUID FK->users.id ON DELETE CASCADE, public_key BYTEA NOT NULL, fcm_token TEXT, revoked_at TIMESTAMPTZ, last_seen TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now())` — index on `user_id`
- `senders(id UUID PK, public_key BYTEA NOT NULL UNIQUE, name TEXT NOT NULL, contact TEXT, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now())`
- `pairings(id UUID PK, user_id UUID FK->users.id ON DELETE CASCADE, sender_id UUID FK->senders.id ON DELETE CASCADE, encrypted_state BYTEA NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now())` — UNIQUE (user_id, sender_id), index on both FKs
- `plugins(id UUID PK, sender_id UUID FK->senders.id, version TEXT NOT NULL, manifest_hash BYTEA NOT NULL, bundle_hash BYTEA NOT NULL, signature BYTEA NOT NULL, signed_bundle_url TEXT NOT NULL, capabilities JSONB NOT NULL, endpoints JSONB NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now())` — UNIQUE (sender_id, version)
- `messages(id UUID PK, sender_id UUID FK->senders.id, user_id UUID FK->users.id ON DELETE CASCADE, plugin_id UUID FK->plugins.id, encrypted_body_pointer TEXT NOT NULL, min_plugin_version TEXT, expires_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ DEFAULT now())` — index on `(user_id, sent_at DESC)`, index on `expires_at` for pruning
- `delivery_status(message_id UUID FK->messages.id ON DELETE CASCADE, device_id UUID FK->devices.id ON DELETE CASCADE, delivered_at TIMESTAMPTZ, dismissed_at TIMESTAMPTZ, actioned_at TIMESTAMPTZ, PRIMARY KEY (message_id, device_id))`
- `encrypted_user_state(user_id UUID PK FK->users.id ON DELETE CASCADE, state_version INT NOT NULL DEFAULT 0, encrypted_blob BYTEA NOT NULL, updated_at TIMESTAMPTZ DEFAULT now())`
- `rate_limit_events(id BIGSERIAL PK, actor_type TEXT NOT NULL, actor_id TEXT NOT NULL, route TEXT NOT NULL, window_start TIMESTAMPTZ NOT NULL, count INT NOT NULL DEFAULT 1)` — UNIQUE (actor_type, actor_id, route, window_start), index on `window_start` for cleanup

### Files to create
- `server/app/db.py` — async engine, sessionmaker, `get_db` dependency, `init_engine()` / `dispose_engine()` for lifecycle
- `server/app/models.py` — SQLAlchemy 2 declarative `Base` class + all model classes mirroring the schema above (use `Mapped[UUID]`, `mapped_column(...)`, type-annotated)
- `server/alembic.ini` — standard with `sqlalchemy.url` pulled from env at runtime (not embedded)
- `server/alembic/env.py` — async migrations, imports `Base.metadata` from `app.models`, reads URL from `Settings`
- `server/alembic/script.py.mako` — default Alembic template
- `server/alembic/versions/0001_initial_schema.py` — the migration creating all 9 tables, all indexes, all UNIQUE constraints, all FK CASCADEs
- `server/app/main.py` — UPDATE to add startup/shutdown hooks calling `init_engine()` / `dispose_engine()`; do NOT add new routes yet (M1.4+)
- `server/tests/test_db.py` — one async test that confirms an engine can be created and `SELECT 1` works against an in-memory SQLite for now (or skip if SQLite doesn't support all features — in that case the test should be `@pytest.mark.skip(reason="needs postgres")` with an explanatory docstring)

### Constraints

- Python 3.12 syntax (`X | None`, `list[X]`)
- SQLAlchemy 2.0 style only (`Mapped`, `mapped_column`, NOT legacy `Column(...)` assignments in `Base`)
- Async everywhere (engine, session, migrations)
- UUIDs use `sqlalchemy.dialects.postgresql.UUID` typed as Python `uuid.UUID`
- JSONB columns typed as `dict` in Python
- Default timestamps use `func.now()` from `sqlalchemy.sql`
- The migration file should be hand-written (don't autogenerate — autogenerate needs a live DB; just write the `op.create_table(...)` calls cleanly)
- All FKs ON DELETE CASCADE where the row is meaningless without parent (devices, pairings, messages, encrypted_user_state, delivery_status); senders/plugins keep their data on user delete (they're shared records)

### Do NOT
- Don't add API routes
- Don't add auth logic
- Don't run migrations or install anything
- Don't touch `.triad/` or repo root
- Don't write a docker-compose for postgres yet (M1.8 if needed)

### After files created
Print summary:
- Files created (paths only, no content dump)
- Anything where SQLAlchemy 2 / Alembic conventions forced a deviation from the spec
- One-line confirmation: "Migration is hand-written, not autogenerated."
