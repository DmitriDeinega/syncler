"""V4 #21 — plugin asset (icon) endpoints.

Triad 169 chose first-party hosting (codex option D) over partner
CDN URLs so notifications can rely on a server we control for icon
loads. Bytes live in the new ``plugin_assets`` table; the plugin
manifest references them by SHA-256 content hash.

Two endpoints:

- ``POST /v1/plugins/me/assets`` — sender-authenticated upload.
  Body: ``{sender_id, content_base64, format, sender_signature}``.
  Server validates MIME + size + dimensions (V1 only PNG, ≤100 KB).
  Response: ``{content_hash}`` (base64 SHA-256) — the publisher
  references this in the plugin manifest's ``icon_content_hash``.

- ``GET /v1/plugins/{plugin_row_id}/assets/{content_hash_b64url}``
  — public read of the bytes (content-blind: anyone with the URL
  AND the right hash gets the bytes). Cached forever client-side
  by content-hash. Returns 404 for unknown hash or non-matching
  plugin row.

Codex 169 contract: PNG only V1, ≤100KB cap, ≥96px square.
Server enforces these gates so a misbehaving partner can't store
arbitrary blobs in the assets table.
"""

from __future__ import annotations

import base64
import hashlib
import struct
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto.signatures import verify_message_envelope
from app.db import get_db
from app.middleware.rate_limit import check_rate_limit, rate_limit
from app.middleware.rate_limit_config import RATE_LIMITS
from app.models import Plugin, PluginAsset
from app.schemas import decode_base64
from app.services.senders import (
    SenderNotFoundError,
    SenderRevokedError,
    get_active_sender,
)
import json
from typing import Annotated

router = APIRouter(tags=["plugin-assets"])

# V4 #21 codex 169 hard caps.
MAX_ICON_BYTES = 100 * 1024  # 100 KB
MIN_ICON_DIMENSION = 96
SUPPORTED_FORMATS = {"image/png"}


class AssetUploadRequest(BaseModel):
    sender_id: uuid.UUID
    content_base64: Annotated[str, Field(min_length=1)]
    format: str
    sender_signature: str  # base64 Ed25519 over canonical body


class AssetUploadResponse(BaseModel):
    content_hash: str  # base64 SHA-256


def _upload_envelope_bytes(payload: AssetUploadRequest) -> bytes:
    """Canonical bytes the sender signs over.

    Includes everything that the server will commit to the
    plugin_assets row. Mirrors the canonical-JSON pattern used by
    publish + revoke envelopes.
    """
    body = {
        "sender_id": str(payload.sender_id),
        "content_base64": payload.content_base64,
        "format": payload.format,
    }
    return json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _verify_png_signature(data: bytes) -> tuple[int, int]:
    """Verify PNG magic + return (width, height) from the IHDR chunk.

    Catches partners uploading non-PNG bytes with format=image/png.
    Raises ValueError on malformed data. The IHDR check also gives
    us the dimensions for the ≥96px gate.
    """
    PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
    if not data.startswith(PNG_MAGIC):
        raise ValueError("not a PNG file (magic bytes mismatch)")
    # IHDR chunk: bytes [8:16] = length(4) + type(4); chunk data at [16:]
    # Width is bytes [16:20], height bytes [20:24] (big-endian uint32).
    if len(data) < 24:
        raise ValueError("PNG too short to contain IHDR")
    if data[12:16] != b"IHDR":
        raise ValueError("first chunk is not IHDR")
    width, height = struct.unpack(">II", data[16:24])
    return width, height


