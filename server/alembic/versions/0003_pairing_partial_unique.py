"""pairings: drop hard unique, add partial unique on active rows

Revision ID: 0003_pairing_partial_unique
Revises: 0002_pending_pairings
Create Date: 2026-05-20 11:00:00.000000

"""

from __future__ import annotations

from typing import Sequence

from alembic import op


revision: str = "0003_pairing_partial_unique"
down_revision: str | None = "0002_pending_pairings"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_constraint("uq_pairings_user_id_sender_id", "pairings", type_="unique")
    op.create_index(
        "uq_pairings_active_user_sender",
        "pairings",
        ["user_id", "sender_id"],
        unique=True,
        postgresql_where="revoked_at IS NULL",
    )


def downgrade() -> None:
    op.drop_index("uq_pairings_active_user_sender", table_name="pairings")
    op.create_unique_constraint(
        "uq_pairings_user_id_sender_id",
        "pairings",
        ["user_id", "sender_id"],
    )
