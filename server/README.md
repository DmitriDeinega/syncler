# Syncler Server

## Quickstart

Install dependencies with uv:

```sh
uv sync
```

Or with pip:

```sh
python -m pip install -e .
```

Create a local environment file:

```sh
cp .env.example .env
```

Set `JWT_SECRET` in `.env`, then run the development server:

```sh
uvicorn app.main:app --reload
```

The API health check is available at `/health`.
OpenAPI docs are available at `/docs`.

## Background jobs

Run expired-data pruning as an external cron-style job:

```sh
python -m app.jobs.retention
```

The process deletes expired messages, revoked pairings older than 180 days, and stale rate limit events. Schedule it outside the app process, for example with AWS EventBridge or a systemd timer.
