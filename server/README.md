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

## Deployment (shared box, behind reverse proxy)

On a deploy where uvicorn runs on the same host as Postgres + Redis,
behind Caddy / nginx / etc., the database stack must bind to loopback
only. Use the `docker-compose.loopback.yml` override:

```sh
# .env should set COMPOSE_FILE=docker-compose.yml:docker-compose.loopback.yml
# (see .env.example) — then bare `docker compose up -d` picks up both files.
docker compose up -d
```

Without `COMPOSE_FILE`, the canonical bring-up is:

```sh
docker compose -f docker-compose.yml -f docker-compose.loopback.yml \
               --env-file .env up -d
```

The loopback override maps Postgres + Redis to `127.0.0.1:65432` and
`127.0.0.1:65379` respectively — the ports `.env.example` advertises.
A bare `docker compose up -d` without the override binds to the dev
ports (5433/6380) instead, and uvicorn will crash-loop against the
wrong addresses. See `docker-compose.loopback.yml` for the rationale.

For boot persistence, install the systemd units from `deploy/`:

```sh
sudo cp deploy/syncler-stack.service deploy/syncler.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now syncler-stack.service
sudo systemctl restart syncler.service
```

`syncler.service` `Requires=syncler-stack.service` so the chain is
ordered: docker stack up → uvicorn starts. A host reboot brings
everything back in order.

## Local development

Start Postgres + Redis on the dev ports (5433 / 6380):

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
