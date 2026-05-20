"""plugins: add plugin_identifier to support multi-version plugin lifecycle

Revision ID: 0004_plugin_identifier
Revises: 0003_pairing_partial_unique
Create Date: 2026-05-20 12:00:00.000000

In the M1.3 schema, ``plugins.id`` is the row PK AND the plugin's stable
identity, which makes publishing a second version impossible (PK collision).

This migration adds a ``plugin_identifier`` column (sender-chosen stable
string, e.g. "com.lottery.app") so each row can be a unique
(sender_id, plugin_identifier, version) tuple while ``plugins.id`` stays
the per-row UUID PK that ``messages.plugin_id`` keeps referencing.
"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa


revision: str = "0004_plugin_identifier"
down_revision: str | None = "0003_pairing_partial_unique"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1. Add plugin_identifier column nullable, then backfill, then NOT NULL.
    op.add_column("plugins", sa.Column("plugin_identifier", sa.Text(), nullable=True))

    # Backfill: any existing rows use their row id as identifier (lossy but
    # there were no real upgrade rows pre-M8.1).
    op.execute(
        "UPDATE plugins SET plugin_identifier = CAST(id AS TEXT) WHERE plugin_identifier IS NULL"
    )
    op.alter_column("plugins", "plugin_identifier", nullable=False)

    # 2. Replace UNIQUE(sender_id, version) with UNIQUE(sender_id, plugin_identifier, version).
    op.drop_constraint("uq_plugins_sender_id_version", "plugins", type_="unique")
    op.create_unique_constraint(
        "uq_plugins_sender_identifier_version",
        "plugins",
        ["sender_id", "plugin_identifier", "version"],
    )

    # 3. Helpful index for "latest version of plugin X".
    op.create_index(
        "ix_plugins_sender_identifier",
        "plugins",
        ["sender_id", "plugin_identifier"],
    )


def downgrade() -> None:
    op.drop_index("ix_plugins_sender_identifier", table_name="plugins")
    op.drop_constraint("uq_plugins_sender_identifier_version", "plugins", type_="unique")
    op.create_unique_constraint(
        "uq_plugins_sender_id_version",
        "plugins",
        ["sender_id", "version"],
    )
    op.drop_column("plugins", "plugin_identifier")
