from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, bootstrap_only_user, create_device_token, current_auth_context
from app.db import get_db
from app.models import User
from app.schemas import DeviceEnrollRequest, DeviceEnrollResponse, DeviceListItem, decode_base64
from app.services.devices import DeviceNotFoundError, enroll_device, list_devices, revoke_device
from app.services.events import get_event_bus

router = APIRouter(tags=["devices"])


@router.post("/enroll", response_model=DeviceEnrollResponse, status_code=status.HTTP_201_CREATED)
async def enroll(
    payload: DeviceEnrollRequest,
    user: User = Depends(bootstrap_only_user),
    db: AsyncSession = Depends(get_db),
) -> DeviceEnrollResponse:
    """Enroll a device and return a device-bound JWT.

    Authenticated by a BOOTSTRAP token (no `did` claim) from /v1/auth/login.
    A revoked device's still-valid device-bound JWT MUST NOT be able to
    enroll a new device and regain access — `bootstrap_only_user` rejects
    any token that carries a `did` claim with 401 `bootstrap_required`.
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

    # Close any open SSE stream for the revoked device immediately,
    # rather than waiting for its JWT to expire. The revoked device
    # would also be rejected by current_auth_context on its next
    # request, but a long-lived event stream that's already past
    # handshake would otherwise stay open.
    await get_event_bus().close_device_subscribers(device_id)
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
