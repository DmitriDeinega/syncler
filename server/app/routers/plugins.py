"""Plugin lifecycle routes — publish, fetch latest, revoke."""

from __future__ import annotations

import base64
import json
import uuid

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import rate_limit
from app.models import Plugin
from app.schemas import (
    PluginLatestResponse,
    PluginPublishRequest,
    PluginPublishResponse,
    PluginRevokeRequest,
    decode_base64,
)
from app.services.plugins import (
    DuplicateVersionError,
    InvalidVersionError,
    PluginNotFoundError,
    VersionRegressionError,
    get_latest_for_plugin,
    publish_plugin,
    revoke_plugin_row,
)
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)
from sqlalchemy import select

router = APIRouter(tags=["plugins"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _publish_envelope(payload: PluginPublishRequest) -> bytes:
    envelope = {
        "sender_id": str(payload.sender_id),
        "plugin_identifier": payload.plugin_identifier,
        "version": payload.version,
        "manifest_hash": payload.manifest_hash,
        "bundle_hash": payload.bundle_hash,
        "signature": payload.signature,
        "signed_bundle_url": payload.signed_bundle_url,
        "capabilities": payload.capabilities,
        "endpoints": payload.endpoints,
    }
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _revoke_envelope(payload: PluginRevokeRequest) -> bytes:
    envelope = {
        "sender_id": str(payload.sender_id),
        "plugin_row_id": str(payload.plugin_row_id),
    }
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


@router.post("/publish", response_model=PluginPublishResponse, status_code=status.HTTP_201_CREATED)
async def publish(
    payload: PluginPublishRequest,
    _: None = Depends(rate_limit("pairing_initiate")),
    db: AsyncSession = Depends(get_db),
) -> PluginPublishResponse:
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=404, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=410, detail="sender revoked") from exc

    sender_sig = decode_base64(payload.sender_signature, field_name="sender_signature", exact=64)
    if not verify_message_envelope(sender.public_key, _publish_envelope(payload), sender_sig):
        raise HTTPException(status_code=401, detail="invalid sender signature")

    try:
        plugin = await publish_plugin(
            db,
            sender_id=sender.id,
            plugin_identifier=payload.plugin_identifier,
            version=payload.version,
            manifest_hash=decode_base64(payload.manifest_hash, field_name="manifest_hash", exact=32),
            bundle_hash=decode_base64(payload.bundle_hash, field_name="bundle_hash", exact=32),
            signature=decode_base64(payload.signature, field_name="signature", exact=64),
            signed_bundle_url=payload.signed_bundle_url,
            capabilities=payload.capabilities,
            endpoints=payload.endpoints,
        )
    except InvalidVersionError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except VersionRegressionError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except DuplicateVersionError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc

    return PluginPublishResponse(
        plugin_row_id=plugin.id,
        plugin_identifier=plugin.plugin_identifier,
        version=plugin.version,
        created_at=plugin.created_at,
    )


@router.get("/{sender_id}/{plugin_identifier}/latest", response_model=PluginLatestResponse)
async def latest(
    sender_id: uuid.UUID,
    plugin_identifier: str,
    _: None = Depends(rate_limit("manifest_fetch")),
    db: AsyncSession = Depends(get_db),
) -> PluginLatestResponse:
    try:
        plugin = await get_latest_for_plugin(
            db, sender_id=sender_id, plugin_identifier=plugin_identifier
        )
    except PluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="plugin not found") from exc

    return PluginLatestResponse(
        plugin_row_id=plugin.id,
        sender_id=plugin.sender_id,
        plugin_identifier=plugin.plugin_identifier,
        version=plugin.version,
        signed_bundle_url=plugin.signed_bundle_url,
        manifest_hash=_b64(plugin.manifest_hash),
        bundle_hash=_b64(plugin.bundle_hash),
        signature=_b64(plugin.signature),
        capabilities=plugin.capabilities,
        endpoints=plugin.endpoints,
        created_at=plugin.created_at,
    )


@router.post("/revoke", status_code=status.HTTP_204_NO_CONTENT)
async def revoke(
    payload: PluginRevokeRequest,
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Sender-authenticated plugin row revoke.

    M8.1 fix: the previous public revoke endpoint allowed anyone with a
    plugin UUID to wipe out a plugin. Now sender signs the revoke envelope
    with their Ed25519 private key, and we also verify the row belongs to
    that sender before flipping revoked_at.
    """
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=404, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=410, detail="sender revoked") from exc

    sender_sig = decode_base64(payload.sender_signature, field_name="sender_signature", exact=64)
    if not verify_message_envelope(sender.public_key, _revoke_envelope(payload), sender_sig):
        raise HTTPException(status_code=401, detail="invalid sender signature")

    plugin_result = await db.execute(select(Plugin).where(Plugin.id == payload.plugin_row_id))
    plugin = plugin_result.scalar_one_or_none()
    if plugin is None:
        raise HTTPException(status_code=404, detail="plugin not found")
    if plugin.sender_id != payload.sender_id:
        # Don't leak existence of foreign-sender's plugins — 404 not 403.
        raise HTTPException(status_code=404, detail="plugin not found")

    try:
        await revoke_plugin_row(db, payload.plugin_row_id)
    except PluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="plugin not found") from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)
