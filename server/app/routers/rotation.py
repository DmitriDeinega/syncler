"""Master-key rotation endpoints (docs/crypto-spec.md §10.6).

Two routes, both behind ``current_auth_context`` (device-bound JWT):

  - ``POST /v1/account/rotate-master-key/challenge`` — issues a fresh
    32-byte single-use challenge bound to the calling device.
  - ``POST /v1/account/rotate-master-key`` — consumes the challenge and
    runs the 14-step rotation transaction.

All non-trivial logic lives in ``app/services/rotation.py``; this layer
translates ``RotationError`` subclasses into the HTTP shapes the spec
requires.
"""

from __future__ import annotations

import base64

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import db as db_module
from app.auth import AuthContext, current_auth_context
from app.db import get_db, init_engine
from app.schemas import (
    RotateMasterKeyRequest,
    RotateMasterKeyResponse,
    RotationChallengeResponse,
    RotationPairingResult,
    RotationUserStateResult,
    decode_base64,
)
from app.services.rotation import (
    RotationChallengeInvalidError,
    RotationContext,
    RotationKeyGenerationMismatchError,
    RotationPairingSetChangedError,
    RotationPairingStateChangedError,
    RotationProofMismatchError,
    RotationSuccessRateLimitedError,
    RotationUserStateChangedError,
    issue_challenge,
    perform_rotation,
)

router = APIRouter(tags=["account"])


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _decode_b64_field(value: str, *, field_name: str, exact: int | None = None,
                     minimum: int | None = None) -> bytes:
    return decode_base64(value, field_name=field_name, exact=exact, minimum=minimum)


def _client_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    if request.client is None:
        return None
    return request.client.host


@router.post(
    "/rotate-master-key/challenge",
    response_model=RotationChallengeResponse,
    status_code=status.HTTP_200_OK,
)
async def rotate_master_key_challenge(
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> RotationChallengeResponse:
    row = await issue_challenge(db, user_id=ctx.user.id, session_id=ctx.device.id)
    return RotationChallengeResponse(
        rotation_challenge=_b64(row.challenge),
        expires_at=row.expires_at,
    )


@router.post(
    "/rotate-master-key",
    response_model=RotateMasterKeyResponse,
    status_code=status.HTTP_200_OK,
)
async def rotate_master_key(
    payload: RotateMasterKeyRequest,
    request: Request,
    ctx: AuthContext = Depends(current_auth_context),
    db: AsyncSession = Depends(get_db),
) -> RotateMasterKeyResponse:
    # Materialize the session factory the service uses for the SEPARATE
    # failed-proof counter increment (§10.8 step 4 mismatch branch).
    init_engine()
    session_factory = db_module._session_factory
    if session_factory is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="database not initialized",
        )

    # Decode the binary fields once at the boundary; the service expects bytes.
    challenge_bytes = _decode_b64_field(
        payload.rotation_challenge, field_name="rotation_challenge", exact=32,
    )
    proof_bytes = _decode_b64_field(
        payload.current_password_proof, field_name="current_password_proof", exact=32,
    )
    new_encrypted_mk = _decode_b64_field(
        payload.new_encrypted_master_key,
        field_name="new_encrypted_master_key",
        minimum=32,
    )
    new_salt: bytes | None = None
    new_auth_proof: bytes | None = None
    if payload.new_auth_salt is not None:
        new_salt = _decode_b64_field(payload.new_auth_salt, field_name="new_auth_salt", exact=16)
    if payload.new_auth_key_proof is not None:
        new_auth_proof = _decode_b64_field(
            payload.new_auth_key_proof, field_name="new_auth_key_proof", exact=32,
        )

    new_user_state: tuple[int, bytes] | None = None
    if payload.new_encrypted_user_state is not None:
        new_user_state = (
            payload.new_encrypted_user_state.state_version_observed,
            _decode_b64_field(
                payload.new_encrypted_user_state.encrypted_blob,
                field_name="encrypted_blob",
                minimum=16,
            ),
        )

    raw_pairing_ids = []
    pairings_decoded: list = []
    if payload.pairings is not None:
        for entry in payload.pairings:
            raw_pairing_ids.append(entry.pairing_id)
            pairings_decoded.append(
                (
                    entry.pairing_id,
                    entry.state_version_observed,
                    _decode_b64_field(
                        entry.new_encrypted_state,
                        field_name="new_encrypted_state",
                        minimum=16,
                    ),
                ),
            )

    rotation_ctx = RotationContext(
        user_id=ctx.user.id,
        session_id=ctx.device.id,
        reason=payload.reason,
        key_generation_observed=payload.key_generation_observed,
        rotation_challenge=challenge_bytes,
        current_password_proof=proof_bytes,
        new_encrypted_master_key=new_encrypted_mk,
        new_auth_salt=new_salt,
        new_auth_key_proof=new_auth_proof,
        new_encrypted_user_state=new_user_state,
        pairings=pairings_decoded,
        raw_pairing_ids=raw_pairing_ids,
        initiating_device_id=ctx.device.id,
        ip=_client_ip(request),
        user_agent=request.headers.get("user-agent"),
    )

    try:
        result = await perform_rotation(db, session_factory, ctx=rotation_ctx)
    except RotationChallengeInvalidError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "rotation_challenge_invalid"},
        ) from exc
    except RotationProofMismatchError as exc:
        if exc.rate_limited:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail={"error": "rotate_proof_fail_rate_limited"},
                headers={"Retry-After": str(exc.retry_after_seconds or 60)},
            ) from exc
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "invalid_password_proof"},
        ) from exc
    except RotationSuccessRateLimitedError as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={"error": "rotation_success_rate_limited"},
            headers={"Retry-After": str(exc.retry_after_seconds)},
        ) from exc
    except RotationKeyGenerationMismatchError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "error": "key_generation_mismatch",
                "current_key_generation": exc.current_key_generation,
                "client_action": "refetch_master_key_and_state",
            },
        ) from exc
    except RotationPairingSetChangedError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "error": "pairing_set_changed",
                "current_pairing_ids": [str(p) for p in exc.current_pairing_ids],
            },
        ) from exc
    except RotationPairingStateChangedError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "error": "pairing_state_changed",
                "mismatches": [
                    {"pairing_id": str(pid), "current_state_version": v}
                    for pid, v in exc.mismatches
                ],
            },
        ) from exc
    except RotationUserStateChangedError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "error": "state_version_mismatch",
                "current_state_version": exc.current_state_version,
            },
        ) from exc

    return RotateMasterKeyResponse(
        key_generation=result.new_key_generation,
        encrypted_user_state=(
            RotationUserStateResult(
                state_version=result.user_state.new_state_version,
                key_generation=result.user_state.new_key_generation,
            )
            if result.user_state is not None
            else None
        ),
        pairings=[
            RotationPairingResult(
                pairing_id=p.pairing_id,
                state_version=p.new_state_version,
                key_generation=p.new_key_generation,
            )
            for p in result.pairings
        ],
    )
