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
# Components capped at 6 digits each so server + client Int parsers agree.
_SEMVER = re.compile(r"^(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-[A-Za-z0-9.\-]+)?$")


class PluginError(Exception):
    """Base."""


class InvalidVersionError(PluginError):
    """Version string is not valid semver-lite."""


class VersionRegressionError(PluginError):
    """New version is not strictly greater than the latest published."""


class DuplicateVersionError(PluginError):
    """(sender_id, plugin_identifier, version) already exists."""


class PluginNotFoundError(PluginError):
    """No plugin with that identifier."""


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
    sender_id: uuid.UUID,
    plugin_identifier: str,
    version: str,
    manifest_hash: bytes,
    bundle_hash: bytes,
    signature: bytes,
    signed_bundle_url: str,
    capabilities: list[str],
    endpoints: list[str],
) -> Plugin:
    new_key = _parse_version(version)

    # Reject if any existing non-revoked version is >= new version.
    existing = await db.execute(
        select(Plugin).where(
            and_(
                Plugin.sender_id == sender_id,
                Plugin.plugin_identifier == plugin_identifier,
            ),
        ),
    )
    for row in existing.scalars().all():
        if _parse_version(row.version) >= new_key:
            raise VersionRegressionError(
                f"new version {version} not greater than existing {row.version}",
            )

    plugin = Plugin(
        id=uuid.uuid4(),  # fresh row UUID per published version
        sender_id=sender_id,
        plugin_identifier=plugin_identifier,
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
        # Narrowed: only re-classify the UNIQUE-specific case as a 409
        # duplicate. Other IntegrityErrors (FK violations etc) escape
        # to the route handler as 500s so we see actual schema bugs.
        msg = str(exc.orig) if exc.orig else str(exc)
        if "uq_plugins_sender_identifier_version" in msg:
            raise DuplicateVersionError(
                f"plugin {plugin_identifier} version {version} already exists",
            ) from exc
        raise
    await db.refresh(plugin)
    return plugin


async def get_latest_for_plugin(
    db: AsyncSession, *, sender_id: uuid.UUID, plugin_identifier: str
) -> Plugin:
    result = await db.execute(
        select(Plugin).where(
            and_(
                Plugin.sender_id == sender_id,
                Plugin.plugin_identifier == plugin_identifier,
                Plugin.revoked_at.is_(None),
            ),
        ),
    )
    rows = list(result.scalars().all())
    if not rows:
        raise PluginNotFoundError(f"no active plugin {plugin_identifier} for sender")
    rows.sort(key=lambda r: _parse_version(r.version), reverse=True)
    return rows[0]


async def get_plugin_by_id(
    db: AsyncSession, *, plugin_row_id: uuid.UUID
) -> Plugin:
    """Returns the plugin row whether or not it's revoked. Used by the
    device's historical lookup path so an old message can re-resolve the
    exact bundle it was originally validated against, even after the
    sender published a newer (or a revoked) version under the same
    plugin_identifier.

    Raises PluginNotFoundError if the row doesn't exist at all.
    """
    result = await db.execute(select(Plugin).where(Plugin.id == plugin_row_id))
    plugin = result.scalar_one_or_none()
    if plugin is None:
        raise PluginNotFoundError(f"no plugin row {plugin_row_id}")
    return plugin


# M11.4: severity ordering for monotonic revocation-reason updates. Only an
# UP-traversal of this list is allowed once a row is revoked, so a sender
# (or a compromised sender's key) cannot later silently downgrade a
# `compromised` revoke to `superseded` and trick devices into running
# untrusted code.
_REASON_SEVERITY = {
    None: 0,
    "superseded": 1,
    "sender_disabled": 2,
    "unspecified": 2,
    "compromised": 3,
}


async def revoke_plugin_row(
    db: AsyncSession,
    plugin_row_id: uuid.UUID,
    reason: str | None = None,
) -> Plugin:
    """Mark a plugin row revoked, optionally with a classification reason.

    Idempotent on `revoked_at` — re-revoking does NOT push the timestamp
    forward. The reason field is monotonic: a higher-severity reason can
    overwrite a lower one (so `superseded` → `compromised` works when a
    vuln surfaces in an already-superseded version), but the reverse
    silently no-ops so a leaked sender key can't downgrade a security
    revoke into a harmless one.
    """
    result = await db.execute(select(Plugin).where(Plugin.id == plugin_row_id))
    plugin = result.scalar_one_or_none()
    if plugin is None:
        raise PluginNotFoundError(f"no plugin row {plugin_row_id}")
    changed = False
    if plugin.revoked_at is None:
        plugin.revoked_at = datetime.now(UTC)
        changed = True
    if reason is not None:
        current_sev = _REASON_SEVERITY.get(plugin.revocation_reason, 0)
        new_sev = _REASON_SEVERITY.get(reason, 0)
        if new_sev > current_sev:
            plugin.revocation_reason = reason
            changed = True
    if changed:
        await db.commit()
        await db.refresh(plugin)
    return plugin


def _to_uuid(value: Any) -> uuid.UUID:
    if isinstance(value, uuid.UUID):
        return value
    return uuid.UUID(str(value))
