import base64
import binascii
import importlib.util
from datetime import datetime
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator

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
