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

# Mirror of schemas._JSONPATH_REGEX — duplicated here to keep this module
# importable in isolation. Phase 5d hardens the previous startswith("$")
# checks below to the same grammar the SDK and schemas layer use.
_JSONPATH_REGEX_SERVICE = re.compile(r"^\$(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")


class PluginError(Exception):
    """Base."""


class InvalidVersionError(PluginError):
    """Version string is not valid semver-lite."""


class VersionRegressionError(PluginError):
    """New version is not strictly greater than the latest published."""


class DuplicateVersionError(PluginError):
    """(sender_id, plugin_identifier, version) already exists."""


class InvalidTemplateError(PluginError):
    """Template configuration is malformed or invalid."""


class InvalidCapabilityError(PluginError):
    """Manifest declares a capability the host won't accept (legacy
    name, mutually exclusive pair, etc.). Phase 12 added."""


# Phase 12 (V2 #10): the canonical capability name set after the
# location split. Source of truth lives in the SDK enum but the
# server is the final gate — a malicious sender bypassing the SDK
# still hits this check.
_VALID_CAPABILITIES = frozenset({
    "network",
    "storage",
    "camera",
    "gallery",
    "file",
    "location.coarse",
    "location.fine",
    "background-exec",
})


def _validate_capabilities(capabilities: list[str]) -> None:
    """Phase 12: reject legacy `location` and mutually-exclusive
    `location.coarse` + `location.fine` declarations.

    See docs/plugin-capability-expansion.md "Server-side capability
    validation". The location pair is mutually exclusive so plugins
    can't smuggle the precise grant under the cover of the coarse
    declaration.
    """
    seen = set()
    for cap in capabilities:
        if cap == "location":
            raise InvalidCapabilityError(
                "legacy `location` capability is rejected — declare "
                "`location.coarse` or `location.fine` instead"
            )
        if cap not in _VALID_CAPABILITIES:
            raise InvalidCapabilityError(f"unknown capability: {cap}")
        if cap in seen:
            raise InvalidCapabilityError(f"duplicate capability: {cap}")
        seen.add(cap)
    if "location.coarse" in seen and "location.fine" in seen:
        raise InvalidCapabilityError(
            "location_double_declaration: manifest must not declare both "
            "`location.coarse` and `location.fine` — declare `location.fine` "
            "and check the returned precision field for OS-downgraded fixes"
        )


class PluginNotFoundError(PluginError):
    """No plugin with that identifier."""


def _validate_template(template: dict, declared_endpoints: list[str]) -> None:
    layout = template.get("layout")
    if layout != "standard_card":
        raise InvalidTemplateError(f"unknown or missing layout: {layout}")

    fields = template.get("fields", {})
    if not isinstance(fields, dict):
        raise InvalidTemplateError("fields must be a dictionary")

    if "title" not in fields:
        raise InvalidTemplateError("missing required field: title")

    for name, config in fields.items():
        if not isinstance(config, dict) or "path" not in config:
            raise InvalidTemplateError(f"malformed config for field {name}")
        path = config["path"]
        if not isinstance(path, str) or not _JSONPATH_REGEX_SERVICE.match(path):
            raise InvalidTemplateError(
                f"invalid JSONPath for field {name}: {path} "
                "(must match $.field(.subfield)*)",
            )

    actions = template.get("actions", [])
    if not isinstance(actions, list):
        raise InvalidTemplateError("actions must be a list")

    for action in actions:
        if not isinstance(action, dict):
            raise InvalidTemplateError("malformed action")
        aid = action.get("id")
        label = action.get("label")
        endpoint = action.get("endpoint")
        if not aid or not label or not endpoint:
            raise InvalidTemplateError("actions must have id, label, and endpoint")

        if endpoint not in declared_endpoints:
            if not any(endpoint.startswith(p.rstrip("*")) for p in declared_endpoints if p.endswith("*")):
                raise InvalidTemplateError(
                    f"action endpoint {endpoint} not covered by declaredEndpoints"
                )


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
    renderer: str = "script",
    template: dict | None = None,
    card_type: str = "event",
    card_key_path: str | None = None,
    entry_class: str | None = None,
    native_sdk_abi: int | None = None,
) -> Plugin:
    new_key = _parse_version(version)

    # Phase 12 (V2 #10): location capability split. `location.coarse`
    # and `location.fine` are mutually exclusive — plugins should
    # declare `.fine` and check the returned `precision` field to
    # detect OS-downgraded approximate fixes. Legacy `location` is
    # rejected; no V0.1 plugins use it. Spec:
    # docs/plugin-capability-expansion.md "Server-side capability validation".
    _validate_capabilities(capabilities)

    if renderer == "template":
        if not template:
            raise InvalidTemplateError("template object is required for renderer='template'")
        _validate_template(template, endpoints)
    elif renderer == "native_kotlin":
        # Phase 11: native plugins require entry_class + native_sdk_abi
        # (validator in schemas.py already enforces this, but the
        # service layer re-checks so anyone calling this function
        # directly catches the contract).
        if not entry_class:
            raise InvalidTemplateError(
                "entry_class is required for renderer='native_kotlin'"
            )
        if native_sdk_abi is None:
            raise InvalidTemplateError(
                "native_sdk_abi is required for renderer='native_kotlin'"
            )
    elif renderer != "script":
        raise InvalidTemplateError(f"unknown renderer type: {renderer}")

    if card_type == "live":
        if not card_key_path:
            raise InvalidTemplateError("card_key_path is required for card_type='live'")
        if not _JSONPATH_REGEX_SERVICE.match(card_key_path):
            raise InvalidTemplateError(
                f"invalid JSONPath for card_key_path: {card_key_path} "
                "(must match $.field(.subfield)*)",
            )
    elif card_type != "event":
        raise InvalidTemplateError(f"unknown card_type: {card_type}")

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
        renderer=renderer,
        template=template,
        card_type=card_type,
        card_key_path=card_key_path,
        entry_class=entry_class,
        native_sdk_abi=native_sdk_abi,
        capabilities=capabilities,
        endpoints=endpoints,
    )
    db.add(plugin)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
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
    result = await db.execute(select(Plugin).where(Plugin.id == plugin_row_id))
    plugin = result.scalar_one_or_none()
    if plugin is None:
        raise PluginNotFoundError(f"no plugin row {plugin_row_id}")
    return plugin


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
