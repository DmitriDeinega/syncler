"""Plugin lifecycle service — publish, fetch latest, revoke."""

from __future__ import annotations

import re
import uuid
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import and_, desc, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Plugin

# Semver-lite: MAJOR.MINOR.PATCH[-prerelease]. Reject anything weirder.
_SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z0-9.\-]+)?$")


class PluginError(Exception):
    """Base."""


class InvalidVersionError(PluginError):
    """Version string is not valid semver-lite."""


class VersionRegressionError(PluginError):
    """New version is not strictly greater than the latest published."""


class PluginNotFoundError(PluginError):
    """No plugin with that id."""


def _parse_version(version: str) -> tuple[int, int, int, str]:
    match = _SEMVER.match(version)
    if not match:
        raise InvalidVersionError(f"invalid version: {version}")
    major, minor, patch = (int(g) for g in match.groups()[:3])
    pre = match.group(0).partition("-")[2] or "~"  # "~" sorts after any prerelease
    return major, minor, patch, pre


async def publish_plugin(
    db: AsyncSession,
    *,
    plugin_id: str,
    sender_id: uuid.UUID,
    version: str,
    manifest_hash: bytes,
    bundle_hash: bytes,
    signature: bytes,
    signed_bundle_url: str,
    capabilities: list[str],
    endpoints: list[str],
) -> Plugin:
    new_key = _parse_version(version)

    # Confirm new version > all existing non-revoked versions
    existing = await db.execute(
        select(Plugin)
        .where(and_(Plugin.sender_id == sender_id, Plugin.id == _to_uuid(plugin_id)))
        .order_by(desc(Plugin.created_at))
    )
    rows = list(existing.scalars().all())
    for row in rows:
        if _parse_version(row.version) >= new_key:
            raise VersionRegressionError(
                f"new version {version} not greater than existing {row.version}",
            )

    plugin = Plugin(
        id=_to_uuid(plugin_id),
        sender_id=sender_id,
        version=version,
        manifest_hash=manifest_hash,
        bundle_hash=bundle_hash,
        signature=signature,
        signed_bundle_url=signed_bundle_url,
        capabilities=capabilities,
        endpoints=endpoints,
    )
    db.add(plugin)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise VersionRegressionError("(sender_id, version) already exists") from exc
    await db.refresh(plugin)
    return plugin


async def get_latest_for_plugin(db: AsyncSession, plugin_id: str) -> Plugin:
    pid = _to_uuid(plugin_id)
    result = await db.execute(
        select(Plugin)
        .where(and_(Plugin.id == pid, Plugin.revoked_at.is_(None)))
    )
    rows = list(result.scalars().all())
    if not rows:
        raise PluginNotFoundError(f"no active plugin {plugin_id}")
    rows.sort(key=lambda r: _parse_version(r.version), reverse=True)
    return rows[0]


async def revoke_plugin(db: AsyncSession, plugin_id: str) -> Plugin:
    pid = _to_uuid(plugin_id)
    result = await db.execute(select(Plugin).where(Plugin.id == pid))
    plugin = result.scalar_one_or_none()
    if plugin is None:
        raise PluginNotFoundError(f"no plugin {plugin_id}")
    if plugin.revoked_at is None:
        plugin.revoked_at = datetime.now(UTC)
        await db.commit()
        await db.refresh(plugin)
    return plugin


def _to_uuid(value: Any) -> uuid.UUID:
    if isinstance(value, uuid.UUID):
        return value
    return uuid.UUID(str(value))
