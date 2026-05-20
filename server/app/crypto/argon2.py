"""Argon2id parameter metadata and auth-key hash verification."""

from __future__ import annotations

import secrets
from typing import Final

ARGON2_PARAMS_V1: Final[dict[str, int]] = {
    "m_cost": 19_456,
    "time_cost": 2,
    "parallelism": 1,
    "hash_len": 64,
}

_PARAMS_BY_VERSION: Final[dict[int, dict[str, int]]] = {
    1: ARGON2_PARAMS_V1,
}


def verify_auth_key_hash(stored_hash: bytes, submitted_hash: bytes) -> bool:
    """Compare a stored auth-key hash with the submitted hash in constant time."""

    return secrets.compare_digest(stored_hash, submitted_hash)


def params_for_version(version: int) -> dict[str, int]:
    """Return Argon2 parameters for a protocol version."""

    try:
        return dict(_PARAMS_BY_VERSION[version])
    except KeyError as exc:
        raise ValueError(f"unknown Argon2 params version: {version}") from exc

