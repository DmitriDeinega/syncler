# M1.1 — Server scaffold (first commit of build phase)

You are the build lead (Round 9 vote). The triad approved you to author files directly (Round 10 vote, unanimous). Workspace-write is granted. The working directory is `d:\Projects\syncler\`. Write files only under `server/` — do not touch `.triad/` or anything at the repo root.

## Context

You authored the final V1 build plan (Round 8). M1 is "Server scaffold: FastAPI, DB schema, auth, JWT, rate limiting, retention jobs." M1 has been split into sub-tasks:

- **M1.1 (this exec)** — runnable scaffold + `/health` endpoint, no DB yet
- M1.2 — DB connection + Alembic
- M1.3 — initial schema migration with all tables
- M1.4 — auth signup/login endpoints
- M1.5 — device enrollment + revocation endpoints
- M1.6 — rate limiting middleware
- M1.7 — retention pruning job
- M1.8 — round-up tests for M1

## Scope of M1.1 — just this commit

Create a minimal runnable FastAPI project at `server/`. **No database. No auth. No business logic.** Just enough to:
- `pip install` (or `uv sync`) succeeds
- `uvicorn` starts the app
- `GET /health` returns 200 with a JSON body
- `pytest` runs one test that hits `/health`

## Files to create

- `server/pyproject.toml` — Python 3.12+, dependencies: `fastapi`, `uvicorn[standard]`, `sqlalchemy>=2`, `alembic`, `asyncpg`, `pydantic`, `pydantic-settings`, `argon2-cffi`, `cryptography`, `httpx`, `pytest`, `pytest-asyncio`. Pin to current stable versions you know exist (May 2026).
- `server/app/__init__.py` — empty or `__version__ = "0.1.0"`
- `server/app/main.py` — FastAPI app instance, `GET /health` returning `{"status": "ok", "version": "0.1.0"}`, no other routes
- `server/app/config.py` — `pydantic_settings.BaseSettings` subclass with at minimum: `database_url`, `jwt_secret`, `environment` ("development" / "production"). All from env, with sensible dev defaults where possible (no default for `jwt_secret` — must be set).
- `server/tests/__init__.py` — empty
- `server/tests/test_health.py` — async test using `httpx.AsyncClient` against the FastAPI app (use ASGI transport, not a real network port)
- `server/README.md` — quickstart only: how to install (uv preferred, pip fallback), how to set env (.env.example), how to run (`uvicorn app.main:app --reload`), where OpenAPI docs land (`/docs`)
- `server/Dockerfile` — Python 3.12-slim, COPY + install + uvicorn entrypoint (no multi-stage; keep minimal)
- `server/.env.example` — template with `DATABASE_URL`, `JWT_SECRET`, `ENVIRONMENT`

## Quality bar

- Python 3.12 syntax: use `X | None` not `Optional[X]`; use `list[X]` not `List[X]`
- async-first: all route handlers `async def` even when not awaiting, because future routes will
- pytest test uses `httpx.AsyncClient(transport=httpx.ASGITransport(app=app))` — no real port binding
- Linux line endings (LF), UTF-8
- No print statements; if you need logs, use `logging` module configured via FastAPI startup
- pyproject.toml uses `[project]` table (PEP 621), not legacy setup.py

## What NOT to do

- Don't write any DB code yet — that's M1.2
- Don't add authentication — that's M1.4
- Don't add rate limiting — that's M1.6
- Don't commit the changes — Claude will review the diff and create the commit
- Don't install dependencies or run anything — no network, no execution
- Don't touch `.triad/` or anything at the repo root
- Don't add a `LICENSE` file — defer

## After you create the files

Print a short summary to stdout:
- List of files you created (paths only)
- Any deviation from the spec or version pin you chose (2-3 lines max)
- The exact `uv` or `pip` command to install + the exact command to run the dev server

That's all. Claude will review your diff and either commit (if clean) or fire follow-up changes (if anything's off).
