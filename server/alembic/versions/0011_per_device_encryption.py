"""Phase 9b — per-device envelope encryption schema

Implements docs/crypto-spec.md §11:

  - devices.encryption_public_key BYTEA NULL (32-byte X25519)
    CHECK octet_length(encryption_public_key) = 32 (when not null)
  - devices.updated_at TIMESTAMP for the sender directory response
  - users.device_directory_version BIGINT NOT NULL DEFAULT 0
    Monotonic counter bumped on any enrollment / revocation /
    encryption_public_key rotation. Used by senders to detect a
    stale recipient set (§11.10).
  - data migration: delete all rows from live_cards (they're V1-
    encrypted with pairing_key which the new client can't derive;
    V0.1 dev-mode tolerates this per spec §11.14).

The encryption_public_key column is NULL for V1 dev devices that
existed before this migration. The new client re-enrolls on
first launch and populates the column; a follow-up migration
(0012) will tighten to NOT NULL once all active devices have
re-enrolled. Until then, server-side publish validation skips
devices with NULL encryption_public_key from the active set
(§11.10's active_devices filter).

Plan: docs/crypto-spec.md §11. Triad consults 123-127.
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "0011_per_device_encryption"
down_revision = "0010_master_key_rotation"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "devices",
        sa.Column("encryption_public_key", sa.LargeBinary(), nullable=True),
    )
    op.create_check_constraint(
        "ck_devices_encryption_public_key_length",
        "devices",
        "encryption_public_key IS NULL OR octet_length(encryption_public_key) = 32",
    )
    op.add_column(
        "devices",
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
    )

    op.add_column(
        "users",
        sa.Column(
            "device_directory_version",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )

    # V0.1 dev-mode: V1 live cards are encrypted with pairing_key the
    # new client cannot derive. Drop them; the next sender publish
    # repopulates under the V2 envelope. See §11.14.
    op.execute("DELETE FROM live_cards")

    # Same reasoning for messages: V1 wire format is incompatible.
    # Empty inbox on first V2 launch is the expected dev-mode behavior.
    op.execute("DELETE FROM delivery_status")
    op.execute("DELETE FROM messages")

    # Restructure live_cards for V2 wire. The V1 columns
    # (encrypted_payload, nonce) carried the raw pairing-key-AEAD wire;
    # V2 stores the full envelope (including per-device HPKE wraps and
    # the envelope_signature) in encrypted_body_pointer alongside the
    # card metadata fields. The table is already empty from the
    # DELETE above, so the schema change is destruction-safe.
    op.drop_column("live_cards", "encrypted_payload")
    op.drop_column("live_cards", "nonce")
    op.add_column(
        "live_cards",
        sa.Column("encrypted_body_pointer", sa.Text(), nullable=False, server_default=""),
    )
    op.add_column(
        "live_cards",
        sa.Column("card_type", sa.Text(), nullable=False, server_default="standard_card"),
    )
    op.add_column(
        "live_cards",
        sa.Column("min_plugin_version", sa.Text(), nullable=True),
    )
    # Drop the server_defaults; they were only there to satisfy NOT
    # NULL on the (empty) table at migration time. New rows must
    # supply values explicitly.
    op.alter_column("live_cards", "encrypted_body_pointer", server_default=None)
    op.alter_column("live_cards", "card_type", server_default=None)


def downgrade() -> None:
    op.drop_column("live_cards", "min_plugin_version")
    op.drop_column("live_cards", "card_type")
    op.drop_column("live_cards", "encrypted_body_pointer")
    op.add_column("live_cards", sa.Column("encrypted_payload", sa.LargeBinary(), nullable=False, server_default=b""))
    op.add_column("live_cards", sa.Column("nonce", sa.LargeBinary(), nullable=False, server_default=b""))
    op.drop_column("users", "device_directory_version")
    op.drop_column("devices", "updated_at")
    op.drop_constraint(
        "ck_devices_encryption_public_key_length",
        "devices",
        type_="check",
    )
    op.drop_column("devices", "encryption_public_key")
