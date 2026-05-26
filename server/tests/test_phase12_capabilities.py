"""Phase 12 — server-side capability validation tests.

Exercises the location-split + both-rejection rule landed in
[`server/app/services/plugins.py`](../app/services/plugins.py).
Spec: docs/plugin-capability-expansion.md "Server-side capability
validation".

Run service-layer validation directly (no DB, no signature) — the
router wraps these as 400s when raised from publish_plugin.
"""

from __future__ import annotations

import pytest

from app.services.plugins import (
    InvalidCapabilityError,
    _validate_capabilities,
)


def test_accepts_canonical_capabilities() -> None:
    _validate_capabilities([
        "network",
        "storage",
        "camera",
        "gallery",
        "file",
        "location.fine",
        "background-exec",
    ])


def test_accepts_coarse_only() -> None:
    _validate_capabilities(["location.coarse"])


def test_accepts_fine_only() -> None:
    _validate_capabilities(["location.fine"])


def test_rejects_legacy_location() -> None:
    with pytest.raises(InvalidCapabilityError) as exc:
        _validate_capabilities(["location"])
    assert "legacy" in str(exc.value).lower()


def test_rejects_unknown_capability() -> None:
    with pytest.raises(InvalidCapabilityError) as exc:
        _validate_capabilities(["audio"])
    assert "unknown capability" in str(exc.value).lower()


def test_rejects_duplicate_capability() -> None:
    with pytest.raises(InvalidCapabilityError) as exc:
        _validate_capabilities(["camera", "camera"])
    assert "duplicate" in str(exc.value).lower()


def test_rejects_location_double_declaration() -> None:
    """Both-location declaration forces single-granularity choice.

    Plugins wanting fine-with-fallback should declare location.fine
    and inspect the returned precision field for OS-downgraded
    approximate fixes.
    """
    with pytest.raises(InvalidCapabilityError) as exc:
        _validate_capabilities(["location.coarse", "location.fine"])
    assert "location_double_declaration" in str(exc.value)


def test_accepts_empty_capabilities() -> None:
    _validate_capabilities([])
