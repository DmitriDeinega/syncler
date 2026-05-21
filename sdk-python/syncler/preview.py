"""HostPreview — structured metadata the host renders natively as an inbox row.

The host reads this block from the decrypted payload before invoking the
plugin's ``render(payload)``. The plugin still receives the full payload for
the detail view; ``hostPreview`` is purely additive.

Wire contract:
- Required: ``title`` (<= 80 UTF-8 bytes).
- Optional: ``subtitle`` (<= 120), ``summary`` (<= 240),
  ``searchText`` (list of <= 16 entries, each <= 64 UTF-8 bytes).
- Serialized ``hostPreview`` must be <= 2048 UTF-8 bytes.

Missing or invalid ``hostPreview`` → the host renders a generic
"New message from {sender_name}" row.
"""

from __future__ import annotations

import json
from typing import Any, NotRequired, TypedDict


class HostPreview(TypedDict):
    title: str
    subtitle: NotRequired[str]
    summary: NotRequired[str]
    searchText: NotRequired[list[str]]


HOST_PREVIEW_KEY = "hostPreview"

TITLE_MAX_BYTES = 80
SUBTITLE_MAX_BYTES = 120
SUMMARY_MAX_BYTES = 240
SEARCH_TEXT_MAX_ENTRIES = 16
SEARCH_TEXT_ENTRY_MAX_BYTES = 64
TOTAL_MAX_BYTES = 2048


class HostPreviewValidationError(ValueError):
    """Raised when a payload's hostPreview block violates the contract."""


def validate_host_preview(value: Any) -> None:
    """Validate a hostPreview block in-place. Raises HostPreviewValidationError
    with a single explanatory message describing the first failure.

    No-op if ``value`` is None or not a dict (the host will fall back to the
    generic row). Senders who want explicit feedback should call this from
    their send path.
    """
    if value is None:
        return
    if not isinstance(value, dict):
        raise HostPreviewValidationError("hostPreview must be a JSON object")

    title = value.get("title")
    if not isinstance(title, str) or not title.strip():
        raise HostPreviewValidationError("hostPreview.title is required and must be a non-empty string")
    _check_bytes("title", title, TITLE_MAX_BYTES)

    if "subtitle" in value:
        if not isinstance(value["subtitle"], str):
            raise HostPreviewValidationError("hostPreview.subtitle must be a string")
        _check_bytes("subtitle", value["subtitle"], SUBTITLE_MAX_BYTES)

    if "summary" in value:
        if not isinstance(value["summary"], str):
            raise HostPreviewValidationError("hostPreview.summary must be a string")
        _check_bytes("summary", value["summary"], SUMMARY_MAX_BYTES)

    if "searchText" in value:
        tokens = value["searchText"]
        if not isinstance(tokens, list):
            raise HostPreviewValidationError("hostPreview.searchText must be a list of strings")
        if len(tokens) > SEARCH_TEXT_MAX_ENTRIES:
            raise HostPreviewValidationError(
                f"hostPreview.searchText has {len(tokens)} entries; max is {SEARCH_TEXT_MAX_ENTRIES}"
            )
        for i, token in enumerate(tokens):
            if not isinstance(token, str):
                raise HostPreviewValidationError(
                    f"hostPreview.searchText[{i}] must be a string"
                )
            _check_bytes(f"searchText[{i}]", token, SEARCH_TEXT_ENTRY_MAX_BYTES)

    # Total serialized size cap. Senders who want a richer preview should
    # trim summary or searchText rather than dropping fields.
    serialized = json.dumps(value, separators=(",", ":")).encode("utf-8")
    if len(serialized) > TOTAL_MAX_BYTES:
        raise HostPreviewValidationError(
            f"hostPreview serialized size {len(serialized)} bytes exceeds {TOTAL_MAX_BYTES} byte cap"
        )


def _check_bytes(field_name: str, text: str, max_bytes: int) -> None:
    size = len(text.encode("utf-8"))
    if size > max_bytes:
        raise HostPreviewValidationError(
            f"hostPreview.{field_name} is {size} UTF-8 bytes; max is {max_bytes}"
        )
