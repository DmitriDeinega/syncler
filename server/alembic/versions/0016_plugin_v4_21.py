"""V4 #21 — plugin icons + notification policy

Triad 169 design. Adds:

- ``plugins.notif_message`` (bool, default true) — fire FCM on
  POST /v1/messages/send.
- ``plugins.notif_card_arrived`` (bool, default true) — fire FCM
  on the first ``cards/upsert`` per (sender_id, user_id, plugin_id,
  card_key).
- ``plugins.notif_card_updated`` (bool, default false) — fire FCM
  on subsequent ``cards/upsert`` + ``card.patch``. Defaults off so
  high-frequency live cards don't drain battery on every payload
  delta; partners opt in.

- ``plugins.icon_content_hash`` (bytea, nullable) — SHA-256 of the
  icon bytes hosted in the new ``plugin_assets`` table.
- ``plugins.icon_format`` (text, nullable) — MIME type, e.g.
  ``image/png``.
- ``plugins.icon_background_color`` (text, nullable) — optional CSS
  color the client paints behind an alpha icon (notification large
  icon on light surfaces).
- ``plugins.icon_visibility`` (text, nullable) — ``always``,
  ``on_unlock``, or ``never``. Server validator applies a default
  based on the plugin's sensitivity (public → always, sensitive →
  on_unlock) when the publisher leaves this null.

- ``plugin_assets`` (new table) — content-addressed PNG bytes for
  plugin icons. Primary key is the SHA-256 hash so duplicate
  uploads dedupe automatically. ``sender_id`` is captured for ops
  + abuse triage; bytes are opaque to the server otherwise.

All notification fields default in a way that legacy plugins
published before V4 #21 keep behaving sanely: messages still fire
FCM, card_arrived fires FCM, card_updated does NOT (avoids spam
from existing live-card publishers). Icon columns default NULL.

Codex 169 + gemini 169 agreement.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0016_plugin_v4_21"
down_revision = "0015_plugin_sensitivity"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Notification policy fields on plugins.
    op.add_column(
        "plugins",
        sa.Column(
            "notif_message",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("true"),
        ),
    )
    op.add_column(
        "plugins",
        sa.Column(
            "notif_card_arrived",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("true"),
        ),
    )
    op.add_column(
        "plugins",
        sa.Column(
            "notif_card_updated",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
    )
    # Icon metadata on plugins.
    op.add_column(
        "plugins",
        sa.Column("icon_content_hash", sa.LargeBinary(), nullable=True),
    )
    op.add_column(
        "plugins",
        sa.Column("icon_format", sa.Text(), nullable=True),
    )
    op.add_column(
        "plugins",
        sa.Column("icon_background_color", sa.Text(), nullable=True),
    )
    op.add_column(
        "plugins",
        sa.Column("icon_visibility", sa.Text(), nullable=True),
    )

    # Content-addressed asset table for plugin icons.
    op.create_table(
        "plugin_assets",
        sa.Column("content_hash", sa.LargeBinary(), primary_key=True),
        sa.Column("bytes", sa.LargeBinary(), nullable=False),
        sa.Column("format", sa.Text(), nullable=False),
        sa.Column("byte_size", sa.Integer(), nullable=False),
        sa.Column(
            "sender_id",
            sa.Uuid(as_uuid=True),
            sa.ForeignKey("senders.id"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
        ),
    )
    op.create_index(
        "ix_plugin_assets_sender_id",
        "plugin_assets",
        ["sender_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_plugin_assets_sender_id", table_name="plugin_assets")
    op.drop_table("plugin_assets")
    op.drop_column("plugins", "icon_visibility")
    op.drop_column("plugins", "icon_background_color")
    op.drop_column("plugins", "icon_format")
    op.drop_column("plugins", "icon_content_hash")
    op.drop_column("plugins", "notif_card_updated")
    op.drop_column("plugins", "notif_card_arrived")
    op.drop_column("plugins", "notif_message")
