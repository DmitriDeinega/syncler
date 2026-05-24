"""Phase 3b: live cards support

Adds 'card_type' and 'card_key_path' to plugins table, and creates the
'live_cards' table for persistent, upsertable card state.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0007_live_cards"
down_revision = "0006_plugin_renderer_template"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 1. Update plugins table
    op.add_column(
        "plugins",
        sa.Column(
            "card_type",
            sa.Text(),
            nullable=False,
            server_default="event",
        ),
    )
    op.add_column(
        "plugins",
        sa.Column("card_key_path", sa.Text(), nullable=True),
    )

    # 2. Create live_cards table
    op.create_table(
        "live_cards",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sender_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("plugin_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("card_key", sa.Text(), nullable=False),
        sa.Column("encrypted_payload", sa.LargeBinary(), nullable=False),
        sa.Column("nonce", sa.LargeBinary(), nullable=False),
        sa.Column("sequence_number", sa.BigInteger(), nullable=False, server_default="0"),
        sa.Column(
            "created_at",
            postgresql.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            postgresql.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("expires_at", postgresql.TIMESTAMP(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name="fk_live_cards_user_id_users", ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["sender_id"], ["senders.id"], name="fk_live_cards_sender_id_senders"
        ),
        sa.ForeignKeyConstraint(
            ["plugin_id"], ["plugins.id"], name="fk_live_cards_plugin_id_plugins"
        ),
        sa.UniqueConstraint(
            "sender_id", "user_id", "card_key", name="uq_live_cards_sender_user_key"
        ),
    )
    op.create_index("ix_live_cards_user_id", "live_cards", ["user_id"])
    op.create_index("ix_live_cards_sender_id", "live_cards", ["sender_id"])
    op.create_index("ix_live_cards_expires_at", "live_cards", ["expires_at"])


def downgrade() -> None:
    op.drop_index("ix_live_cards_expires_at", table_name="live_cards")
    op.drop_index("ix_live_cards_sender_id", table_name="live_cards")
    op.drop_index("ix_live_cards_user_id", table_name="live_cards")
    op.drop_table("live_cards")
    op.drop_column("plugins", "card_key_path")
    op.drop_column("plugins", "card_type")
