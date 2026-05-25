"""Shared helpers for the Phase 8 ``key_generation`` gate.

Every endpoint that writes a ``key_generation``-tagged blob (docs/crypto-spec.md §10.5)
goes through:

1. ``SELECT users.* FROM users WHERE id = :u FOR UPDATE`` — serializes
   against any concurrent rotation.
2. Mixed-client gate — if the request claims Phase < 8 (or omits the
   ``X-Syncler-Client-Min-Phase`` header) and ``users.key_generation > 1``,
   return **426 Upgrade Required**.
3. CAS check on ``key_generation_observed`` (when the client is Phase 8+).
   Mismatch → **409 key_generation_mismatch**.

This module keeps that boilerplate out of every route handler.
"""

from __future__ import annotations

from fastapi import HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User

PHASE_8 = 8
CLIENT_MIN_PHASE_HEADER = "X-Syncler-Client-Min-Phase"


def parse_client_min_phase(request: Request) -> int:
    """Return the int phase the client claims, or 0 if missing / unparseable.

    Phase-8-aware clients send the literal ``8``. Older clients omit the
    header — we treat that as phase 0 for gating.
    """
    raw = request.headers.get(CLIENT_MIN_PHASE_HEADER)
    if raw is None:
        return 0
    try:
        return int(raw.strip())
    except ValueError:
        return 0


async def lock_user_and_gate(
    db: AsyncSession,
    *,
    request: Request,
    user_id,
    key_generation_observed: int | None,
    require_observed: bool = True,
) -> User:
    """Take the user-row FOR UPDATE lock then apply the §10.5 gates.

    Returns the locked, fresh ``User`` row so the caller can use the
    just-read ``key_generation`` value (and other locked fields) for
    follow-up writes inside the same transaction.

    ``require_observed`` controls whether a Phase-8+ client MUST send
    ``key_generation_observed``:

      - ``True`` (default) — for endpoints that write a
        ``key_generation``-tagged blob (PUT /v1/state, POST /v1/pairing
        /complete). A Phase-8+ client without the field is a protocol
        bug → 400.
      - ``False`` — for endpoints that don't write a new blob but still
        take the lock per §10.5 (POST /v1/pairing/{id}/revoke).
        Phase-8+ clients MAY pass the field (we then compare it to the
        locked row); if they omit it we still serve. This is the fix
        for the Gemini 104 YELLOW: revoke shouldn't 400 just because
        the client doesn't have an AAD to bind.

    Raises ``HTTPException`` with the spec-mandated status codes:

      - 426 if the client claims Phase < 8 and ``key_generation > 1``.
      - 409 ``key_generation_mismatch`` if a Phase-8+ client passes an
        observed value that doesn't match the locked row.
      - 400 if a Phase-8+ client omits ``key_generation_observed`` AND
        ``require_observed=True``.
    """
    # Use the ORM ``with_for_update()`` clause so SQLAlchemy emits the
    # right dialect-specific lock hint and applies the model's UUID type
    # converter (raw ``text("... :user_id")`` won't bind a UUID on SQLite).
    bind = db.get_bind()
    dialect = bind.dialect.name
    stmt = select(User).where(User.id == user_id)
    if dialect != "sqlite":
        stmt = stmt.with_for_update()
    row = await db.execute(stmt)
    user = row.scalar_one_or_none()
    if user is None:
        # Auth dependency should have caught this; degrade to 401.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
        )

    client_phase = parse_client_min_phase(request)
    if client_phase < PHASE_8 and user.key_generation > 1:
        raise HTTPException(
            status_code=status.HTTP_426_UPGRADE_REQUIRED,
            detail={
                "error": "account_upgraded_requires_newer_client",
                "minimum_supported_phase": PHASE_8,
            },
        )

    if client_phase >= PHASE_8:
        if key_generation_observed is None:
            if require_observed:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail={
                        "error": "key_generation_observed_required",
                        "minimum_supported_phase": PHASE_8,
                    },
                )
            # require_observed=False — revoke and similar non-blob-writing
            # endpoints don't need the AAD binding; serve.
        elif key_generation_observed != user.key_generation:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    "error": "key_generation_mismatch",
                    "current_key_generation": user.key_generation,
                    "client_action": "refetch_master_key_and_state",
                },
            )

    return user
