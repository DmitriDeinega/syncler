"""V3 #17 — SSE event-ID shape tests.

The V0.1 in-process bus used a process-local monotonic int.
V3 #17 replaces that with ``{epoch_ms}-{worker_pid_hex}-{seq}``
so multi-worker IDs don't collide. Documented as diagnostic-
only (no Last-Event-ID replay contract on PUB/SUB backends).
"""

from __future__ import annotations

import re

import pytest

from app.services.events import _next_event_id


_ID_PATTERN = re.compile(r"^\d{10,}-[0-9a-f]+-\d+$")


@pytest.mark.asyncio
async def test_event_id_shape() -> None:
    eid = await _next_event_id()
    assert _ID_PATTERN.match(eid), f"unexpected id shape: {eid}"


@pytest.mark.asyncio
async def test_event_ids_are_unique_within_a_burst() -> None:
    ids = [await _next_event_id() for _ in range(100)]
    assert len(set(ids)) == len(ids), "expected unique ids in a burst"


@pytest.mark.asyncio
async def test_event_ids_are_monotonic() -> None:
    # Lex-sortable per spec — clients inspecting logs can
    # eyeball the order without parsing the timestamp.
    ids = [await _next_event_id() for _ in range(50)]
    assert ids == sorted(ids)
