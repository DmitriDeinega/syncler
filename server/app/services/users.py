import secrets
from datetime import UTC, datetime
from uuid import UUID, uuid4

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.models import User

DEFAULT_ARGON2_PARAMS_VERSION = 1
PRE_LOGIN_FAKE_SALT_LENGTH_BYTES = 16
PRE_LOGIN_FAKE_SALT_INFO_PREFIX = b"syncler-v1-pre-login-salt:"


class UserAlreadyExistsError(Exception):
    pass


class InvalidCredentialsError(Exception):
    pass


async def create_user(
    db: AsyncSession,
    *,
    email: str,
    auth_key_hash: bytes,
    encrypted_master_key: bytes,
    auth_salt: bytes,
    argon2_params_version: int,
    user_id: UUID | None = None,
) -> User:
    """Insert a new user row.

    Phase 8d: ``user_id`` is now an optional caller-supplied UUID
    (docs/crypto-spec.md §10.9 — the MK wrap AAD includes user_id,
    so a Phase-8d-aware client generates the UUID locally and
    sends it). ``None`` → server generates (pre-Phase-8d behavior).
    """
    existing_user = await get_user_by_email(db, email)
    if existing_user is not None:
        raise UserAlreadyExistsError

    user = User(
        id=user_id or uuid4(),
        email=email,
        auth_key_hash=auth_key_hash,
        encrypted_master_key=encrypted_master_key,
        auth_salt=auth_salt,
        argon2_params_version=argon2_params_version,
        created_at=datetime.now(UTC),
    )
    db.add(user)

    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise UserAlreadyExistsError from exc

    await db.refresh(user)
    return user


async def get_user_by_email(db: AsyncSession, email: str) -> User | None:
    result = await db.execute(select(User).where(User.email == email))
    return result.scalar_one_or_none()


async def get_user_by_id(db: AsyncSession, user_id: UUID) -> User | None:
    result = await db.execute(select(User).where(User.id == user_id))
    return result.scalar_one_or_none()


async def authenticate_user(db: AsyncSession, *, email: str, auth_key_hash: bytes) -> User:
    user = await get_user_by_email(db, email)
    if user is None or not secrets.compare_digest(auth_key_hash, user.auth_key_hash):
        raise InvalidCredentialsError

    return user


async def pre_login_salt(db: AsyncSession, email: str) -> tuple[bytes, int]:
    user = await get_user_by_email(db, email)
    if user is not None:
        return user.auth_salt, user.argon2_params_version

    normalized_email = email.strip().lower().encode("utf-8")
    fake_salt = HKDF(
        algorithm=hashes.SHA256(),
        length=PRE_LOGIN_FAKE_SALT_LENGTH_BYTES,
        salt=None,
        info=PRE_LOGIN_FAKE_SALT_INFO_PREFIX + normalized_email,
    ).derive(Settings().pre_login_pepper.encode("utf-8"))
    return fake_salt, DEFAULT_ARGON2_PARAMS_VERSION


async def delete_user(db: AsyncSession, user: User) -> None:
    managed_user = await db.merge(user)
    await db.delete(managed_user)
    await db.commit()
