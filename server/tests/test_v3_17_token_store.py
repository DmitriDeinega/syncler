"""V3 #17 token store tests — in-process backend.

The Redis backend is exercised by tests that spin up a real
Redis (e.g. `test_v3_17_redis_integration.py`, deferred
until the dev-box can run docker-compose). Here we verify
the Protocol surface + factory dispatch works on the
``memory`` backplane.
"""

from __future__ import annotations

import uuid
import time

import pytest

from app.config import get_settings
from app.live.token_store import (
    CONNECT_TOKEN_TTL_SECONDS,
    InProcessTokenStore,
    get_token_store,
    set_for_tests,
)


@pytest.fixture(autouse=True)
def reset_store() -> None:
    set_for_tests(None)
    # Make sure get_settings is on the default memory mode.
    get_settings.cache_clear()
    yield
    set_for_tests(None)
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_inprocess_mint_returns_token_and_entry() -> None:
    store = InProcessTokenStore()
    uid = uuid.uuid4()
    did = uuid.uuid4()
    token, entry = await store.mint(uid, did)
    assert isinstance(token, str)
    assert len(token) > 30
    assert entry.user_id == uid
    assert entry.device_id == did
    assert entry.expires_at_epoch_s > time.time()


@pytest.mark.asyncio
async def test_inprocess_redeem_is_single_use() -> None:
    store = InProcessTokenStore()
    token, entry = await store.mint(uuid.uuid4(), uuid.uuid4())
    first = await store.redeem(token)
    assert first is not None
    assert first.device_id == entry.device_id
    second = await store.redeem(token)
    assert second is None


@pytest.mark.asyncio
async def test_inprocess_redeem_unknown_returns_none() -> None:
    store = InProcessTokenStore()
    assert await store.redeem("never-minted") is None


@pytest.mark.asyncio
async def test_inprocess_redeem_after_expiry_returns_none() -> None:
    store = InProcessTokenStore()
    token, _ = await store.mint(uuid.uuid4(), uuid.uuid4())
    # Hand-rewind the entry's TTL.
    entry = store._tokens[token]
    store._tokens[token] = entry.__class__(
        user_id=entry.user_id,
        device_id=entry.device_id,
        expires_at_epoch_s=time.time() - 1.0,
    )
    assert await store.redeem(token) is None


@pytest.mark.asyncio
async def test_factory_returns_inprocess_on_memory_backplane(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIVE_BACKPLANE", "memory")
    get_settings.cache_clear()
    set_for_tests(None)
    store = get_token_store()
    assert isinstance(store, InProcessTokenStore)


def test_constants_stable() -> None:
    # Pinning the 60s default — both the in-process and
    # Redis backends rely on this for SET EX/TTL.
    assert CONNECT_TOKEN_TTL_SECONDS == 60.0
