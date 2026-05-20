import pytest
from sqlalchemy import text

from app.db import dispose_engine, init_engine


@pytest.mark.asyncio
async def test_sqlite_async_engine_can_execute_select_one() -> None:
    """Uses SQLite only as a lightweight async engine smoke test; schema migrations target Postgres."""
    pytest.importorskip("aiosqlite", reason="needs async SQLite driver for in-memory engine smoke test")

    engine = init_engine("sqlite+aiosqlite:///:memory:")

    try:
        async with engine.connect() as connection:
            result = await connection.scalar(text("SELECT 1"))

        assert result == 1
    finally:
        await dispose_engine()
