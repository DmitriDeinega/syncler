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
