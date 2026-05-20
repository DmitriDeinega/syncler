from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import current_user
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
    device = await enroll_device(
        db,
        user_id=user.id,
        public_key=decode_base64(payload.public_key, field_name="public_key", exact=32),
        fcm_token=payload.fcm_token,
    )
    return DeviceEnrollResponse(device_id=device.id, created_at=device.created_at)


@router.post("/{device_id}/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(device_id: UUID, user: User = Depends(current_user), db: AsyncSession = Depends(get_db)) -> Response:
    try:
        await revoke_device(db, user_id=user.id, device_id=device_id)
    except DeviceNotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="device not found") from exc

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("", response_model=list[DeviceListItem])
async def list_user_devices(
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> list[DeviceListItem]:
    devices = await list_devices(db, user_id=user.id)
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
