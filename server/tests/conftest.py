import asyncio
import re
from collections.abc import AsyncIterator, Iterator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, create_async_engine

from app.config import Settings
from app.db import get_db
from app.main import app
from app.models import Base

TEST_JWT_SECRET = "test-secret-with-at-least-32-bytes"
TEST_DATABASE_NAME = "syncler_test"


def _test_database_url() -> str:
    base_url = make_url(Settings(jwt_secret=TEST_JWT_SECRET).database_url)
    return base_url.set(database=TEST_DATABASE_NAME).render_as_string(hide_password=False)


async def _recreate_database(database_url: str) -> None:
    url = make_url(database_url)
    database_name = url.database
    if database_name is None or not re.fullmatch(r"[A-Za-z0-9_]+", database_name):
        raise RuntimeError(f"Unsafe test database name: {database_name!r}")

    admin_url = url.set(database="postgres")
    admin_engine = create_async_engine(
        admin_url.render_as_string(hide_password=False),
        isolation_level="AUTOCOMMIT",
    )
    try:
        async with admin_engine.connect() as connection:
            await connection.execute(
                text(
                    """
                    SELECT pg_terminate_backend(pid)
                    FROM pg_stat_activity
                    WHERE datname = :database_name
                      AND pid <> pg_backend_pid()
                    """
                ),
                {"database_name": database_name},
            )
            await connection.execute(text(f'DROP DATABASE IF EXISTS "{database_name}"'))
            await connection.execute(text(f'CREATE DATABASE "{database_name}"'))
    finally:
        await admin_engine.dispose()


async def _drop_database(database_url: str) -> None:
    url = make_url(database_url)
    database_name = url.database
    if database_name is None or not re.fullmatch(r"[A-Za-z0-9_]+", database_name):
        raise RuntimeError(f"Unsafe test database name: {database_name!r}")

    admin_url = url.set(database="postgres")
    admin_engine = create_async_engine(
        admin_url.render_as_string(hide_password=False),
        isolation_level="AUTOCOMMIT",
    )
    try:
        async with admin_engine.connect() as connection:
            await connection.execute(
                text(
                    """
                    SELECT pg_terminate_backend(pid)
                    FROM pg_stat_activity
                    WHERE datname = :database_name
                      AND pid <> pg_backend_pid()
                    """
                ),
                {"database_name": database_name},
            )
            await connection.execute(text(f'DROP DATABASE IF EXISTS "{database_name}"'))
    finally:
        await admin_engine.dispose()


@pytest.fixture(scope="session", autouse=True)
def test_database() -> Iterator[str]:
    database_url = _test_database_url()
    asyncio.run(_recreate_database(database_url))
    try:
        yield database_url
    finally:
        asyncio.run(_drop_database(database_url))


@pytest.fixture
def event_loop() -> Iterator[asyncio.AbstractEventLoop]:
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        yield loop
    finally:
        asyncio.set_event_loop(None)
        loop.close()


@pytest.fixture
def test_settings(test_database: str, monkeypatch: pytest.MonkeyPatch) -> Settings:
    monkeypatch.setenv("DATABASE_URL", test_database)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    return Settings(database_url=test_database, jwt_secret=TEST_JWT_SECRET)


@pytest.fixture
async def engine(test_settings: Settings) -> AsyncIterator[AsyncEngine]:
    test_engine = create_async_engine(test_settings.database_url, pool_pre_ping=True)
    async with test_engine.begin() as connection:
        await connection.run_sync(Base.metadata.drop_all)
        await connection.run_sync(Base.metadata.create_all)

    try:
        yield test_engine
    finally:
        async with test_engine.begin() as connection:
            await connection.run_sync(Base.metadata.drop_all)
        await test_engine.dispose()


@pytest.fixture
async def db_session(engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    async with engine.connect() as connection:
        transaction = await connection.begin()
        session = AsyncSession(bind=connection, expire_on_commit=False)
        try:
            yield session
        finally:
            await session.close()
            if transaction.is_active:
                await transaction.rollback()


@pytest.fixture
async def app_client(db_session: AsyncSession) -> AsyncIterator[AsyncClient]:
    async def override_get_db() -> AsyncIterator[AsyncSession]:
        yield db_session

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app, client=("127.0.0.1", 12345))

    try:
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield client
    finally:
        app.dependency_overrides.clear()
