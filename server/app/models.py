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
    created_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Pairing(Base):
    __tablename__ = "pairings"
    __table_args__ = (UniqueConstraint("user_id", "sender_id", name="uq_pairings_user_id_sender_id"),)

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
    __table_args__ = (UniqueConstraint("sender_id", "version", name="uq_plugins_sender_id_version"),)

    id: Mapped[UUID] = mapped_column(UUID_TYPE, primary_key=True)
    sender_id: Mapped[UUID] = mapped_column(
        UUID_TYPE,
        ForeignKey("senders.id"),
        nullable=False,
    )
    version: Mapped[str] = mapped_column(Text, nullable=False)
    manifest_hash: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    bundle_hash: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signature: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signed_bundle_url: Mapped[str] = mapped_column(Text, nullable=False)
    capabilities: Mapped[dict] = mapped_column(JSONB_TYPE, nullable=False)
    endpoints: Mapped[dict] = mapped_column(JSONB_TYPE, nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
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
