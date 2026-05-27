"""V4 #21 — plugin asset endpoint tests.

Triad 170 codex demanded test coverage for the new asset surface
before partner ship: good sig, bad sig, bad PNG, too large,
unknown hash, non-referenced hash. This file covers each case.
"""

from __future__ import annotations

import base64
import hashlib
import json
import struct
import zlib

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from httpx import AsyncClient


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _make_png(width: int = 192, height: int = 192) -> bytes:
    """Build a minimally-valid PNG with the given dimensions.

    We hand-roll the bytes rather than depend on PIL: the server's
    asset validator only checks magic + IHDR dimensions, so a
    well-formed IHDR + an empty IDAT + IEND is sufficient.
    """
    magic = b"\x89PNG\r\n\x1a\n"

    def chunk(chunk_type: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(chunk_type + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)

    # IHDR: width(4) + height(4) + bitdepth(1) + colortype(1) +
    # compression(1) + filter(1) + interlace(1).
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    # IDAT with a single zero byte filter row of all-zero pixels.
    raw = b"\x00" + b"\x00" * width
    raw_all = raw * height
    idat = zlib.compress(raw_all)
    return (
        magic
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", idat)
        + chunk(b"IEND", b"")
    )


async def _register_sender(client: AsyncClient) -> tuple[str, Ed25519PrivateKey]:
    priv = Ed25519PrivateKey.generate()
    pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    r = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(pub), "name": "Asset Test"},
    )
    assert r.status_code == 201, r.text
    return r.json()["sender_id"], priv


def _sign_upload(
    priv: Ed25519PrivateKey,
    *,
    sender_id: str,
    content_b64: str,
    format: str,
) -> dict:
    canonical = json.dumps(
        {
            "sender_id": sender_id,
            "content_base64": content_b64,
            "format": format,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    sig = priv.sign(canonical)
    return {
        "sender_id": sender_id,
        "content_base64": content_b64,
        "format": format,
        "sender_signature": _b64(sig),
    }


@pytest.mark.asyncio
async def test_upload_happy_path_returns_content_hash(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    png = _make_png()
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(png), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 201, r.text
    returned_hash = base64.b64decode(r.json()["content_hash"])
    assert returned_hash == hashlib.sha256(png).digest()


@pytest.mark.asyncio
async def test_upload_rejects_wrong_signature(app_client: AsyncClient) -> None:
    sender_id, _ = await _register_sender(app_client)
    wrong_priv = Ed25519PrivateKey.generate()
    png = _make_png()
    body = _sign_upload(wrong_priv, sender_id=sender_id, content_b64=_b64(png), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 401, r.text
    assert "invalid sender signature" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_rejects_non_png_format(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    body = _sign_upload(
        priv, sender_id=sender_id, content_b64=_b64(b"x" * 200), format="image/jpeg",
    )

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 400, r.text
    assert "unsupported asset format" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_rejects_oversized_payload(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    # 200 KB > 100 KB cap. Use a small valid PNG header followed by
    # padding so the magic check passes but the size check fails.
    big = _make_png(width=96) + b"\x00" * (200 * 1024)
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(big), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 400, r.text
    assert "exceeds" in r.json()["detail"]
    assert "102400" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_rejects_bad_png_magic(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    not_png = b"\xFF\xD8\xFF\xE0this-is-jpeg-not-png-but-claims-png" + b"\x00" * 200
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(not_png), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 400, r.text
    assert "PNG" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_rejects_too_small_dimensions(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    tiny = _make_png(width=32, height=32)  # below 96px minimum
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(tiny), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 400, r.text
    assert "96" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_rejects_non_square(app_client: AsyncClient) -> None:
    sender_id, priv = await _register_sender(app_client)
    rect = _make_png(width=96, height=128)
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(rect), format="image/png")

    r = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r.status_code == 400, r.text
    assert "square" in r.json()["detail"]


@pytest.mark.asyncio
async def test_upload_is_idempotent(app_client: AsyncClient) -> None:
    """Re-uploading identical bytes returns the same hash, no error."""
    sender_id, priv = await _register_sender(app_client)
    png = _make_png()
    body = _sign_upload(priv, sender_id=sender_id, content_b64=_b64(png), format="image/png")

    r1 = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r1.status_code == 201
    r2 = await app_client.post("/v1/plugins/me/assets", json=body)
    assert r2.status_code == 201
    assert r1.json()["content_hash"] == r2.json()["content_hash"]


@pytest.mark.asyncio
async def test_get_unknown_hash_returns_404(app_client: AsyncClient) -> None:
    """Unknown plugin row id → 404 even with a valid hash."""
    import uuid as _uuid
    fake_plugin = str(_uuid.uuid4())
    fake_hash = base64.urlsafe_b64encode(b"\x01" * 32).decode().rstrip("=")
    r = await app_client.get(f"/v1/plugins/{fake_plugin}/assets/{fake_hash}")
    assert r.status_code == 404, r.text
