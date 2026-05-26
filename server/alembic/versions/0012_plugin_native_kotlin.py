"""Phase 11b — native Kotlin plugin runtime schema

Adds two nullable columns to ``plugins`` for the
``renderer = "native_kotlin"`` path. The columns are nullable
because they're meaningful ONLY for native plugins; script /
template renderers leave them NULL.

  - plugins.entry_class TEXT NULL
  - plugins.native_sdk_abi INTEGER NULL

A CHECK constraint enforces the (entry_class, native_sdk_abi,
renderer) tri-state: both NOT NULL iff renderer ==
"native_kotlin"; both NULL otherwise.

Plan: docs/plugin-host-native-kotlin.md. Triad consults 132-135.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0012_plugin_native_kotlin"
down_revision = "0011_per_device_encryption"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("plugins", sa.Column("entry_class", sa.Text(), nullable=True))
    op.add_column("plugins", sa.Column("native_sdk_abi", sa.Integer(), nullable=True))
    # CHECK that the two native-only columns travel together with the
    # renderer kind. Both NOT NULL iff renderer is "native_kotlin"; both
    # NULL for "script" / "template". A buggy router that mismatches
    # would fail this constraint at INSERT time.
    op.create_check_constraint(
        "ck_plugins_native_kotlin_columns",
        "plugins",
        (
            "(renderer = 'native_kotlin'"
            " AND entry_class IS NOT NULL"
            " AND native_sdk_abi IS NOT NULL)"
            " OR (renderer != 'native_kotlin'"
            " AND entry_class IS NULL"
            " AND native_sdk_abi IS NULL)"
        ),
    )


def downgrade() -> None:
    op.drop_constraint("ck_plugins_native_kotlin_columns", "plugins", type_="check")
    op.drop_column("plugins", "native_sdk_abi")
    op.drop_column("plugins", "entry_class")
