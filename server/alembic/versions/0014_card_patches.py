"""V3 #16 — card_patches sidecar table

Stores per-(plugin_row, card, base_seq, patch_seq) entries so
the inbox catch-up surface can replay missed live patches to
disconnected devices. Triad 145 dual-write design (both
reviewers FIX): patches ride the live channel for latency AND
land here for durable replay.

Retention: 48h TTL matching the existing live-card TTL.
Application-level GC + immediate purge on next cards.upsert
for the same card (gemini FIX — don't keep stale patches
past their parent upsert).

Spec: docs/live-card-patch.md.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0014_card_patches"
down_revision = "0013_plugin_live_inbound"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "card_patches",
        sa.Column("plugin_row_id", sa.Uuid(), nullable=False),
        sa.Column("card_id", sa.Uuid(), nullable=False),
        sa.Column("base_seq", sa.BigInteger(), nullable=False),
        sa.Column("patch_seq", sa.BigInteger(), nullable=False),
        # The full V2-shape envelope JSON (per-recipient HPKE
        # blobs + sender signature). The server NEVER decodes
        # this — it's opaque routing bytes.
        sa.Column("envelope_json", sa.Text(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint(
            "plugin_row_id", "card_id", "base_seq", "patch_seq",
            name="pk_card_patches",
        ),
    )
    # Catch-up query lookup: all patches for one card sorted
    # by sequence. Created_at index supports the GC sweep.
    op.create_index(
        "ix_card_patches_card_seq",
        "card_patches",
        ["plugin_row_id", "card_id", "base_seq", "patch_seq"],
    )
    op.create_index(
        "ix_card_patches_created_at",
        "card_patches",
        ["created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_card_patches_created_at", table_name="card_patches")
    op.drop_index("ix_card_patches_card_seq", table_name="card_patches")
    op.drop_table("card_patches")
