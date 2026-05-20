from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://syncler:syncler@localhost:5432/syncler"
    jwt_secret: str
    pre_login_pepper: str = ""
    environment: Literal["development", "production"] = "development"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
