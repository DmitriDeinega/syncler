"""pairings: drop hard unique, add partial unique on active rows

Revision ID: 0003_pairing_partial_unique
Revises: 0002_pending_pairings
Create Date: 2026-05-20 11:00:00.000000

"""

from __future__ import annotations

from typing import Sequence

from alembic import op
import sqlalchemy as sa


revision: str = "0003_pairing_partial_unique"
down_revision: str | None = "0002_pending_pairings"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_constraint("uq_pairings_user_id_sender_id", "pairings", type_="unique")
    op.create_index(
        "uq_pairings_active_user_sender",
        "pairings",
        ["user_id", "sender_id"],
        unique=True,
        postgresql_where="revoked_at IS NULL",
    )


def downgrade() -> None:
    """Reverse the partial-unique migration.

    DANGER: recreating the hard UNIQUE(user_id, sender_id) will fail if
    re-pair-after-revoke has produced multiple historical rows for the
    same (user_id, sender_id). The sanity check below raises explicitly
    so the operator notices and cleans up duplicates first. To inspect:

        SELECT user_id, sender_id, count(*) FROM pairings
        GROUP BY user_id, sender_id HAVING count(*) > 1;

    Then DELETE the redundant revoked rows or skip the downgrade.
    """
    op.drop_index("uq_pairings_active_user_sender", table_name="pairings")

    conn = op.get_bind()
    duplicates = conn.execute(
        sa.text(
            """
            SELECT count(*) FROM (
                SELECT user_id, sender_id
                FROM pairings
                GROUP BY user_id, sender_id
                HAVING count(*) > 1
            ) AS dup
            """
        ),
    ).scalar() or 0
    if duplicates:
        raise RuntimeError(
            f"Cannot recreate hard UNIQUE(user_id, sender_id) — "
            f"{duplicates} (user_id, sender_id) groups have multiple rows. "
            "Resolve duplicates before downgrading; see this migration's docstring."
        )

    op.create_unique_constraint(
        "uq_pairings_user_id_sender_id",
        "pairings",
        ["user_id", "sender_id"],
    )
