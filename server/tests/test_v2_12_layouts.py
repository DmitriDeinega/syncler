"""V2 #12 — server template-layout validation tests.

Each new layout (`compact_row`, `score_card`, `stat_grid`) must
accept its required fields and reject missing ones. Existing
standard_card behavior preserved.

Spec: docs/ROADMAP.md V2 #12.
"""

from __future__ import annotations

import pytest

from app.services.plugins import (
    InvalidTemplateError,
    _validate_template,
)


def _f(path: str) -> dict:
    return {"path": path}


def test_standard_card_still_accepts_minimal() -> None:
    _validate_template({
        "layout": "standard_card",
        "fields": {"title": _f("$.title")},
        "actions": [],
    }, declared_endpoints=[])


def test_compact_row_requires_leading() -> None:
    with pytest.raises(InvalidTemplateError) as exc:
        _validate_template({
            "layout": "compact_row",
            "fields": {"trailing": _f("$.t")},
            "actions": [],
        }, declared_endpoints=[])
    assert "leading" in str(exc.value)


def test_compact_row_accepts_full() -> None:
    _validate_template({
        "layout": "compact_row",
        "fields": {
            "leading": _f("$.l"),
            "trailing": _f("$.t"),
            "subtitle": _f("$.s"),
        },
        "actions": [],
    }, declared_endpoints=[])


def test_score_card_requires_score_and_label() -> None:
    with pytest.raises(InvalidTemplateError):
        _validate_template({
            "layout": "score_card",
            "fields": {"score": _f("$.score")},
            "actions": [],
        }, declared_endpoints=[])
    with pytest.raises(InvalidTemplateError):
        _validate_template({
            "layout": "score_card",
            "fields": {"label": _f("$.label")},
            "actions": [],
        }, declared_endpoints=[])


def test_score_card_accepts_full() -> None:
    _validate_template({
        "layout": "score_card",
        "fields": {
            "score": _f("$.score"),
            "label": _f("$.label"),
            "caption": _f("$.caption"),
        },
        "actions": [],
    }, declared_endpoints=[])


def test_stat_grid_requires_title() -> None:
    with pytest.raises(InvalidTemplateError):
        _validate_template({
            "layout": "stat_grid",
            "fields": {"stat1_label": _f("$.s1l")},
            "actions": [],
        }, declared_endpoints=[])


def test_stat_grid_accepts_full() -> None:
    _validate_template({
        "layout": "stat_grid",
        "fields": {
            "title": _f("$.title"),
            "stat1_label": _f("$.s1l"),
            "stat1_value": _f("$.s1v"),
            "stat2_label": _f("$.s2l"),
            "stat2_value": _f("$.s2v"),
        },
        "actions": [],
    }, declared_endpoints=[])


def test_unknown_layout_rejected() -> None:
    with pytest.raises(InvalidTemplateError) as exc:
        _validate_template({
            "layout": "fancy_grid",
            "fields": {},
            "actions": [],
        }, declared_endpoints=[])
    assert "unknown or missing layout" in str(exc.value)
