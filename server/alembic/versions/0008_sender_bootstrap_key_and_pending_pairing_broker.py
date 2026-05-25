"""Phase 5a-2: sender bootstrap key + pending_pairing.sender_broker_url

Adds three nullable columns:
  - `senders.bootstrap_key` — sender's X25519 public key (32 bytes,
    binary). Set when the sender adopts the V1.5 automated pairing
    flow; nullable so existing senders that haven't opted in keep
    working.
  - `senders.bootstrap_key_signature` — Ed25519 signature over
    `"syncler-v1-bootstrap-key:" || raw_bootstrap_key`. Set together
    with `bootstrap_key`.
  - `pending_pairings.sender_broker_url` — text URL the sender supplies
    at pairing/initiate. Echoed in pairing/preview. Bound to the
    sender-signed initiate envelope. NULL when the sender opted out
    (V1 manual flow).

Spec: docs/crypto-spec.md §9. Plan: .triad/70-phase5-agreement.md.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0008_sender_bootstrap_broker"
down_revision = "0007_live_cards"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "senders",
        sa.Column("bootstrap_key", sa.LargeBinary(), nullable=True),
    )
    op.add_column(
        "senders",
        sa.Column("bootstrap_key_signature", sa.LargeBinary(), nullable=True),
    )
    op.add_column(
        "pending_pairings",
        sa.Column("sender_broker_url", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("pending_pairings", "sender_broker_url")
    op.drop_column("senders", "bootstrap_key_signature")
    op.drop_column("senders", "bootstrap_key")
