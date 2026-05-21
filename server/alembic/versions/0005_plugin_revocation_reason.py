"""M11.4: plugin revocation_reason for classified revoke UX

Adds an optional `revocation_reason` column so devices can render
different UI when a plugin was pulled for security reasons vs simply
superseded by a newer version.

Reasons:
- ``superseded`` — a newer version replaced this; safe to surface
  silently in update prompts.
- ``compromised`` — security issue with the bundle/sender; devices
  MUST refuse to execute. Surface as an alert.
- ``sender_disabled`` — sender account was disabled (admin action or
  account self-disable); devices should stop rendering and show a
  neutral "unavailable" message.

The column is nullable: an unrevoked plugin has ``revoked_at IS NULL``
and ``revocation_reason IS NULL``. A revoked plugin from before this
migration ran has ``revoked_at`` set but no reason — client code treats
that as ``unspecified`` (most conservative).
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0005_plugin_revocation_reason"
down_revision = "0004_plugin_identifier"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "plugins",
        sa.Column("revocation_reason", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("plugins", "revocation_reason")
