from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from app.config import Settings

_engine: AsyncEngine | None = None
_session_factory: async_sessionmaker[AsyncSession] | None = None


def init_engine(database_url: str | None = None) -> AsyncEngine:
    global _engine, _session_factory

    if _engine is None:
        if database_url is None:
            database_url = Settings().database_url

        _engine = create_async_engine(
            database_url,
            pool_pre_ping=True,
        )
        _session_factory = async_sessionmaker(_engine, expire_on_commit=False)

    return _engine


async def dispose_engine() -> None:
    global _engine, _session_factory

    if _engine is not None:
        await _engine.dispose()
        _engine = None
        _session_factory = None


async def get_db() -> AsyncIterator[AsyncSession]:
    if _session_factory is None:
        init_engine()

    assert _session_factory is not None
    async with _session_factory() as session:
        yield session
