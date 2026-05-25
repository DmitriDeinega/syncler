from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

pytest.importorskip("sqlalchemy", reason="retention DB tests need SQLAlchemy")
pytest.importorskip("aiosqlite", reason="retention DB tests need async SQLite")

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from app.jobs.retention import prune_expired
from app.models import Base, Message, Plugin, Sender, User


@pytest.fixture
async def db_session() -> AsyncIterator[AsyncSession]:
    engine = create_async_engine(
        "sqlite+aiosqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)

    async with session_factory() as session:
        yield session

    await engine.dispose()


@pytest.mark.asyncio
async def test_prune_expired_deletes_expired_message(db_session: AsyncSession) -> None:
    now = datetime.now(UTC)
    user = User(
        id=uuid4(),
        email="retention@example.com",
        auth_key_hash=b"1" * 32,
        encrypted_master_key=b"2" * 48,
        auth_salt=b"3" * 16,
        argon2_params_version=1,
        created_at=now,
    )
    sender = Sender(id=uuid4(), public_key=b"4" * 32, name="sender", created_at=now)
    plugin = Plugin(
        id=uuid4(),
        sender_id=sender.id,
        plugin_identifier="com.test.retention",
        version="1.0.0",
        manifest_hash=b"5" * 32,
        bundle_hash=b"6" * 32,
        signature=b"7" * 64,
        signed_bundle_url="https://example.com/bundle",
        capabilities={},
        endpoints={},
        created_at=now,
    )
    message = Message(
        id=uuid4(),
        sender_id=sender.id,
        user_id=user.id,
        plugin_id=plugin.id,
        encrypted_body_pointer="s3://bucket/message",
        expires_at=now - timedelta(hours=1),
        sent_at=now - timedelta(days=1),
    )
    db_session.add_all([user, sender, plugin, message])
    await db_session.commit()

    summary = await prune_expired(db_session)
    remaining = await db_session.execute(select(Message).where(Message.id == message.id))

    assert summary["messages"] == 1
    assert remaining.scalar_one_or_none() is None
