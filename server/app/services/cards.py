"""Live card service — Phase 9b V2 upsert, delete, fetch, prune."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import LiveCard, Pairing, Plugin

# Server-enforced 48h TTL ceiling. Sender-supplied `expires_at` is clamped
# against `now + MAX_TTL` so a malicious or buggy sender can't squat a card
# slot indefinitely.
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


async def upsert_live_card_v2(
    db: AsyncSession,
    *,
    payload: object,  # LiveCardUpsertRequestV2 — avoid circular import
) -> LiveCard:
    """Phase 9b V2 live-card upsert (spec §11.5).

    Caller (POST /v1/cards/upsert) has already:
    - Verified the Ed25519 envelope signature
    - Burned the payload_nonce in the nonce-replay table
    - Run the recipient-set classifier (§11.10)

    so this function focuses on pairing/plugin checks, sequence
    monotonicity, and storing the V2 pointer.
    """
    # Local import to avoid module-load-time cycles.
    from app.services.envelopes_v2 import build_v2_pointer

    sender_id = payload.sender_id
    user_id = payload.user_id
    plugin_id = payload.plugin_id
    card_key = payload.card_key
    sequence_number = payload.sequence_number
    expires_at = payload.expires_at

    now = datetime.now(UTC)
    if expires_at <= now:
        raise ExpiredEnvelopeError("expires_at is not in the future")
    if expires_at > now + MAX_TTL:
        raise ExpiredEnvelopeError(
            f"expires_at exceeds the {int(MAX_TTL.total_seconds() // 3600)}h live-card cap"
        )

    # Pairing check.
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

    # Plugin lookup — must be a live-card plugin owned by this sender.
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

    pointer = build_v2_pointer(payload)

    # Phase 9b (Codex 128 #2): upsert is now plugin-scoped. The V1
    # lookup by (sender_id, user_id, card_key) could conflict across
    # two plugins from the same sender that happened to use the same
    # card_key. V2 delete already scopes by plugin_id; upsert mirrors.
    existing_result = await db.execute(
        select(LiveCard).where(
            and_(
                LiveCard.sender_id == sender_id,
                LiveCard.user_id == user_id,
                LiveCard.plugin_id == plugin_id,
                LiveCard.card_key == card_key,
            )
        )
    )
    existing = existing_result.scalar_one_or_none()

    if existing:
        if sequence_number <= existing.sequence_number:
            raise SequenceNumberRegressionError(
                f"sequence_number {sequence_number} not greater than "
                f"{existing.sequence_number}"
            )
        existing.encrypted_body_pointer = pointer
        existing.card_type = payload.card_type
        existing.min_plugin_version = payload.min_plugin_version
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
            encrypted_body_pointer=pointer,
            card_type=payload.card_type,
            min_plugin_version=payload.min_plugin_version,
            sequence_number=sequence_number,
            created_at=now,
            updated_at=now,
            expires_at=expires_at,
        )
        db.add(card)

    await db.commit()
    await db.refresh(card)
    return card


async def delete_live_card_v2(
    db: AsyncSession,
    *,
    sender_id: uuid.UUID,
    user_id: uuid.UUID,
    plugin_id: uuid.UUID,
    card_key: str,
) -> bool:
    """Phase 9b V2 live-card delete (spec §11.6). Plugin-scoped lookup
    closes the cross-plugin-replay gap Codex flagged at triad 125 #1.

    Returns True if a card was actually deleted, False if nothing
    matched (the request remains a 204 either way — V2 mirrors V1's
    idempotent delete semantics).
    """
    result = await db.execute(
        select(LiveCard).where(
            and_(
                LiveCard.sender_id == sender_id,
                LiveCard.user_id == user_id,
                LiveCard.plugin_id == plugin_id,
                LiveCard.card_key == card_key,
            )
        )
    )
    card = result.scalar_one_or_none()
    if card is None:
        return False
    await db.delete(card)
    await db.commit()
    return True


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
