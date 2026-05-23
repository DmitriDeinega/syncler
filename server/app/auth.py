"""Authentication primitives — JWT issue, JWT decode, route dependencies.

JWTs are device-bound in this milestone: every token carries a `did`
(device_id) claim alongside the `sub` (user_id) claim. Sensitive routes
(state, inbox, message detail, etc.) require [current_auth_context],
which verifies the device exists and has not been revoked.

Pre-device routes (login, signup, the initial enrollment) accept tokens
without a `did` claim by using [current_user] directly. Once the device
is enrolled, the enrollment endpoint replaces the bootstrap token with
a device-bound one.

Migration: clients holding pre-Phase-0 tokens (no `did` claim) get
HTTP 401 with `WWW-Authenticate: device_required` on sensitive routes
and are expected to re-login. One-time pain.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID

import jwt
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer
from jwt import InvalidTokenError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db import get_db
from app.models import Device, User
from app.services.users import get_user_by_id

ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_HOURS = 24

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/v1/auth/login")


@dataclass(frozen=True)
class TokenClaims:
    user_id: UUID
    device_id: UUID | None


@dataclass(frozen=True)
class AuthContext:
    """The fully-resolved authenticated principal for a sensitive request.

    Returned by [current_auth_context]. Carries both the user and the
    specific device making the call so route handlers can scope writes
    (e.g. last_seen bumps, dismiss source-of-truth) to the device that
    actually originated the request.
    """

    user: User
    device: Device


def create_access_token(user_id: UUID, device_id: UUID | None = None) -> str:
    """Issue a JWT.

    `device_id` is None for the short-lived bootstrap token returned by
    login (before the device has been enrolled). After enrollment the
    server issues a device-bound token via [create_device_token].
    """
    now = datetime.now(UTC)
    payload: dict[str, object] = {
        "sub": str(user_id),
        "iat": now,
        "exp": now + timedelta(hours=ACCESS_TOKEN_EXPIRE_HOURS),
    }
    if device_id is not None:
        payload["did"] = str(device_id)
    return jwt.encode(payload, Settings().jwt_secret, algorithm=ALGORITHM)


def create_device_token(user_id: UUID, device_id: UUID) -> str:
    """Issue a device-bound JWT. Equivalent to create_access_token with
    a device_id; named separately for readability at call sites."""
    return create_access_token(user_id=user_id, device_id=device_id)


def _decode_claims(token: str) -> TokenClaims:
    try:
        payload = jwt.decode(token, Settings().jwt_secret, algorithms=[ALGORITHM])
        subject = payload.get("sub")
        if subject is None:
            raise InvalidTokenError("missing sub claim")
        did = payload.get("did")
        device_id = UUID(str(did)) if did is not None else None
        return TokenClaims(user_id=UUID(str(subject)), device_id=device_id)
    except (InvalidTokenError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


def decode_access_token(token: str) -> UUID:
    """Legacy helper retained for compatibility — returns only the user_id.
    Most call sites should migrate to [_decode_claims] or to the
    [current_auth_context] dependency."""
    return _decode_claims(token).user_id


async def current_user(
    request: Request,
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    """User-only dependency for routes that operate before a device has been
    enrolled (signup completion, login).

    For routes that handle user data (state, inbox, message detail, dismiss,
    device listing, device revoke), use [current_auth_context] instead. That
    dependency additionally rejects requests from revoked devices.

    For the enrollment endpoint specifically (`/v1/auth/devices/enroll`),
    use [bootstrap_only_user] — a revoked device-bound token must NOT be
    able to re-enroll and regain access (Codex consultation 51 RED #1).
    """
    claims = _decode_claims(token)
    user = await get_user_by_id(db, claims.user_id)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    request.state.user_id = str(user.id)
    return user


async def bootstrap_only_user(
    request: Request,
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    """Bootstrap-only dependency for `/v1/auth/devices/enroll`.

    Accepts ONLY tokens without a `did` claim (bootstrap tokens from
    `/v1/auth/login`). Tokens carrying `did` are rejected — a revoked
    device's still-valid JWT must not be able to enroll a new device
    and regain access.

    This is the only "user-only" route in the post-Phase-0 surface that
    needs this explicit guard; everywhere else `current_auth_context`
    is the right dependency.
    """
    claims = _decode_claims(token)
    if claims.device_id is not None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="bootstrap token required for enrollment; log in fresh",
            headers={"WWW-Authenticate": "bootstrap_required"},
        )
    user = await get_user_by_id(db, claims.user_id)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    request.state.user_id = str(user.id)
    return user


async def current_auth_context(
    request: Request,
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> AuthContext:
    """Resolves user + device for sensitive routes.

    Rejects requests where:
    - The token lacks a `did` claim (pre-Phase-0 tokens; the
      `WWW-Authenticate: device_required` hint tells clients to re-login
      so the enrollment flow runs and a device-bound token is issued).
    - The device does not exist (e.g. enrollment was rolled back).
    - The device has been revoked (`revoked_at` is set).

    Returns an [AuthContext] so handlers can read `ctx.user` and
    `ctx.device` directly.
    """
    claims = _decode_claims(token)
    if claims.device_id is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="device-bound token required; re-login to enroll",
            headers={"WWW-Authenticate": "device_required"},
        )
    user = await get_user_by_id(db, claims.user_id)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    device_row = await db.execute(
        select(Device).where(Device.id == claims.device_id, Device.user_id == user.id),
    )
    device = device_row.scalar_one_or_none()
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="device not enrolled",
            headers={"WWW-Authenticate": "device_required"},
        )
    if device.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="device revoked",
            headers={"WWW-Authenticate": "device_revoked"},
        )
    request.state.user_id = str(user.id)
    request.state.device_id = str(device.id)
    return AuthContext(user=user, device=device)
