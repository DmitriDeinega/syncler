import asyncio
import json
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import dispose_engine, get_db, init_engine
from app.models import (
    CardPatch,
    LiveCard,
    Message,
    Pairing,
    RateLimitEvent,
    RotationChallenge,
)
from app.services.nonce_replay import cleanup_expired_with_lock


async def prune_expired(session: AsyncSession) -> dict[str, int]:
    now = datetime.now(UTC)

    messages = await session.execute(delete(Message).where(Message.expires_at < now))
    live_cards = await session.execute(delete(LiveCard).where(LiveCard.expires_at < now))
    # Triad 146 codex FIX #4 — card_patches retention. Two passes:
    # (1) age out anything older than 48h regardless of parent (spec TTL).
    # (2) sweep orphans whose parent LiveCard just got pruned above —
    #     the schema has no FK cascade, so we do it explicitly here.
    card_patches_aged = await session.execute(
        delete(CardPatch).where(CardPatch.created_at < now - timedelta(hours=48))
    )
    card_patches_orphan = await session.execute(
        delete(CardPatch).where(
            ~CardPatch.card_id.in_(select(LiveCard.id))
        )
    )
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
    # Phase 8: rotation challenges expire in ~5 min; sweep stragglers so
    # the table doesn't grow unboundedly on long-running deployments.
    rotation_challenges = await session.execute(
        delete(RotationChallenge).where(RotationChallenge.expires_at < now),
    )
    await session.commit()

    return {
        "messages": messages.rowcount or 0,
        "live_cards": live_cards.rowcount or 0,
        "card_patches_aged": card_patches_aged.rowcount or 0,
        "card_patches_orphan": card_patches_orphan.rowcount or 0,
        "pairings": pairings.rowcount or 0,
        "rate_limit_events": rate_limit_events.rowcount or 0,
        "nonce_replay": max(nonce_replay, 0),
        "rotation_challenges": rotation_challenges.rowcount or 0,
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
