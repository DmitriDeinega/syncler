"""V3 #14 — plugin live inbound webhook URL

Adds a nullable column to ``plugins`` carrying the sender-
registered webhook URL the V3 #14 forwarder POSTs device-
originated live frames to. NULL = plugin is one-way push only
(sender → device).

Spec: docs/live-channel.md "Inbound webhook".
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0013_plugin_live_inbound"
down_revision = "0012_plugin_native_kotlin"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "plugins",
        sa.Column("live_inbound_url", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("plugins", "live_inbound_url")
