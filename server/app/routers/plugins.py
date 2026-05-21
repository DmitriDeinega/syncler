"""Plugin lifecycle routes — publish, fetch latest, revoke."""

from __future__ import annotations

import base64
import json
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
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
    get_plugin_by_id,
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
    # Canonical 3-field envelope. Reason is part of the signed bytes so a
    # man-in-the-middle can't strip a "compromised" reason down to a silent
    # "superseded". Backwards compat with M8.1 clients: when reason is None
    # we omit it from the envelope so the signature shape matches.
    envelope: dict[str, str] = {
        "sender_id": str(payload.sender_id),
        "plugin_row_id": str(payload.plugin_row_id),
    }
    if payload.reason is not None:
        envelope["reason"] = payload.reason
    return json.dumps(envelope, sort_keys=True, separators=(",", ":")).encode("utf-8")


@router.post("/publish", response_model=PluginPublishResponse, status_code=status.HTTP_201_CREATED)
async def publish(
    payload: PluginPublishRequest,
    request: Request,
    # Pre-auth IP bucket — cheap, prevents body-parsing DoS from anonymous
    # callers. The per-sender bucket is applied below, after sig verification.
    _: None = Depends(rate_limit("message_send_ip")),
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

    # Per-sender bucket AFTER signature verification — a spoofer cannot inflate
    # someone else's bucket by setting a foreign sender_id in the body, because
    # they cannot forge the matching Ed25519 signature.
    request.state.sender_id = str(payload.sender_id)
    await check_rate_limit(db, request, RATE_LIMITS["plugin_publish"])

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
        # Active rows by definition have revoked_at IS NULL; surface the
        # fields anyway so clients that consume both endpoints don't need
        # different code paths.
        revoked_at=plugin.revoked_at,
        revocation_reason=plugin.revocation_reason,
    )


@router.get("/by-id/{plugin_row_id}", response_model=PluginLatestResponse)
async def by_id(
    plugin_row_id: uuid.UUID,
    # IP-based bucket: /by-id is unauthenticated and has no sender_id in
    # the path for the per-sender bucket to key off. message_send_ip is
    # the cheap pre-auth bucket we use for the same shape elsewhere.
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> PluginLatestResponse:
    """Resolve a plugin manifest by its exact row UUID.

    Devices use this for historical lookups (an inbox message carries the
    plugin_row_id it was originally validated against; resolving by row
    UUID guarantees the device renders against the EXACT bundle that was
    in effect at message-send time, even after the sender publishes a
    newer or revoked version under the same plugin_identifier).

    Unlike `/latest`, this endpoint returns revoked rows too — with
    `revoked_at` and `revocation_reason` populated so the device can
    apply differentiated UX (refuse-to-execute for `compromised`,
    neutral fallback for `sender_disabled`, etc.).
    """
    try:
        plugin = await get_plugin_by_id(db, plugin_row_id=plugin_row_id)
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
        revoked_at=plugin.revoked_at,
        revocation_reason=plugin.revocation_reason,
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
        await revoke_plugin_row(db, payload.plugin_row_id, reason=payload.reason)
    except PluginNotFoundError as exc:
        raise HTTPException(status_code=404, detail="plugin not found") from exc
    return Response(status_code=status.HTTP_204_NO_CONTENT)
