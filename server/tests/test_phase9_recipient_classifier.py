"""Phase 9b recipient classifier tests — the §11.10 8-row matrix.

Exercises ``classify_recipient_set`` against a real Postgres test
database. Each row of the matrix gets its own test that fixtures up
the matching device-set state and asserts the (http_status, code)
pair the classifier returns.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Device, User
from app.services.envelopes_v2 import (
    RecipientSetError,
    RecipientSetOK,
    classify_recipient_set,
)


@dataclass
class FakeRecipientEnvelope:
    device_id: uuid.UUID


async def _make_user(db: AsyncSession, *, directory_version: int = 1) -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"u{uuid.uuid4().hex[:8]}@example.com",
        auth_key_hash=b"\x00" * 32,
        encrypted_master_key=b"\x00" * 32,
        auth_salt=b"\x00" * 16,
        argon2_params_version=1,
        key_generation=1,
        device_directory_version=directory_version,
    )
    db.add(user)
    await db.flush()
    return user


async def _add_device(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
    encryption_public_key: bytes | None = b"\x01" * 32,
    revoked_at: datetime | None = None,
) -> Device:
    now = datetime.now(UTC)
    device = Device(
        id=uuid.uuid4(),
        user_id=user_id,
        public_key=b"\x02" * 32,
        encryption_public_key=encryption_public_key,
        revoked_at=revoked_at,
        last_seen=now,
        created_at=now,
        updated_at=now,
    )
    db.add(device)
    await db.flush()
    return device


def _env(device_id: uuid.UUID) -> FakeRecipientEnvelope:
    return FakeRecipientEnvelope(device_id=device_id)


@pytest.mark.asyncio
async def test_success_all_active_devices_covered(db_session: AsyncSession):
    user = await _make_user(db_session, directory_version=5)
    d1 = await _add_device(db_session, user_id=user.id)
    d2 = await _add_device(db_session, user_id=user.id)

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id), _env(d2.id)],
        sender_directory_version=5,
    )
    assert isinstance(result, RecipientSetOK)
    assert result.directory_version == 5


@pytest.mark.asyncio
async def test_duplicate_device_id_rejected(db_session: AsyncSession):
    user = await _make_user(db_session)
    d1 = await _add_device(db_session, user_id=user.id)

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id), _env(d1.id)],
        sender_directory_version=1,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 400
    assert result.code == "duplicate_device_id"


@pytest.mark.asyncio
async def test_unknown_device_id_rejected(db_session: AsyncSession):
    user = await _make_user(db_session)
    d1 = await _add_device(db_session, user_id=user.id)
    bogus = uuid.uuid4()

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id), _env(bogus)],
        sender_directory_version=1,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 400
    assert result.code == "unknown_recipient"


@pytest.mark.asyncio
async def test_long_revoked_device_id_treated_as_unknown(db_session: AsyncSession):
    """A device revoked > 5 minutes ago is no longer in the grace
    window. Including it in recipient_envelopes is unknown_recipient."""
    user = await _make_user(db_session)
    active = await _add_device(db_session, user_id=user.id)
    long_ago = datetime.now(UTC) - timedelta(minutes=30)
    long_revoked = await _add_device(
        db_session, user_id=user.id, revoked_at=long_ago,
    )

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(active.id), _env(long_revoked.id)],
        sender_directory_version=1,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 400
    assert result.code == "unknown_recipient"


@pytest.mark.asyncio
async def test_null_encryption_key_device_treated_as_unknown(db_session: AsyncSession):
    """A device that hasn't registered an encryption_public_key yet is
    NOT part of the active set. Sender shouldn't try to seal to it; if
    they do (e.g. via a stale directory), unknown_recipient fires."""
    user = await _make_user(db_session)
    active = await _add_device(db_session, user_id=user.id)
    unregistered = await _add_device(
        db_session, user_id=user.id, encryption_public_key=None,
    )

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(active.id), _env(unregistered.id)],
        sender_directory_version=1,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 400
    assert result.code == "unknown_recipient"


@pytest.mark.asyncio
async def test_missing_active_device_fires_stale_recipient_set(
    db_session: AsyncSession,
):
    user = await _make_user(db_session, directory_version=5)
    d1 = await _add_device(db_session, user_id=user.id)
    d2 = await _add_device(db_session, user_id=user.id)
    # Sender only includes d1 → d2 is missing.

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id)],
        sender_directory_version=5,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 409
    assert result.code == "stale_recipient_set"
    assert result.current_directory_version == 5
    assert d2.id in result.missing_device_ids


@pytest.mark.asyncio
async def test_directory_version_too_new_rejected(db_session: AsyncSession):
    """Sender claims a future version. Either lying or reading from a
    stale replica — server treats as untrustworthy."""
    user = await _make_user(db_session, directory_version=3)
    d1 = await _add_device(db_session, user_id=user.id)

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id)],
        sender_directory_version=99,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 400
    assert result.code == "invalid_directory_version"


@pytest.mark.asyncio
async def test_directory_version_stale_with_complete_set_still_409(
    db_session: AsyncSession,
):
    """Even if the recipient set matches the active set exactly, a
    stale directory_version surfaces as 409 so the sender pins the
    new version on retry."""
    user = await _make_user(db_session, directory_version=10)
    d1 = await _add_device(db_session, user_id=user.id)

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(d1.id)],
        sender_directory_version=8,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 409
    assert result.code == "stale_recipient_set"


@pytest.mark.asyncio
async def test_recently_revoked_extras_tolerated(db_session: AsyncSession):
    """A device revoked < 5 minutes ago can still appear in
    recipient_envelopes (sender's directory cache was old). The
    classifier accepts as long as all CURRENTLY-active devices are
    also covered."""
    user = await _make_user(db_session, directory_version=2)
    active = await _add_device(db_session, user_id=user.id)
    just_revoked = await _add_device(
        db_session,
        user_id=user.id,
        revoked_at=datetime.now(UTC) - timedelta(minutes=2),
    )

    result = await classify_recipient_set(
        db_session,
        user_id=user.id,
        recipient_envelopes=[_env(active.id), _env(just_revoked.id)],
        sender_directory_version=2,
    )
    assert isinstance(result, RecipientSetOK)


@pytest.mark.asyncio
async def test_unknown_user_returns_404(db_session: AsyncSession):
    nonexistent = uuid.uuid4()
    result = await classify_recipient_set(
        db_session,
        user_id=nonexistent,
        recipient_envelopes=[_env(uuid.uuid4())],
        sender_directory_version=1,
    )
    assert isinstance(result, RecipientSetError)
    assert result.http_status == 404
    assert result.code == "user_not_found"
