from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.jobs.retention import prune_expired
from app.models import Message, Plugin, Sender, User


@pytest.mark.asyncio
async def test_prune_expired_only_deletes_expired_messages(db_session: AsyncSession) -> None:
    now = datetime.now(UTC)
    user = User(
        id=uuid4(),
        email="retention-integration@example.com",
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
        plugin_identifier="com.test.retention_integration",
        version="1.0.0",
        manifest_hash=b"5" * 32,
        bundle_hash=b"6" * 32,
        signature=b"7" * 64,
        signed_bundle_url="https://example.com/bundle",
        capabilities={},
        endpoints={},
        created_at=now,
    )
    expired = Message(
        id=uuid4(),
        sender_id=sender.id,
        user_id=user.id,
        plugin_id=plugin.id,
        encrypted_body_pointer="s3://bucket/expired",
        expires_at=now - timedelta(seconds=1),
        sent_at=now - timedelta(days=1),
    )
    future = Message(
        id=uuid4(),
        sender_id=sender.id,
        user_id=user.id,
        plugin_id=plugin.id,
        encrypted_body_pointer="s3://bucket/future",
        expires_at=now + timedelta(hours=1),
        sent_at=now,
    )
    # Insert parents in dependency order so FKs resolve. SQLAlchemy
    # can't always infer topological order from a single add_all when
    # the model graph doesn't declare relationship()s.
    db_session.add_all([user, sender])
    await db_session.flush()
    db_session.add(plugin)
    await db_session.flush()
    db_session.add_all([expired, future])
    await db_session.commit()

    summary = await prune_expired(db_session)
    remaining = await db_session.scalars(select(Message.id).order_by(Message.encrypted_body_pointer))

    assert summary["messages"] == 1
    assert set(remaining.all()) == {future.id}
