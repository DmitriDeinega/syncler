"""Live card service — upsert, delete, fetch, prune."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import LiveCard, Pairing, Plugin

# Server-enforced 48h TTL ceiling. Sender-supplied `expires_at` is clamped
# against `now + MAX_TTL` so a malicious or buggy sender can't squat a card
# slot indefinitely (Codex consultation 62 RED #4). Aligns with the Phase 3b
# plan in .triad/50-agreement-and-plan.md.
MAX_TTL = timedelta(hours=48)


class CardError(Exception):
    """Base class for card service errors."""


class CardNotFoundError(CardError):
    """Card not found."""


class PluginInactiveError(CardError):
    """Plugin missing, revoked, or not owned by sender."""


class PairingMissingError(CardError):
    """No active (sender_id, user_id) pairing."""


class ExpiredEnvelopeError(CardError):
    """expires_at is not in the future or exceeds the server-enforced cap."""


class SequenceNumberRegressionError(CardError):
    """New sequence number is not greater than existing."""


async def upsert_live_card(
    db: AsyncSession,
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    card_key: str,
    encrypted_payload: bytes,
    nonce: bytes,
    sequence_number: int,
    expires_at: datetime,
) -> LiveCard:
    now = datetime.now(UTC)
    # TTL enforcement (Codex consultation 62 RED #4): reject already-expired
    # expires_at and clamp anything beyond the 48h cap. Mirrors the
    # `store_message` MAX_RETENTION pattern in services/messages.py.
    if expires_at <= now:
        raise ExpiredEnvelopeError("expires_at is not in the future")
    if expires_at > now + MAX_TTL:
        raise ExpiredEnvelopeError(
            f"expires_at exceeds the {int(MAX_TTL.total_seconds() // 3600)}h live-card cap"
        )

    # Pairing check (Codex consultation 62 RED #3): live-card upsert MUST
    # require an active (sender_id, user_id) pairing, same as the message
    # store path. Without this, any registered sender could push live cards
    # to any user they happen to know the UUID of.
    pairing_result = await db.execute(
        select(Pairing).where(
            and_(
                Pairing.sender_id == sender_id,
                Pairing.user_id == user_id,
                Pairing.revoked_at.is_(None),
            ),
        ),
    )
    if pairing_result.scalar_one_or_none() is None:
        raise PairingMissingError("no active pairing")

    # Plugin lookup (Codex consultation 62 RED #2): include the
    # `Plugin.revoked_at.is_(None)` filter so revoked plugins cannot upsert
    # live cards (matches the messages service `_plugin` pattern).
    result = await db.execute(
        select(Plugin).where(
            and_(
                Plugin.id == plugin_id,
                Plugin.sender_id == sender_id,
                Plugin.card_type == "live",
                Plugin.revoked_at.is_(None),
            )
        )
    )
    plugin = result.scalar_one_or_none()
    if plugin is None:
        raise PluginInactiveError(
            "plugin missing, revoked, not live-type, or not owned by sender"
        )

    # 2. Upsert with sequence number check.
    existing_result = await db.execute(
        select(LiveCard).where(
            and_(
                LiveCard.sender_id == sender_id,
                LiveCard.user_id == user_id,
                LiveCard.card_key == card_key,
            )
        )
    )
    existing = existing_result.scalar_one_or_none()
    
    now = datetime.now(UTC)
    if existing:
        if sequence_number <= existing.sequence_number:
            raise SequenceNumberRegressionError(
                f"sequence_number {sequence_number} not greater than {existing.sequence_number}"
            )
        
        existing.encrypted_payload = encrypted_payload
        existing.nonce = nonce
        existing.sequence_number = sequence_number
        existing.updated_at = now
        existing.expires_at = expires_at
        card = existing
    else:
        card = LiveCard(
            id=uuid.uuid4(),
            user_id=user_id,
            sender_id=sender_id,
            plugin_id=plugin_id,
            card_key=card_key,
            encrypted_payload=encrypted_payload,
            nonce=nonce,
            sequence_number=sequence_number,
            created_at=now,
            updated_at=now,
            expires_at=expires_at,
        )
        db.add(card)

    await db.commit()
    await db.refresh(card)
    return card


async def delete_live_card(
    db: AsyncSession,
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    card_key: str,
) -> None:
    result = await db.execute(
        select(LiveCard).where(
            and_(
                LiveCard.sender_id == sender_id,
                LiveCard.user_id == user_id,
                LiveCard.card_key == card_key,
            )
        )
    )
    card = result.scalar_one_or_none()
    if card:
        await db.delete(card)
        await db.commit()


async def get_live_cards_for_user(
    db: AsyncSession,
    *,
    user_id: uuid.UUID,
) -> list[LiveCard]:
    now = datetime.now(UTC)
    result = await db.execute(
        select(LiveCard).where(
            and_(
                LiveCard.user_id == user_id,
                LiveCard.expires_at > now,
            )
        )
    )
    return list(result.scalars().all())


async def prune_expired_cards(db: AsyncSession) -> int:
    """Delete cards past their expires_at."""
    now = datetime.now(UTC)
    expired = await db.execute(select(LiveCard).where(LiveCard.expires_at <= now))
    count = 0
    for card in expired.scalars().all():
        await db.delete(card)
        count += 1
    if count > 0:
        await db.commit()
    return count
