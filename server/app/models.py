from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, Integer
from sqlalchemy import JSON, LargeBinary, Text, UniqueConstraint, Uuid, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.sql import func

JSONB_TYPE = JSONB().with_variant(JSON(), "sqlite")
UUID_TYPE = Uuid(as_uuid=True)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("email", name="uq_users_email"),)

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    email: Mapped[str] = mapped_column(Text, nullable=False)
    auth_key_hash: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    encrypted_master_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    auth_salt: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    argon2_params_version: Mapped[int] = mapped_column(Integer, nullable=False)
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    public_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    fcm_token: Mapped[str | None] = mapped_column(Text)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_seen: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Sender(Base):
    __tablename__ = "senders"
    __table_args__ = (UniqueConstraint("public_key", name="uq_senders_public_key"),)

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    public_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    contact: Mapped[str | None] = mapped_column(Text)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # V1.5 automated pairing (Phase 5a-2): sender's X25519 bootstrap public
    # key + Ed25519 signature over `"syncler-v1-bootstrap-key:" || raw_pub`.
    # Both nullable until the sender opts into the automated pairing flow.
    # Spec: docs/crypto-spec.md §9.
    bootstrap_key: Mapped[bytes | None] = mapped_column(LargeBinary)
    bootstrap_key_signature: Mapped[bytes | None] = mapped_column(LargeBinary)
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Pairing(Base):
    __tablename__ = "pairings"
    # No hard UNIQUE(user_id, sender_id) — re-pair after revoke must work.
    # Migration 0003 replaces the M1.3 constraint with a partial unique
    # index on (user_id, sender_id) WHERE revoked_at IS NULL.
    # The ORM-side Index below mirrors that so Base.metadata.create_all()
    # (used by tests/conftest.py) gets the same enforcement without Alembic.
    __table_args__ = (
        Index(
            "uq_pairings_active_user_sender",
            "user_id",
            "sender_id",
            unique=True,
            postgresql_where=text("revoked_at IS NULL"),
            sqlite_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    encrypted_state: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Plugin(Base):
    __tablename__ = "plugins"
    __table_args__ = (
        UniqueConstraint(
            "sender_id",
            "plugin_identifier",
            "version",
            name="uq_plugins_sender_identifier_version",
        ),
        Index("ix_plugins_sender_identifier", "sender_id", "plugin_identifier"),
    )

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id"),
        nullable=False,
    )
    # Sender-chosen stable identity (e.g. "com.lottery.app"). Decouples
    # the logical plugin from per-version row UUIDs.
    plugin_identifier: Mapped[str] = mapped_column(Text, nullable=False)
    version: Mapped[str] = mapped_column(Text, nullable=False)
    manifest_hash: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    bundle_hash: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signature: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signed_bundle_url: Mapped[str] = mapped_column(Text, nullable=False)
    capabilities: Mapped[dict] = mapped_column(JSONB_TYPE, nullable=False)
    endpoints: Mapped[dict] = mapped_column(JSONB_TYPE, nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # M11.4: optional classification of why this plugin row was revoked.
    # See revoke endpoint for the accepted enum and per-reason UX contract.
    revocation_reason: Mapped[str | None] = mapped_column(Text)
    # Phase 3a: "script" (legacy WebView bundle) or "template" (native
    # Compose renderer). Backfilled to "script" for pre-existing rows so
    # the latest endpoint can safely return a non-null value.
    renderer: Mapped[str] = mapped_column(Text, nullable=False, server_default="script")
    # Phase 3a: the template manifest block when renderer == "template".
    # Null otherwise. The publish-time validator in routers/plugins.py
    # rejects mismatched (renderer, template) pairs before insert.
    template: Mapped[dict | None] = mapped_column(JSONB_TYPE, nullable=True)
    # Phase 3b: card_type ("event" or "live") and optional card_key_path
    # for live cards to extract their stable identity from the payload.
    card_type: Mapped[str] = mapped_column(Text, nullable=False, server_default="event")
    card_key_path: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (Index("ix_messages_user_id_sent_at_desc", "user_id", text("sent_at DESC")),)

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id"),
        nullable=False,
    )
    user_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    plugin_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("plugins.id"),
        nullable=False,
    )
    encrypted_body_pointer: Mapped[str] = mapped_column(Text, nullable=False)
    min_plugin_version: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class DeliveryStatus(Base):
    __tablename__ = "delivery_status"

    message_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("messages.id", ondelete="CASCADE"),
        primary_key=True,
    )
    device_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("devices.id", ondelete="CASCADE"),
        primary_key=True,
    )
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    dismissed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    actioned_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class LiveCard(Base):
    __tablename__ = "live_cards"
    __table_args__ = (
        UniqueConstraint("sender_id", "user_id", "card_key", name="uq_live_cards_sender_user_key"),
    )

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id"),
        nullable=False,
        index=True,
    )
    plugin_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("plugins.id"),
        nullable=False,
    )
    card_key: Mapped[str] = mapped_column(Text, nullable=False)
    encrypted_payload: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    nonce: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    sequence_number: Mapped[int] = mapped_column(
        BigInteger, nullable=False, server_default="0"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)


class EncryptedUserState(Base):
    __tablename__ = "encrypted_user_state"

    user_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )
    state_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    encrypted_blob: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class PendingPairing(Base):
    """Sender-initiated pending pairing waiting for user-side completion."""

    __tablename__ = "pending_pairings"
    __table_args__ = (UniqueConstraint("pairing_token", name="uq_pending_pairings_token"),)

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    pairing_token: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    metadata_json: Mapped[dict | None] = mapped_column(JSONB_TYPE)
    # V1.5 automated pairing (Phase 5a-2): sender-operated broker URL that
    # the device POSTs the encrypted bootstrap envelope to after confirm.
    # NULL when the sender opted out of automated pairing (V1 manual flow).
    # Stored on PendingPairing (not Pairing) because the URL is only
    # consumed during the initiate → preview → bootstrap-POST cycle; the
    # final Pairing row doesn't need it for ongoing operation. The signed
    # initiate envelope binds this value so the syncler server can't
    # substitute it.
    sender_broker_url: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class RateLimitEvent(Base):
    __tablename__ = "rate_limit_events"
    __table_args__ = (
        UniqueConstraint(
            "actor_type",
            "actor_id",
            "route",
            "window_start",
            name="uq_rate_limit_events_actor_route_window",
        ),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    actor_type: Mapped[str] = mapped_column(Text, nullable=False)
    actor_id: Mapped[str] = mapped_column(Text, nullable=False)
    route: Mapped[str] = mapped_column(Text, nullable=False)
    window_start: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
