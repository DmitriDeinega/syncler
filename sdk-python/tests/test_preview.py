"""Validate HostPreview contract enforcement.

Mirror of sdk-plugin/test/preview.test.ts; ensures the Python and TypeScript
SDKs reject the same shapes.
"""

from __future__ import annotations

import pytest

from syncler import HostPreviewValidationError, validate_host_preview
from syncler.preview import (
    SEARCH_TEXT_ENTRY_MAX_BYTES,
    SEARCH_TEXT_MAX_ENTRIES,
    SUBTITLE_MAX_BYTES,
    SUMMARY_MAX_BYTES,
    TITLE_MAX_BYTES,
    TOTAL_MAX_BYTES,
)


VALID_PREVIEW = {
    "title": "Lottery ticket entered",
    "subtitle": "Mega Millions — Ticket 8KQ-193",
    "summary": "5 lines for the May 22 drawing.",
    "searchText": ["mega millions", "ticket 8KQ-193"],
}


def test_accepts_valid_block() -> None:
    validate_host_preview(VALID_PREVIEW)  # no raise


def test_none_is_noop() -> None:
    validate_host_preview(None)  # no raise — host falls back


def test_non_dict_is_rejected() -> None:
    with pytest.raises(HostPreviewValidationError, match="must be a JSON object"):
        validate_host_preview("hi")


def test_title_required() -> None:
    with pytest.raises(HostPreviewValidationError, match="title is required"):
        validate_host_preview({"subtitle": "x"})
    with pytest.raises(HostPreviewValidationError, match="title is required"):
        validate_host_preview({"title": ""})
    with pytest.raises(HostPreviewValidationError, match="title is required"):
        validate_host_preview({"title": "   "})


def test_title_byte_cap() -> None:
    with pytest.raises(HostPreviewValidationError, match="title"):
        validate_host_preview({"title": "a" * (TITLE_MAX_BYTES + 1)})


def test_utf8_byte_cap_for_emoji() -> None:
    # 21 × 4-byte emoji = 84 bytes; over the 80-byte title cap. This is the
    # case a character-count cap would miss.
    with pytest.raises(HostPreviewValidationError, match="title"):
        validate_host_preview({"title": "🎰" * 21})


def test_subtitle_summary_caps() -> None:
    with pytest.raises(HostPreviewValidationError, match="subtitle"):
        validate_host_preview({
            "title": "ok",
            "subtitle": "a" * (SUBTITLE_MAX_BYTES + 1),
        })
    with pytest.raises(HostPreviewValidationError, match="summary"):
        validate_host_preview({
            "title": "ok",
            "summary": "a" * (SUMMARY_MAX_BYTES + 1),
        })


def test_search_text_must_be_list() -> None:
    with pytest.raises(HostPreviewValidationError, match="must be a list"):
        validate_host_preview({"title": "ok", "searchText": "a, comma, list"})


def test_search_text_entry_count() -> None:
    tokens = [f"t{i}" for i in range(SEARCH_TEXT_MAX_ENTRIES + 1)]
    with pytest.raises(HostPreviewValidationError, match="searchText has"):
        validate_host_preview({"title": "ok", "searchText": tokens})


def test_search_text_entry_byte_cap() -> None:
    fat = "a" * (SEARCH_TEXT_ENTRY_MAX_BYTES + 1)
    with pytest.raises(HostPreviewValidationError, match=r"searchText\[0\]"):
        validate_host_preview({"title": "ok", "searchText": [fat]})


def test_total_size_cap_against_unknown_field_bloat() -> None:
    # Per-field caps alone sum well under the 2KB total. The total cap
    # exists to catch unknown extension fields ("extra") that bypass the
    # per-field validators.
    block = {
        "title": "ok",
        "extra": "x" * (TOTAL_MAX_BYTES + 100),
    }
    with pytest.raises(HostPreviewValidationError, match=r"exceeds 2048"):
        validate_host_preview(block)
