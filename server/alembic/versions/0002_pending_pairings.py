"""pending_pairings

Revision ID: 0002_pending_pairings
Revises: 0001_initial_schema
Create Date: 2026-05-20 10:00:00.000000

"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "0002_pending_pairings"
down_revision: str | None = "0001_initial_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "pending_pairings",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("sender_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("pairing_token", postgresql.BYTEA(), nullable=False),
        sa.Column("expires_at", postgresql.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("consumed_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("metadata_json", postgresql.JSONB()),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(
            ["sender_id"],
            ["senders.id"],
            name="fk_pending_pairings_sender_id_senders",
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("pairing_token", name="uq_pending_pairings_token"),
    )
    op.create_index("ix_pending_pairings_sender_id", "pending_pairings", ["sender_id"])
    op.create_index("ix_pending_pairings_expires_at", "pending_pairings", ["expires_at"])


def downgrade() -> None:
    op.drop_index("ix_pending_pairings_expires_at", table_name="pending_pairings")
    op.drop_index("ix_pending_pairings_sender_id", table_name="pending_pairings")
    op.drop_table("pending_pairings")
