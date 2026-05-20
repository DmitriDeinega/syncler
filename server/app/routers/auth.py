import base64

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import create_access_token, current_user, rate_limit
from app.db import get_db
from app.models import User
from app.schemas import LoginRequest, LoginResponse, SignupRequest, SignupResponse, decode_base64
from app.services.users import (
    InvalidCredentialsError,
    UserAlreadyExistsError,
    authenticate_user,
    create_user,
    delete_user,
)

router = APIRouter(tags=["auth"])
account_router = APIRouter(tags=["account"])


def _encode_base64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


@router.post("/signup", response_model=SignupResponse, status_code=status.HTTP_201_CREATED)
async def signup(payload: SignupRequest, db: AsyncSession = Depends(get_db)) -> SignupResponse:
    try:
        user = await create_user(
            db,
            email=str(payload.email),
            auth_key_hash=decode_base64(payload.auth_key_hash, field_name="auth_key_hash", exact=32),
            encrypted_master_key=decode_base64(
                payload.encrypted_master_key,
                field_name="encrypted_master_key",
                minimum=32,
            ),
            auth_salt=decode_base64(payload.auth_salt, field_name="auth_salt", exact=16),
            argon2_params_version=payload.argon2_params_version,
        )
    except UserAlreadyExistsError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="email already exists") from exc

    return SignupResponse(user_id=user.id, created_at=user.created_at)


@router.post("/login", response_model=LoginResponse)
async def login(
    payload: LoginRequest,
    _: None = Depends(rate_limit("login")),
    db: AsyncSession = Depends(get_db),
) -> LoginResponse:
    try:
        user = await authenticate_user(
            db,
            email=str(payload.email),
            auth_key_hash=decode_base64(payload.auth_key_hash, field_name="auth_key_hash", exact=32),
        )
    except InvalidCredentialsError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials") from exc

    return LoginResponse(
        user_id=user.id,
        session_token=create_access_token(user.id),
        encrypted_master_key=_encode_base64(user.encrypted_master_key),
        auth_salt=_encode_base64(user.auth_salt),
        argon2_params_version=user.argon2_params_version,
    )


@account_router.delete("/account", status_code=status.HTTP_204_NO_CONTENT)
async def delete_account(user: User = Depends(current_user), db: AsyncSession = Depends(get_db)) -> Response:
    await delete_user(db, user)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
