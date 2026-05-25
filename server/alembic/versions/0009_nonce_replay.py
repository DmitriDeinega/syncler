"""Phase 7: durable per-sender nonce-replay registry

Creates the `nonce_replay` table — composite PK on (sender_id, nonce)
so concurrent inserts race-safely via INSERT ON CONFLICT DO NOTHING.
Replaces the in-memory `NonceRegistry` (which was process-local and
lost state on worker restart).

Schema:
  - sender_id UUID, FK to senders.id ON DELETE CASCADE
  - nonce BYTEA (always exactly 12 bytes — AES-GCM requirement;
    enforced by app layer + a CHECK constraint here)
  - seen_at TIMESTAMPTZ DEFAULT NOW(), indexed for the retention
    cleanup query

Retention: pruned after MAX_RETENTION (30 days) by
`server/app/jobs/retention.py`. Plus best-effort on-write cleanup
in `server/app/services/nonce_replay.py`.

Plan: V1.5 ROADMAP item #5. Triad consult 95.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0009_nonce_replay"
down_revision = "0008_sender_bootstrap_key_and_pending_pairing_broker"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "nonce_replay",
        sa.Column(
            "sender_id",
            sa.Uuid(),
            sa.ForeignKey("senders.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("nonce", sa.LargeBinary(), nullable=False),
        sa.Column(
            "seen_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("sender_id", "nonce", name="pk_nonce_replay"),
        sa.CheckConstraint(
            "octet_length(nonce) = 12",
            name="ck_nonce_replay_nonce_length",
        ),
    )
    op.create_index(
        "ix_nonce_replay_seen_at",
        "nonce_replay",
        ["seen_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_nonce_replay_seen_at", table_name="nonce_replay")
    op.drop_table("nonce_replay")
