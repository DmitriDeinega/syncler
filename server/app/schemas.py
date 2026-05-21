import base64
import binascii
import importlib.util
from datetime import datetime
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_serializer, field_validator

EmailField = (
    EmailStr
    if importlib.util.find_spec("email_validator")
    else Annotated[str, Field(pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")]
)


def decode_base64(value: str, *, field_name: str, exact: int | None = None, minimum: int | None = None) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(f"{field_name} must be valid base64") from exc

    if exact is not None and len(decoded) != exact:
        raise ValueError(f"{field_name} must decode to {exact} bytes")
    if minimum is not None and len(decoded) < minimum:
        raise ValueError(f"{field_name} must decode to at least {minimum} bytes")

    return decoded


class SignupRequest(BaseModel):
    email: EmailField
    auth_key_hash: str
    encrypted_master_key: str
    auth_salt: str
    argon2_params_version: Annotated[int, Field(ge=1)]

    @field_validator("auth_key_hash")
    @classmethod
    def validate_auth_key_hash(cls, value: str) -> str:
        decode_base64(value, field_name="auth_key_hash", exact=32)
        return value

    @field_validator("encrypted_master_key")
    @classmethod
    def validate_encrypted_master_key(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_master_key", minimum=32)
        return value

    @field_validator("auth_salt")
    @classmethod
    def validate_auth_salt(cls, value: str) -> str:
        decode_base64(value, field_name="auth_salt", exact=16)
        return value


class SignupResponse(BaseModel):
    user_id: UUID
    created_at: datetime


class PreLoginRequest(BaseModel):
    email: EmailField


class PreLoginResponse(BaseModel):
    auth_salt: str
    argon2_params_version: int


class LoginRequest(BaseModel):
    email: EmailField
    auth_key_hash: str

    @field_validator("auth_key_hash")
    @classmethod
    def validate_auth_key_hash(cls, value: str) -> str:
        decode_base64(value, field_name="auth_key_hash", exact=32)
        return value


class LoginResponse(BaseModel):
    user_id: UUID
    session_token: str
    encrypted_master_key: str
    auth_salt: str
    argon2_params_version: int


class DeviceEnrollRequest(BaseModel):
    public_key: str
    fcm_token: str | None = None

    @field_validator("public_key")
    @classmethod
    def validate_public_key(cls, value: str) -> str:
        decode_base64(value, field_name="public_key", exact=32)
        return value


class DeviceEnrollResponse(BaseModel):
    device_id: UUID
    created_at: datetime


class DeviceListItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    created_at: datetime | None
    last_seen: datetime | None
    revoked_at: datetime | None
    has_fcm_token: bool


class SenderRegisterRequest(BaseModel):
    public_key: str
    name: Annotated[str, Field(min_length=1, max_length=200)]
    contact: Annotated[str | None, Field(max_length=200)] = None

    @field_validator("public_key")
    @classmethod
    def validate_public_key(cls, value: str) -> str:
        decode_base64(value, field_name="public_key", exact=32)
        return value


class SenderRegisterResponse(BaseModel):
    sender_id: UUID
    created_at: datetime


class MessageSendRequest(BaseModel):
    sender_id: UUID
    user_id: UUID
    plugin_id: UUID
    encrypted_body: str
    nonce: str
    envelope_signature: str
    min_plugin_version: str | None = None
    expires_at: datetime

    @field_validator("expires_at")
    @classmethod
    def require_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("expires_at must be timezone-aware (ISO-8601 with offset, e.g. ...Z)")
        return value

    @field_validator("encrypted_body")
    @classmethod
    def validate_encrypted_body(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_body", minimum=16)
        return value

    @field_validator("nonce")
    @classmethod
    def validate_nonce(cls, value: str) -> str:
        decode_base64(value, field_name="nonce", exact=12)
        return value

    @field_validator("envelope_signature")
    @classmethod
    def validate_envelope_signature(cls, value: str) -> str:
        decode_base64(value, field_name="envelope_signature", exact=64)
        return value


class MessageSendResponse(BaseModel):
    message_id: UUID
    expires_at: datetime


class MessageInboxItem(BaseModel):
    id: UUID
    sender_id: UUID
    plugin_id: UUID
    # Stable sender-chosen identifier (e.g. "com.lottery.app"). The device
    # uses this to fetch the plugin manifest at
    # `/v1/plugins/{sender_id}/{plugin_identifier}/latest` and render the
    # message's card via the sender's JS plugin bundle.
    plugin_identifier: str
    min_plugin_version: str | None
    encrypted_body: str
    nonce: str
    envelope_signature: str
    sent_at: datetime
    # Required so the recipient can reconstruct AAD/envelope for decrypt + sig verify.
    expires_at: datetime

    # Senders sign/encrypt with a `Z`-suffixed UTC string for `expires_at`
    # (see sdk-python crypto.py + the canonicalization in
    # routers/messages.py:_build_envelope_bytes). Pydantic's default datetime
    # serializer emits `+00:00`, which would make the device's AAD bytes
    # diverge from what the sender encrypted with and break AES-GCM decrypt.
    # Force the canonical form on the wire.
    @field_serializer("expires_at")
    def _serialize_expires_at(self, value: datetime) -> str:
        return value.isoformat().replace("+00:00", "Z")


class MessageInboxResponse(BaseModel):
    messages: list[MessageInboxItem]
    next_since: datetime | None


class MessageDetailResponse(MessageInboxItem):
    pass


class PairingInitiateRequest(BaseModel):
    sender_id: UUID
    ttl_seconds: Annotated[int, Field(ge=1, le=900)] = 300
    metadata: dict | None = None
    signature: str  # Ed25519 over canonical body (sender authentication)

    @field_validator("signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="signature", exact=64)
        return value


class PairingInitiateResponse(BaseModel):
    pairing_id: UUID
    pairing_token: str  # base64
    broker_url: str
    expires_at: datetime


class PairingPreviewResponse(BaseModel):
    sender_id: UUID
    sender_name: str
    sender_public_key: str
    sender_public_key_fingerprint: str
    sender_name_hash: str
    expires_at: datetime


class PairingCompleteRequest(BaseModel):
    pairing_token: str  # url-safe or standard base64 (32 bytes)
    encrypted_initial_state: str  # base64

    @field_validator("pairing_token")
    @classmethod
    def validate_token(cls, value: str) -> str:
        try:
            if "-" in value or "_" in value or not value.endswith("="):
                pad = "=" * (-len(value) % 4)
                decoded = base64.urlsafe_b64decode(value + pad)
            else:
                decoded = base64.b64decode(value, validate=True)
        except Exception as exc:
            raise ValueError("pairing_token must be valid base64") from exc
        if len(decoded) != 32:
            raise ValueError("pairing_token must decode to 32 bytes")
        return value

    @field_validator("encrypted_initial_state")
    @classmethod
    def validate_state(cls, value: str) -> str:
        decode_base64(value, field_name="encrypted_initial_state", minimum=16)
        return value


class PairingCompleteResponse(BaseModel):
    pairing_id: UUID
    sender_id: UUID
    sender_name: str
    sender_public_key: str
    sender_public_key_fingerprint: str
    sender_name_hash: str
    paired_at: datetime


class PairingItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    sender_id: UUID
    created_at: datetime | None
    revoked_at: datetime | None


class StateGetResponse(BaseModel):
    state_version: int
    encrypted_blob: str  # base64
    updated_at: datetime | None


class StatePutRequest(BaseModel):
    expected_state_version: Annotated[int, Field(ge=0)]
    new_encrypted_blob: str  # base64

    @field_validator("new_encrypted_blob")
    @classmethod
    def validate_blob(cls, value: str) -> str:
        decode_base64(value, field_name="new_encrypted_blob", minimum=1)
        return value


class StatePutResponse(BaseModel):
    new_state_version: int


class StateConflictBody(BaseModel):
    current_state_version: int
    current_encrypted_blob: str


class PluginPublishRequest(BaseModel):
    sender_id: UUID
    plugin_identifier: Annotated[str, Field(min_length=1, max_length=200)]
    version: Annotated[str, Field(min_length=1, max_length=64)]
    manifest_hash: str  # base64 32 bytes
    bundle_hash: str    # base64 32 bytes
    signature: str      # base64 64 bytes Ed25519 (bundle signature)
    signed_bundle_url: str
    capabilities: list[str]
    endpoints: list[str]
    sender_signature: str  # base64 Ed25519 over canonical publish body

    @field_validator("manifest_hash")
    @classmethod
    def validate_manifest_hash(cls, value: str) -> str:
        decode_base64(value, field_name="manifest_hash", exact=32)
        return value

    @field_validator("bundle_hash")
    @classmethod
    def validate_bundle_hash(cls, value: str) -> str:
        decode_base64(value, field_name="bundle_hash", exact=32)
        return value

    @field_validator("signature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        decode_base64(value, field_name="signature", exact=64)
        return value

    @field_validator("sender_signature")
    @classmethod
    def validate_sender_signature(cls, value: str) -> str:
        decode_base64(value, field_name="sender_signature", exact=64)
        return value


class PluginPublishResponse(BaseModel):
    plugin_row_id: UUID
    plugin_identifier: str
    version: str
    created_at: datetime


class PluginLatestResponse(BaseModel):
    plugin_row_id: UUID
    sender_id: UUID
    plugin_identifier: str
    version: str
    signed_bundle_url: str
    manifest_hash: str
    bundle_hash: str
    signature: str
    capabilities: list[str]
    endpoints: list[str]
    created_at: datetime


# M11.4: classified revocation reasons. Devices use this to decide UX:
# - ``superseded``: silent; show in update prompts.
# - ``compromised``: refuse to execute; show security alert.
# - ``sender_disabled``: stop rendering; show neutral "unavailable".
# - ``unspecified``: legacy revokes (pre-M11.4) where no reason was given;
#   conservative client treats this as ``compromised`` until clarified.
REVOCATION_REASONS = frozenset({"superseded", "compromised", "sender_disabled", "unspecified"})


class PluginRevokeRequest(BaseModel):
    sender_id: UUID
    plugin_row_id: UUID
    sender_signature: str  # base64 Ed25519 over canonical body
    # Optional in V1 for backwards compatibility with the M8.1 contract;
    # senders SHOULD always supply one. Missing reason → stored as null;
    # client code interprets null as ``unspecified``.
    reason: str | None = None

    @field_validator("sender_signature")
    @classmethod
    def validate_sender_signature(cls, value: str) -> str:
        decode_base64(value, field_name="sender_signature", exact=64)
        return value

    @field_validator("reason")
    @classmethod
    def validate_reason(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if value not in REVOCATION_REASONS:
            raise ValueError(
                f"reason must be one of {sorted(REVOCATION_REASONS)}; got {value!r}"
            )
        return value
