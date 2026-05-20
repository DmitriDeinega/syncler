import secrets
from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User


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
) -> User:
    existing_user = await get_user_by_email(db, email)
    if existing_user is not None:
        raise UserAlreadyExistsError

    user = User(
        id=uuid4(),
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


async def delete_user(db: AsyncSession, user: User) -> None:
    managed_user = await db.merge(user)
    await db.delete(managed_user)
    await db.commit()
