"""Phase 8b — master-key rotation schema

Implements docs/crypto-spec.md §10.3:

  - users.key_generation INTEGER NOT NULL DEFAULT 1
  - encrypted_user_state.key_generation INTEGER NOT NULL DEFAULT 1
  - pairings.state_version INTEGER NOT NULL DEFAULT 1
  - pairings.key_generation INTEGER NOT NULL DEFAULT 1
  - rotation_challenges (PK = bytea challenge, expires_at index)
  - master_key_rotation_audit (BIGSERIAL PK)

`encrypted_user_state.state_version` already exists (M7 CAS); not
re-added.

Plan: docs/crypto-spec.md §10. Triad consult 103.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0010_master_key_rotation"
down_revision = "0009_nonce_replay"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column(
            "key_generation",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )
    op.add_column(
        "encrypted_user_state",
        sa.Column(
            "key_generation",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )
    op.add_column(
        "pairings",
        sa.Column(
            "state_version",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )
    op.add_column(
        "pairings",
        sa.Column(
            "key_generation",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )

    op.create_table(
        "rotation_challenges",
        sa.Column("challenge", sa.LargeBinary(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("session_id", sa.Uuid(), nullable=False),
        sa.Column(
            "expires_at",
            sa.DateTime(timezone=True),
            nullable=False,
        ),
        sa.CheckConstraint(
            "octet_length(challenge) = 32",
            name="ck_rotation_challenges_challenge_length",
        ),
    )
    op.create_index(
        "ix_rotation_challenges_expiry",
        "rotation_challenges",
        ["expires_at"],
    )

    op.create_table(
        "master_key_rotation_audit",
        sa.Column(
            "id",
            sa.BigInteger().with_variant(
                sa.dialects.postgresql.BIGINT(), "postgresql"
            ),
            primary_key=True,
            autoincrement=True,
        ),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("old_generation", sa.Integer(), nullable=False),
        sa.Column("new_generation", sa.Integer(), nullable=False),
        sa.Column("initiating_session_id", sa.Uuid(), nullable=True),
        sa.Column("initiating_device_id", sa.Uuid(), nullable=True),
        sa.Column("ip", sa.Text(), nullable=True),
        sa.Column("user_agent", sa.Text(), nullable=True),
        sa.Column("paired_count", sa.Integer(), nullable=False),
        sa.Column(
            "occurred_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "reason IN ('password_rewrap','root_hygiene_rotation','root_compromise_rotation')",
            name="ck_mkr_audit_reason",
        ),
    )
    # Spec §10.3 uses (user_id, occurred_at DESC) so the most-recent-
    # rotations queries (step 5 success rate-limit; admin/incident
    # forensics) are an index seek + reverse scan. ``user_id`` uses
    # the plain column reference so ``alembic revision --autogenerate``
    # sees it as a column-index, not an expression-index, and won't
    # flag a phantom diff on future migrations (Gemini 105 nit).
    op.create_index(
        "ix_mkr_audit_user_time",
        "master_key_rotation_audit",
        ["user_id", sa.text("occurred_at DESC")],
        postgresql_using="btree",
    )


def downgrade() -> None:
    op.drop_index("ix_mkr_audit_user_time", table_name="master_key_rotation_audit")
    op.drop_table("master_key_rotation_audit")
    op.drop_index("ix_rotation_challenges_expiry", table_name="rotation_challenges")
    op.drop_table("rotation_challenges")
    op.drop_column("pairings", "key_generation")
    op.drop_column("pairings", "state_version")
    op.drop_column("encrypted_user_state", "key_generation")
    op.drop_column("users", "key_generation")
