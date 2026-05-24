"""Phase 3a: plugin renderer + template columns

Adds the two columns the template renderer needs on the `plugins` table:

- ``renderer`` (text, NOT NULL, default ``'script'``) — discriminates
  between the legacy WebView bundle path (``'script'``) and the new
  native Compose renderer (``'template'``). The server-side default
  backfills existing rows to ``'script'`` so the latest endpoint can
  safely return the field without breaking pre-Phase-3a senders.

- ``template`` (jsonb, NULL) — the template manifest block when
  ``renderer = 'template'``. NULL otherwise. The publish-time
  validator in ``server/app/schemas.py`` enforces the pairing.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB


revision = "0006_plugin_renderer_template"
down_revision = "0005_plugin_revocation_reason"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "plugins",
        sa.Column(
            "renderer",
            sa.Text(),
            nullable=False,
            server_default="script",
        ),
    )
    op.add_column(
        "plugins",
        sa.Column(
            "template",
            JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
    )


def downgrade() -> None:
    op.drop_column("plugins", "template")
    op.drop_column("plugins", "renderer")
