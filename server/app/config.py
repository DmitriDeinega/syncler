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

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Memoized accessor used by services that need settings without DI."""
    return Settings()  # type: ignore[call-arg]
