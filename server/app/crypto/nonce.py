"""Nonce generation.

Phase 7 — the durable nonce-replay registry lives in
`server/app/services/nonce_replay.py` (Postgres-backed,
multi-worker-safe). The previous in-memory `NonceRegistry`
implementation was removed because it lost state on worker restart
and didn't synchronize across multiple uvicorn workers.

This module now only owns the constants and the random-nonce
generator. Callers that need replay detection use
`services.nonce_replay.record_nonce_or_reject`.
"""

from __future__ import annotations

import secrets
from typing import Final

NONCE_BYTES: Final[int] = 12


def generate_nonce() -> bytes:
    """Generate a 96-bit AES-GCM nonce."""

    return secrets.token_bytes(NONCE_BYTES)
