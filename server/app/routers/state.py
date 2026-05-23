"""Encrypted user state sync endpoints (GET/PUT with CAS)."""

from __future__ import annotations

import base64

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import AuthContext, current_auth_context
from app.db import get_db
from app.schemas import (
    StateConflictBody,
    StateGetResponse,
    StatePutRequest,
    StatePutResponse,
    decode_base64,
)
from app.services.events import get_event_bus
from app.services.state import (
    StateConflictError,
    get_state,
    upsert_state_cas,
)

router = APIRouter(tags=["state"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


@router.get("", response_model=StateGetResponse)
async def get_user_state(
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> StateGetResponse:
    state = await get_state(db, ctx.user.id)
    if state is None:
        return StateGetResponse(state_version=0, encrypted_blob="", updated_at=None)
    return StateGetResponse(
        state_version=state.state_version,
        encrypted_blob=_b64(state.encrypted_blob),
        updated_at=state.updated_at,
    )


@router.put("", response_model=StatePutResponse)
async def put_user_state(
    payload: StatePutRequest,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> StatePutResponse:
    new_blob = decode_base64(payload.new_encrypted_blob, field_name="new_encrypted_blob", minimum=1)
    try:
        updated = await upsert_state_cas(
            db,
            user_id=ctx.user.id,
            expected_state_version=payload.expected_state_version,
            new_encrypted_blob=new_blob,
        )
    except StateConflictError as exc:
        # 409 with the current state so client can merge and retry.
        current_blob = _b64(exc.current.encrypted_blob)
        body = StateConflictBody(
            current_state_version=exc.current.state_version,
            current_encrypted_blob=current_blob,
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=body.model_dump(),
        ) from exc
    # SSE hint: notify other foreground devices that the encrypted state
    # blob just changed so they pull the new version instead of waiting
    # for the next refresh tick.
    await get_event_bus().publish_to_user(
        user_id=ctx.user.id,
        event_type="state.changed",
        data={"version": updated.state_version},
    )
    return StatePutResponse(new_state_version=updated.state_version)
