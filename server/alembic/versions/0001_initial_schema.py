"""initial schema

Revision ID: 0001_initial_schema
Revises:
Create Date: 2026-05-20 00:00:00.000000

"""
from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0001_initial_schema"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("email", sa.Text(), nullable=False),
        sa.Column("auth_key_hash", postgresql.BYTEA(), nullable=False),
        sa.Column("encrypted_master_key", postgresql.BYTEA(), nullable=False),
        sa.Column("auth_salt", postgresql.BYTEA(), nullable=False),
        sa.Column("argon2_params_version", sa.Integer(), nullable=False),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.UniqueConstraint("email", name="uq_users_email"),
    )

    op.create_table(
        "senders",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("public_key", postgresql.BYTEA(), nullable=False),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("contact", sa.Text()),
        sa.Column("revoked_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.UniqueConstraint("public_key", name="uq_senders_public_key"),
    )

    op.create_table(
        "devices",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("public_key", postgresql.BYTEA(), nullable=False),
        sa.Column("fcm_token", sa.Text()),
        sa.Column("revoked_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("last_seen", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_devices_user_id_users", ondelete="CASCADE"),
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"])

    op.create_table(
        "pairings",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("sender_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("encrypted_state", postgresql.BYTEA(), nullable=False),
        sa.Column("revoked_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_pairings_user_id_users", ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["sender_id"],
            ["senders.id"],
            name="fk_pairings_sender_id_senders",
            ondelete="CASCADE",
        ),
        sa.UniqueConstraint("user_id", "sender_id", name="uq_pairings_user_id_sender_id"),
    )
    op.create_index("ix_pairings_user_id", "pairings", ["user_id"])
    op.create_index("ix_pairings_sender_id", "pairings", ["sender_id"])

    op.create_table(
        "plugins",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("sender_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version", sa.Text(), nullable=False),
        sa.Column("manifest_hash", postgresql.BYTEA(), nullable=False),
        sa.Column("bundle_hash", postgresql.BYTEA(), nullable=False),
        sa.Column("signature", postgresql.BYTEA(), nullable=False),
        sa.Column("signed_bundle_url", sa.Text(), nullable=False),
        sa.Column("capabilities", postgresql.JSONB(), nullable=False),
        sa.Column("endpoints", postgresql.JSONB(), nullable=False),
        sa.Column("revoked_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("created_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["sender_id"], ["senders.id"], name="fk_plugins_sender_id_senders"),
        sa.UniqueConstraint("sender_id", "version", name="uq_plugins_sender_id_version"),
    )

    op.create_table(
        "messages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("sender_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("plugin_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("encrypted_body_pointer", sa.Text(), nullable=False),
        sa.Column("min_plugin_version", sa.Text()),
        sa.Column("expires_at", postgresql.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("sent_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(["sender_id"], ["senders.id"], name="fk_messages_sender_id_senders"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_messages_user_id_users", ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["plugin_id"], ["plugins.id"], name="fk_messages_plugin_id_plugins"),
    )
    op.create_index("ix_messages_user_id_sent_at_desc", "messages", ["user_id", sa.text("sent_at DESC")])
    op.create_index("ix_messages_expires_at", "messages", ["expires_at"])

    op.create_table(
        "delivery_status",
        sa.Column("message_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("device_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("delivered_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("dismissed_at", postgresql.TIMESTAMP(timezone=True)),
        sa.Column("actioned_at", postgresql.TIMESTAMP(timezone=True)),
        sa.ForeignKeyConstraint(
            ["message_id"],
            ["messages.id"],
            name="fk_delivery_status_message_id_messages",
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name="fk_delivery_status_device_id_devices",
            ondelete="CASCADE",
        ),
    )

    op.create_table(
        "encrypted_user_state",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("state_version", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("encrypted_blob", postgresql.BYTEA(), nullable=False),
        sa.Column("updated_at", postgresql.TIMESTAMP(timezone=True), server_default=sa.func.now()),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name="fk_encrypted_user_state_user_id_users",
            ondelete="CASCADE",
        ),
    )

    op.create_table(
        "rate_limit_events",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("actor_type", sa.Text(), nullable=False),
        sa.Column("actor_id", sa.Text(), nullable=False),
        sa.Column("route", sa.Text(), nullable=False),
        sa.Column("window_start", postgresql.TIMESTAMP(timezone=True), nullable=False),
        sa.Column("count", sa.Integer(), nullable=False, server_default="1"),
        sa.UniqueConstraint(
            "actor_type",
            "actor_id",
            "route",
            "window_start",
            name="uq_rate_limit_events_actor_route_window",
        ),
    )
    op.create_index("ix_rate_limit_events_window_start", "rate_limit_events", ["window_start"])


def downgrade() -> None:
    op.drop_index("ix_rate_limit_events_window_start", table_name="rate_limit_events")
    op.drop_table("rate_limit_events")

    op.drop_table("encrypted_user_state")
    op.drop_table("delivery_status")

    op.drop_index("ix_messages_expires_at", table_name="messages")
    op.drop_index("ix_messages_user_id_sent_at_desc", table_name="messages")
    op.drop_table("messages")

    op.drop_table("plugins")

    op.drop_index("ix_pairings_sender_id", table_name="pairings")
    op.drop_index("ix_pairings_user_id", table_name="pairings")
    op.drop_table("pairings")

    op.drop_index("ix_devices_user_id", table_name="devices")
    op.drop_table("devices")

    op.drop_table("senders")
    op.drop_table("users")
