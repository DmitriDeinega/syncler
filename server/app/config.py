from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://syncler:syncler@localhost:5432/syncler"
    jwt_secret: str
    pre_login_pepper: str = ""
    firebase_service_account_path: str | None = None
    environment: Literal["development", "production"] = "development"

    # V3 #14 step 5: Ed25519 32-byte seed used by the live
    # webhook forwarder to sign requests forwarded to sender
    # webhooks. Senders verify against the corresponding public
    # key advertised at a well-known endpoint (V0.2 admin
    # surface). V0.1 dev posture: empty seed = no signing key
    # available, webhook forwarding falls back to "delivery
    # disabled" until the env var is set.
    server_signing_seed_b64: str = ""

    # V3 #17: live-backplane selector. "memory" keeps the
    # V0.1 in-process Hub/EventBus/token-store (default for
    # tests + single-worker dev). "redis" swaps all three to
    # Redis-backed implementations so multi-worker fan-out
    # works. ONE global flag — per-component flags are
    # explicitly rejected (a Redis hub + memory token store
    # would silently break multi-worker WS routing).
    # Spec: docs/live-backplane.md "Configuration".
    live_backplane: Literal["memory", "redis"] = "memory"

    # V3 #17: Redis connection URL. Required when
    # live_backplane=="redis"; ignored when "memory".
    # Default points at the docker-compose-published port
    # (6380 → 6379 inside the container) so `LIVE_BACKPLANE=
    # redis` works out of the box with `docker compose up`.
    # Production deployments override via env var.
    # Triad 148 codex FIX #10 (port mismatch).
    redis_url: str = "redis://localhost:6380/0"

    # V3 #17: ordered-lane Redis Streams trim cap (XADD
    # MAXLEN ~). Independent of the in-process backend's
    # ORDERED_REPLAY_CAP=256. Default chosen for headroom:
    # V3 #16 patches persist in Postgres so the stream can
    # be smaller, but a 1024-entry trim handles bursts.
    live_ordered_stream_maxlen: int = 1024

    # V3 #17: optional Redis key prefix for shared Redis
    # environments (e.g. dev cluster shared with other
    # services). Empty by default; prepended to every key
    # the backplane writes ("live:token:...", "sse:user:...",
    # etc.).
    live_redis_key_prefix: str = ""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Memoized accessor used by services that need settings without DI."""
    return Settings()  # type: ignore[call-arg]
