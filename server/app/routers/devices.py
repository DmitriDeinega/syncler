from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, create_device_token, current_auth_context, current_user
from app.db import get_db
from app.models import User
from app.schemas import DeviceEnrollRequest, DeviceEnrollResponse, DeviceListItem, decode_base64
from app.services.devices import DeviceNotFoundError, enroll_device, list_devices, revoke_device

router = APIRouter(tags=["devices"])


@router.post("/enroll", response_model=DeviceEnrollResponse, status_code=status.HTTP_201_CREATED)
async def enroll(
    payload: DeviceEnrollRequest,
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> DeviceEnrollResponse:
    """Enroll a device and return a device-bound JWT.

    Authenticated by the user-only bootstrap token from /v1/auth/login (the
    device doesn't exist yet, so this endpoint can't require a device-bound
    token itself). The response includes a fresh device-bound JWT that the
    client is expected to use for all subsequent calls to sensitive routes.
    """
    device = await enroll_device(
        db,
        user_id=user.id,
        public_key=decode_base64(payload.public_key, field_name="public_key", exact=32),
        fcm_token=payload.fcm_token,
    )
    return DeviceEnrollResponse(
        device_id=device.id,
        created_at=device.created_at,
        session_token=create_device_token(user_id=user.id, device_id=device.id),
    )


@router.post("/{device_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(
    device_id: UUID,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Revoke a device. Authenticated by a still-valid device-bound token —
    a revoked device can't revoke itself or any sibling because its own
    requests are rejected at the auth layer."""
    try:
        await revoke_device(db, user_id=ctx.user.id, device_id=device_id)
    except DeviceNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="device not found") from exc

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("", response_model=list[DeviceListItem])
async def list_user_devices(
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> list[DeviceListItem]:
    devices = await list_devices(db, user_id=ctx.user.id)
    return [
        DeviceListItem(
            id=device.id,
            created_at=device.created_at,
            last_seen=device.last_seen,
            revoked_at=device.revoked_at,
            has_fcm_token=device.fcm_token is not None,
        )
        for device in devices
    ]
