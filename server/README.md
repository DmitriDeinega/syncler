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

## Local development

Start Postgres:

```sh
docker compose up -d
```

Install dependencies, including test/dev tools:

```sh
uv sync --extra dev
```

Apply migrations:

```sh
alembic upgrade head
```

Run the test suite:

```sh
pytest
```

Run the development server:

```sh
uvicorn app.main:app --reload
```

## Background jobs

Run expired-data pruning as an external cron-style job:

```sh
python -m app.jobs.retention
```

The process deletes expired messages, revoked pairings older than 180 days, and stale rate limit events. Schedule it outside the app process, for example with AWS EventBridge or a systemd timer.

## Deployment

When deploying the server behind a reverse proxy (e.g., Nginx, Traefik, AWS ALB), it is critical to configure the proxy correctly. The application relies on the `X-Forwarded-For` and `X-Forwarded-Host` headers to determine the client's IP address (for rate limiting) and the public-facing hostname (for generating pairing URLs).

Ensure your proxy correctly sets these headers and that the application is configured to trust the proxy. Failure to do so may result in incorrect rate limiting or the generation of invalid URLs for client pairing.
