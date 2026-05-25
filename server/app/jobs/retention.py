import asyncio
import json
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import dispose_engine, get_db, init_engine
from app.models import LiveCard, Message, Pairing, RateLimitEvent
from app.services.nonce_replay import cleanup_expired_with_lock


async def prune_expired(session: AsyncSession) -> dict[str, int]:
    now = datetime.now(UTC)

    messages = await session.execute(delete(Message).where(Message.expires_at < now))
    live_cards = await session.execute(delete(LiveCard).where(LiveCard.expires_at < now))
    pairings = await session.execute(
        delete(Pairing).where(
            Pairing.revoked_at.is_not(None),
            Pairing.revoked_at < now - timedelta(days=180),
        )
    )
    rate_limit_events = await session.execute(
        delete(RateLimitEvent).where(RateLimitEvent.window_start < now - timedelta(days=7))
    )
    # Phase 7: durable nonce-replay registry cleanup. Uses a Postgres
    # advisory lock so multi-worker deployments don't all run the same
    # DELETE concurrently. Returns -1 if another worker held the lock
    # (no-op for this invocation); we surface that as 0 in the summary.
    nonce_replay = await cleanup_expired_with_lock(session)
    await session.commit()

    return {
        "messages": messages.rowcount or 0,
        "live_cards": live_cards.rowcount or 0,
        "pairings": pairings.rowcount or 0,
        "rate_limit_events": rate_limit_events.rowcount or 0,
        "nonce_replay": max(nonce_replay, 0),
    }


async def _main() -> None:
    init_engine()
    try:
        async for session in get_db():
            summary = await prune_expired(session)
            print(json.dumps(summary, sort_keys=True))
            break
    finally:
        await dispose_engine()


if __name__ == "__main__":
    asyncio.run(_main())
