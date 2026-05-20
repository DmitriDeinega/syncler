import asyncio
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text
from sqlalchemy.ext.asyncio import create_async_engine

from app.config import Settings
from app.models import Base


PROJECT_ROOT = Path(__file__).resolve().parents[1]


async def _reset_public_schema(database_url: str) -> None:
    engine = create_async_engine(database_url, isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as connection:
            await connection.execute(text("DROP SCHEMA IF EXISTS public CASCADE"))
            await connection.execute(text("CREATE SCHEMA public"))
    finally:
        await engine.dispose()


async def _table_names(database_url: str) -> set[str]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            names = await connection.run_sync(lambda sync_connection: inspect(sync_connection).get_table_names())
    finally:
        await engine.dispose()
    return set(names)


def _alembic_config() -> Config:
    return Config(str(PROJECT_ROOT / "alembic.ini"))


def test_alembic_upgrade_head_and_downgrade_base(test_settings: Settings) -> None:
    asyncio.run(_reset_public_schema(test_settings.database_url))

    config = _alembic_config()
    command.upgrade(config, "head")

    created_tables = asyncio.run(_table_names(test_settings.database_url))
    assert created_tables - {"alembic_version"} == set(Base.metadata.tables.keys())

    command.downgrade(config, "base")

    remaining_tables = asyncio.run(_table_names(test_settings.database_url))
    assert remaining_tables - {"alembic_version"} == set()