@router.post(
    "/me/assets",
    response_model=AssetUploadResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_plugin_asset(
    payload: AssetUploadRequest,
    request: Request,
    # Pre-signature IP bucket — keeps anonymous upload-bomb DoS cheap.
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> AssetUploadResponse:
    """V4 #21 — sender uploads a plugin icon.

    The upload is content-addressed: SHA-256 of the bytes IS the
    primary key. Re-uploading the same icon by the same sender is
    a no-op (returns the existing hash). Cross-sender hash
    collisions in V1 are tolerated: if sender A and sender B both
    upload the same icon bytes, B effectively reuses A's row but
    the dedup is intentional. We track ``sender_id`` on the row
    so ops can audit who first introduced an asset.
    """
    try:
        sender = await get_active_sender(db, payload.sender_id)
    except SenderNotFoundError as exc:
        raise HTTPException(status_code=404, detail="sender not found") from exc
    except SenderRevokedError as exc:
        raise HTTPException(status_code=410, detail="sender revoked") from exc

    sender_sig = decode_base64(
        payload.sender_signature,
        field_name="sender_signature",
        exact=64,
    )
    if not verify_message_envelope(
        sender.public_key,
        _upload_envelope_bytes(payload),
        sender_sig,
    ):
        raise HTTPException(status_code=401, detail="invalid sender signature")

    # Per-sender bucket AFTER signature verification.
    request.state.sender_id = str(payload.sender_id)
    await check_rate_limit(db, request, RATE_LIMITS["plugin_publish"])

    if payload.format not in SUPPORTED_FORMATS:
        raise HTTPException(
            status_code=400,
            detail=(
                f"unsupported asset format {payload.format!r}; "
                f"V1 accepts {sorted(SUPPORTED_FORMATS)}"
            ),
        )

    try:
        raw_bytes = base64.b64decode(payload.content_base64, validate=True)
    except Exception as exc:
        raise HTTPException(
            status_code=400, detail="content_base64 is not valid base64",
        ) from exc

    if len(raw_bytes) == 0:
        raise HTTPException(status_code=400, detail="empty asset payload")
    if len(raw_bytes) > MAX_ICON_BYTES:
        raise HTTPException(
            status_code=400,
            detail=(
                f"asset exceeds {MAX_ICON_BYTES}-byte cap "
                f"(got {len(raw_bytes)} bytes)"
            ),
        )

    try:
        width, height = _verify_png_signature(raw_bytes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if width != height:
        raise HTTPException(
            status_code=400, detail=f"asset must be square (got {width}x{height})",
        )
    if width < MIN_ICON_DIMENSION:
        raise HTTPException(
            status_code=400,
            detail=(
                f"asset must be at least {MIN_ICON_DIMENSION}x{MIN_ICON_DIMENSION} "
                f"(got {width}x{height})"
            ),
        )

    content_hash = hashlib.sha256(raw_bytes).digest()

    # Dedup: insert-or-do-nothing keyed on content_hash.
    existing = await db.get(PluginAsset, content_hash)
    if existing is None:
        db.add(
            PluginAsset(
                content_hash=content_hash,
                bytes_=raw_bytes,
                format=payload.format,
                byte_size=len(raw_bytes),
                sender_id=sender.id,
            ),
        )
        await db.commit()

    return AssetUploadResponse(
        content_hash=base64.b64encode(content_hash).decode("ascii"),
    )


@router.get("/{plugin_row_id}/assets/{content_hash_b64url}")
async def get_plugin_asset(
    plugin_row_id: uuid.UUID,
    content_hash_b64url: str,
    # Anonymous read; IP-based bucket because the path carries
    # plugin_row_id (a UUID) not the sender_id that
    # manifest_fetch's actor lookup expects.
    _: None = Depends(rate_limit("message_send_ip")),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Public read for plugin asset bytes.

    URL-safe base64 (RFC 4648 §5) is used in the path so the hash
    fits cleanly into a URL component without percent-encoding. The
    plugin row id in the path is partly cosmetic — it gives clients
    a stable canonical URL per (plugin, asset) — but we also use it
    to verify the plugin actually references this asset (a bare
    hash lookup would let anyone enumerate the asset table).
    """
    # Compute the right amount of padding rather than always adding
    # "==". Python's base64 decoder is strict about input length
    # being a multiple of 4; over-padding throws.
    padding = (-len(content_hash_b64url)) % 4
    try:
        content_hash = base64.urlsafe_b64decode(content_hash_b64url + "=" * padding)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid content_hash") from exc
    if len(content_hash) != 32:
        raise HTTPException(status_code=400, detail="content_hash must be SHA-256")

    plugin = await db.get(Plugin, plugin_row_id)
    if plugin is None:
        raise HTTPException(status_code=404, detail="plugin not found")
    if plugin.icon_content_hash != content_hash:
        raise HTTPException(
            status_code=404,
            detail="plugin does not reference this asset",
        )

    asset = await db.get(PluginAsset, content_hash)
    if asset is None:
        # Shouldn't happen if the plugin row references it, but
        # guard anyway.
        raise HTTPException(status_code=404, detail="asset bytes missing")

    return Response(
        content=asset.bytes_,
        media_type=asset.format,
        headers={
            # Content-addressed → immutable. Long cache.
            "Cache-Control": "public, max-age=31536000, immutable",
            "ETag": f'"{content_hash_b64url}"',
        },
    )
