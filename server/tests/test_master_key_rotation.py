"""Phase 8 — master-key rotation tests.

Covers the three rotation modes (§10.1), the §10.8 14-step transaction
contract (lock → challenge → proof → success-rate → key-gen → pairing-set
→ pairing CAS → state CAS → user-row writes → audit → session revoke →
challenge consume → COMMIT), and the §10.5 mixed-client + key_generation
gates on PUT /v1/state and POST /v1/pairing/complete.

Tests focus on observable invariants rather than literal §10.13 AAD bytes
(those would need a full Argon2id + AES-GCM derivation harness — see
docs/crypto-spec.md for the canonical computed values).
"""

from __future__ import annotations

import base64
import hashlib
import json
import uuid

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    Device,
    EncryptedUserState,
    MasterKeyRotationAudit,
    Pairing,
    RotationChallenge,
    User,
)

PHASE_8_HEADER = {"X-Syncler-Client-Min-Phase": "8"}


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


async def _signup_login_enroll(
    client: AsyncClient,
    *,
    email: str = "rot@example.com",
    auth_key: bytes = b"old-auth-key-32-bytes-padding!!1",
) -> tuple[str, bytes]:
    """Returns (session_token, auth_key). ``auth_key`` is the 32-byte secret;
    ``SHA-256(auth_key)`` is what the server stores as auth_key_hash."""
    assert len(auth_key) == 32, f"auth_key must be 32 bytes, got {len(auth_key)}"
    stored_hash = hashlib.sha256(auth_key).digest()
    auth_key_hash_b64 = _b64(stored_hash)

    await client.post(
        "/v1/auth/signup",
        json={
            "email": email,
            "auth_key_hash": auth_key_hash_b64,
            "encrypted_master_key": _b64(b"old-wrapped-mk" + b"\x00" * 50),
            "auth_salt": _b64(b"old-salt-16byte!"),
            "argon2_params_version": 1,
        },
    )
    login = await client.post(
        "/v1/auth/login",
        json={"email": email, "auth_key_hash": auth_key_hash_b64},
    )
    assert login.status_code == 200, login.text
    bootstrap = login.json()["session_token"]

    enroll = await client.post(
        "/v1/auth/devices/enroll",
        headers={"Authorization": f"Bearer {bootstrap}"},
        json={"public_key": _b64(b"d" * 32)},
    )
    assert enroll.status_code == 201, enroll.text
    return enroll.json()["session_token"], auth_key


async def _seed_initial_state(client: AsyncClient, session: str) -> None:
    """Put an initial encrypted_user_state row so root_* rotations have
    something to CAS."""
    r = await client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 0, "new_encrypted_blob": _b64(b"initial-state-blob")},
    )
    assert r.status_code == 200, r.text


async def _pair_with_sender(
    client: AsyncClient, session: str, *, sender_name: str
) -> uuid.UUID:
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    register = await client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": sender_name, "contact": "x@y.z"},
    )
    sender_id = uuid.UUID(register.json()["sender_id"])

    body = {"sender_id": str(sender_id), "ttl_seconds": 300, "metadata": {}}
    sig = _b64(
        private_key.sign(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()),
    )
    initiate = await client.post(
        "/v1/pairing/initiate",
        json={**body, "signature": sig},
    )
    pairing_token = initiate.json()["pairing_token"]

    complete = await client.post(
        "/v1/pairing/complete",
        headers={"Authorization": f"Bearer {session}"},
        json={
            "pairing_token": pairing_token,
            "encrypted_initial_state": _b64(b"opaque-initial-pairing-state"),
        },
    )
    assert complete.status_code == 201, complete.text
    return uuid.UUID(complete.json()["pairing_id"])


async def _get_challenge(client: AsyncClient, session: str) -> str:
    r = await client.post(
        "/v1/account/rotate-master-key/challenge",
        headers={"Authorization": f"Bearer {session}"},
    )
    assert r.status_code == 200, r.text
    return r.json()["rotation_challenge"]


# ---------------------------------------------------------------------------
# §10.6 challenge endpoint
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_challenge_returns_32_byte_single_use_token(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, _ = await _signup_login_enroll(app_client)

    response = await app_client.post(
        "/v1/account/rotate-master-key/challenge",
        headers={"Authorization": f"Bearer {session}"},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    challenge_b = base64.b64decode(body["rotation_challenge"])
    assert len(challenge_b) == 32

    # Row persisted, bound to the calling device.
    rows = (await db_session.execute(select(RotationChallenge))).scalars().all()
    assert len(rows) == 1
    assert rows[0].challenge == challenge_b


@pytest.mark.asyncio
async def test_challenge_requires_auth(app_client: AsyncClient) -> None:
    response = await app_client.post("/v1/account/rotate-master-key/challenge")
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# password_rewrap (master key UNCHANGED)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_password_rewrap_happy_path(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"rewrapped-mk-blob" + b"\x01" * 40),
            "new_auth_salt": _b64(b"new-salt-16byte!"),
            "new_auth_key_proof": _b64(b"new-auth-key-32-bytes-padding!!1"),
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    # key_generation UNCHANGED.
    assert body["key_generation"] == 1
    assert body["encrypted_user_state"] is None
    assert body["pairings"] == []

    # users row: new auth_key_hash + new salt + new encrypted_master_key,
    # generation still 1.
    user = (await db_session.execute(select(User))).scalar_one()
    assert user.key_generation == 1
    assert user.auth_salt == b"new-salt-16byte!"
    assert user.auth_key_hash == hashlib.sha256(
        b"new-auth-key-32-bytes-padding!!1"
    ).digest()
    assert user.encrypted_master_key.startswith(b"rewrapped-mk-blob")

    # Audit row written.
    audit = (await db_session.execute(select(MasterKeyRotationAudit))).scalar_one()
    assert audit.reason == "password_rewrap"
    assert audit.old_generation == 1
    assert audit.new_generation == 1
    assert audit.paired_count == 0


@pytest.mark.asyncio
async def test_password_rewrap_rejects_state_and_pairings(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"rewrapped-mk-blob" + b"\x00" * 40),
            "new_auth_salt": _b64(b"new-salt-16byte!"),
            "new_auth_key_proof": _b64(b"new-auth-key-32-bytes-padding!!1"),
            # FORBIDDEN for password_rewrap:
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"blob" + b"\x00" * 20),
                "state_version_observed": 1,
            },
        },
    )
    assert response.status_code == 400


# ---------------------------------------------------------------------------
# root_hygiene_rotation (new MK, same password)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_root_hygiene_rotation_happy_path(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pairing_id = await _pair_with_sender(app_client, session, sender_name="Senderoo")

    # Read current versions so we can pass observed correctly.
    state_row = (await db_session.execute(select(EncryptedUserState))).scalar_one()
    pairing_row = (await db_session.execute(select(Pairing))).scalar_one()
    assert state_row.state_version == 1
    assert pairing_row.state_version == 1
    assert pairing_row.key_generation == 1

    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new-wrapped-mk" + b"\x02" * 50),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"re-encrypted-state" + b"\x00" * 20),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pairing_id),
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(
                        b"re-encrypted-pairing" + b"\x00" * 20,
                    ),
                },
            ],
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["key_generation"] == 2
    assert body["encrypted_user_state"]["state_version"] == 2
    assert body["encrypted_user_state"]["key_generation"] == 2
    assert len(body["pairings"]) == 1
    assert body["pairings"][0]["state_version"] == 2
    assert body["pairings"][0]["key_generation"] == 2

    # auth_key_hash + auth_salt UNCHANGED.
    user = (await db_session.execute(select(User).where(User.id == pairing_row.user_id))).scalar_one()
    assert user.key_generation == 2
    assert user.auth_key_hash == hashlib.sha256(auth_key).digest()
    assert user.auth_salt == b"old-salt-16byte!"

    # All sessions (devices) STILL active for hygiene.
    devices = (await db_session.execute(select(Device))).scalars().all()
    assert all(d.revoked_at is None for d in devices)


# ---------------------------------------------------------------------------
# root_compromise_rotation — revokes ALL sessions
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_root_compromise_rotation_revokes_all_devices(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_compromise_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new-mk" + b"\x03" * 60),
            "new_auth_salt": _b64(b"new-salt-16byte!"),
            "new_auth_key_proof": _b64(b"shiny-new-auth-key-32-bytes!padd"),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"new-state" + b"\x00" * 20),
                "state_version_observed": 1,
            },
            "pairings": [],
        },
    )
    assert response.status_code == 200, response.text

    # Every device flipped to revoked.
    devices = (await db_session.execute(select(Device))).scalars().all()
    assert len(devices) >= 1
    assert all(d.revoked_at is not None for d in devices)

    # The post-rotation session token should now 401.
    me = await app_client.get(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
    )
    assert me.status_code == 401


# ---------------------------------------------------------------------------
# §10.8 step 4 — invalid proof + failed-counter
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_wrong_proof_returns_401_and_persists_counter(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, _ = await _signup_login_enroll(app_client)
    challenge = await _get_challenge(app_client, session)

    bad_proof = b"\x00" * 32
    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(bad_proof),
            "new_encrypted_master_key": _b64(b"never-applied" + b"\x00" * 30),
            "new_auth_salt": _b64(b"never-applied!!1"),
            "new_auth_key_proof": _b64(b"never-applied-auth-key-32bytes!1"),
        },
    )
    assert response.status_code == 401, response.text

    # Challenge NOT consumed — protocol requirement (step 4 mismatch branch).
    rows = (await db_session.execute(select(RotationChallenge))).scalars().all()
    assert len(rows) == 1

    # No user mutation, no audit row.
    user = (await db_session.execute(select(User))).scalar_one()
    assert user.auth_salt == b"old-salt-16byte!"
    audit = (await db_session.execute(select(MasterKeyRotationAudit))).scalars().all()
    assert audit == []


@pytest.mark.asyncio
async def test_wrong_proof_rate_limits_after_10(app_client: AsyncClient) -> None:
    session, _ = await _signup_login_enroll(app_client)
    bad_proof = b"\x00" * 32

    last_status: int | None = None
    last_text: str | None = None
    for _ in range(11):
        challenge = await _get_challenge(app_client, session)
        r = await app_client.post(
            "/v1/account/rotate-master-key",
            headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
            json={
                "reason": "password_rewrap",
                "key_generation_observed": 1,
                "rotation_challenge": challenge,
                "current_password_proof": _b64(bad_proof),
                "new_encrypted_master_key": _b64(b"never" + b"\x00" * 40),
                "new_auth_salt": _b64(b"x" * 16),
                "new_auth_key_proof": _b64(b"y" * 32),
            },
        )
        last_status = r.status_code
        last_text = r.text
    # The 11th attempt MUST be 429.
    assert last_status == 429, last_text


# ---------------------------------------------------------------------------
# §10.8 step 5 — success rate-limit (3 per 24h)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_fourth_rotation_in_24h_is_429(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)

    async def _do_rewrap(salt: bytes) -> int:
        challenge = await _get_challenge(app_client, session)
        r = await app_client.post(
            "/v1/account/rotate-master-key",
            headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
            json={
                "reason": "password_rewrap",
                "key_generation_observed": 1,
                "rotation_challenge": challenge,
                "current_password_proof": _b64(auth_key),
                "new_encrypted_master_key": _b64(b"rewrap" + b"\x00" * 40),
                "new_auth_salt": _b64(salt),
                "new_auth_key_proof": _b64(auth_key),
            },
        )
        return r.status_code

    assert await _do_rewrap(b"salt-aaaaaaaaaaa") == 200
    assert await _do_rewrap(b"salt-bbbbbbbbbbb") == 200
    assert await _do_rewrap(b"salt-cccccccccccc"[:16]) == 200
    fourth = await _do_rewrap(b"salt-ddddddddddd")
    assert fourth == 429


# ---------------------------------------------------------------------------
# §10.8 step 6 — key_generation_observed mismatch
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_wrong_key_generation_observed_409(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 99,  # WRONG
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"r" * 64),
            "new_auth_salt": _b64(b"s" * 16),
            "new_auth_key_proof": _b64(auth_key),
        },
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["error"] == "key_generation_mismatch"
    assert detail["current_key_generation"] == 1


# ---------------------------------------------------------------------------
# §10.8 step 7 — pairing-set checks (duplicate / missing / extra)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_duplicate_pairing_id_returns_409(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pairing_id = await _pair_with_sender(app_client, session, sender_name="A")
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pairing_id),
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"a" * 32),
                },
                {
                    "pairing_id": str(pairing_id),  # DUPLICATE
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"b" * 32),
                },
            ],
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "pairing_set_changed"


@pytest.mark.asyncio
async def test_missing_pairing_returns_409(app_client: AsyncClient) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pid_a = await _pair_with_sender(app_client, session, sender_name="A")
    await _pair_with_sender(app_client, session, sender_name="B")
    challenge = await _get_challenge(app_client, session)

    # Request only includes A but B is also active.
    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pid_a),
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"a" * 32),
                },
            ],
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "pairing_set_changed"


@pytest.mark.asyncio
async def test_extra_pairing_id_returns_409(app_client: AsyncClient) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pid_a = await _pair_with_sender(app_client, session, sender_name="A")
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pid_a),
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"a" * 32),
                },
                {
                    "pairing_id": str(uuid.uuid4()),  # not in canonical set
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"b" * 32),
                },
            ],
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "pairing_set_changed"


# ---------------------------------------------------------------------------
# §10.8 step 8/9 — CAS mismatch surfaces 409
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stale_pairing_state_version_returns_409(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pid = await _pair_with_sender(app_client, session, sender_name="A")
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pid),
                    "state_version_observed": 99,  # STALE
                    "new_encrypted_state": _b64(b"a" * 32),
                },
            ],
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "pairing_state_changed"


@pytest.mark.asyncio
async def test_stale_user_state_version_returns_409(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    challenge = await _get_challenge(app_client, session)

    response = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 99,  # STALE
            },
            "pairings": [],
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"]["error"] == "state_version_mismatch"


# ---------------------------------------------------------------------------
# §10.6 challenge consumption (only on success; never on bad proof)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_successful_rotation_consumes_challenge(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    challenge_b64 = await _get_challenge(app_client, session)

    r = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 1,
            "rotation_challenge": challenge_b64,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_auth_salt": _b64(b"s" * 16),
            "new_auth_key_proof": _b64(auth_key),
        },
    )
    assert r.status_code == 200

    # rotation_challenges table now empty for this user.
    rows = (await db_session.execute(select(RotationChallenge))).scalars().all()
    assert rows == []


@pytest.mark.asyncio
async def test_invalid_challenge_returns_401(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    bogus = _b64(b"\xff" * 32)

    r = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "password_rewrap",
            "key_generation_observed": 1,
            "rotation_challenge": bogus,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_auth_salt": _b64(b"s" * 16),
            "new_auth_key_proof": _b64(auth_key),
        },
    )
    assert r.status_code == 401
    assert r.json()["detail"]["error"] == "rotation_challenge_invalid"


# ---------------------------------------------------------------------------
# §10.5 mixed-client gate on PUT /v1/state and POST /v1/pairing/complete
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_put_state_426_when_pre_phase8_after_rotation(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    challenge = await _get_challenge(app_client, session)

    r = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"new" + b"\x00" * 60),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [],
        },
    )
    assert r.status_code == 200, r.text

    # Pre-Phase-8 client: no X-Syncler-Client-Min-Phase header.
    legacy_put = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}"},
        json={"expected_state_version": 2, "new_encrypted_blob": _b64(b"x" * 32)},
    )
    assert legacy_put.status_code == 426
    body = legacy_put.json()["detail"]
    assert body["error"] == "account_upgraded_requires_newer_client"
    assert body["minimum_supported_phase"] == 8


@pytest.mark.asyncio
async def test_put_state_phase8_requires_key_generation_observed(
    app_client: AsyncClient,
) -> None:
    session, _ = await _signup_login_enroll(app_client)
    # Phase-8 header but no key_generation_observed → 400.
    r = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={"expected_state_version": 0, "new_encrypted_blob": _b64(b"first" * 8)},
    )
    assert r.status_code == 400
    assert r.json()["detail"]["error"] == "key_generation_observed_required"


@pytest.mark.asyncio
async def test_put_state_phase8_409_on_stale_key_generation(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    challenge = await _get_challenge(app_client, session)
    # Rotate → key_generation 1 → 2.
    rot = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"n" * 64),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [],
        },
    )
    assert rot.status_code == 200

    # Client still thinks key_generation == 1 → 409.
    r = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "expected_state_version": 2,
            "new_encrypted_blob": _b64(b"x" * 32),
            "key_generation_observed": 1,
        },
    )
    assert r.status_code == 409
    detail = r.json()["detail"]
    assert detail["error"] == "key_generation_mismatch"
    assert detail["current_key_generation"] == 2


# ---------------------------------------------------------------------------
# Codex 104 RED — pairing.complete after a rotation must stamp the new row
# with the LOCKED user.key_generation (§10.4 AAD lockstep).
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_post_rotation_pairing_complete_stamps_new_generation(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    challenge = await _get_challenge(app_client, session)

    # Rotate: 1 → 2.
    rot = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"n" * 64),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [],
        },
    )
    assert rot.status_code == 200, rot.text
    assert rot.json()["key_generation"] == 2

    # Now /pairing/complete a fresh sender as a Phase-8 client with
    # key_generation_observed=2. The row MUST be stamped with
    # key_generation=2, not the server_default 1.
    private_key = Ed25519PrivateKey.generate()
    public_key = private_key.public_key().public_bytes_raw()
    register = await app_client.post(
        "/v1/senders/register",
        json={"public_key": _b64(public_key), "name": "Sndr", "contact": "x@y.z"},
    )
    sender_id = uuid.UUID(register.json()["sender_id"])
    body = {"sender_id": str(sender_id), "ttl_seconds": 300, "metadata": {}}
    sig = _b64(
        private_key.sign(
            json.dumps(body, sort_keys=True, separators=(",", ":")).encode(),
        ),
    )
    initiate = await app_client.post(
        "/v1/pairing/initiate",
        json={**body, "signature": sig},
    )
    complete = await app_client.post(
        "/v1/pairing/complete",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "pairing_token": initiate.json()["pairing_token"],
            "encrypted_initial_state": _b64(b"opaque-blob-after-rotation-aad!"),
            "key_generation_observed": 2,
        },
    )
    assert complete.status_code == 201, complete.text

    pairing_id = uuid.UUID(complete.json()["pairing_id"])
    row = (
        await db_session.execute(
            select(Pairing).where(Pairing.id == pairing_id),
        )
    ).scalar_one()
    assert row.key_generation == 2, (
        f"new pairing must be stamped with key_generation 2, got {row.key_generation}"
    )


# ---------------------------------------------------------------------------
# Gemini 104 YELLOW — revoke must NOT require key_generation_observed.
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_phase8_revoke_works_without_key_generation_observed(
    app_client: AsyncClient,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)
    pid = await _pair_with_sender(app_client, session, sender_name="A")
    challenge = await _get_challenge(app_client, session)

    # Rotate so users.key_generation > 1.
    rot = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"n" * 64),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [
                {
                    "pairing_id": str(pid),
                    "state_version_observed": 1,
                    "new_encrypted_state": _b64(b"re-encrypted" + b"\x00" * 20),
                },
            ],
        },
    )
    assert rot.status_code == 200, rot.text

    # Phase-8 client revokes without passing key_generation_observed.
    # Must succeed — revoke doesn't write a key_generation-tagged blob.
    r = await app_client.post(
        f"/v1/pairing/{pid}/revoke",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
    )
    assert r.status_code == 204, r.text


# ---------------------------------------------------------------------------
# Codex 105 RED — PUT /v1/state must stamp encrypted_user_state.key_generation
# with the locked user.key_generation on every CAS write (§10.4).
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_put_state_stamps_row_with_locked_key_generation(
    app_client: AsyncClient, db_session: AsyncSession,
) -> None:
    session, auth_key = await _signup_login_enroll(app_client)
    await _seed_initial_state(app_client, session)

    # Sanity: pre-rotation, row carries key_generation=1.
    pre = (await db_session.execute(select(EncryptedUserState))).scalar_one()
    assert pre.key_generation == 1

    # Rotate 1 → 2 (root_hygiene); rotation step 9 stamps the row to 2.
    challenge = await _get_challenge(app_client, session)
    rot = await app_client.post(
        "/v1/account/rotate-master-key",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "reason": "root_hygiene_rotation",
            "key_generation_observed": 1,
            "rotation_challenge": challenge,
            "current_password_proof": _b64(auth_key),
            "new_encrypted_master_key": _b64(b"n" * 64),
            "new_encrypted_user_state": {
                "encrypted_blob": _b64(b"s" * 32),
                "state_version_observed": 1,
            },
            "pairings": [],
        },
    )
    assert rot.status_code == 200, rot.text

    # Now Phase-8 PUT a new state with key_generation_observed=2.
    put = await app_client.put(
        "/v1/state",
        headers={"Authorization": f"Bearer {session}", **PHASE_8_HEADER},
        json={
            "expected_state_version": 2,
            "new_encrypted_blob": _b64(b"post-rotation-state-blob" + b"\x00" * 8),
            "key_generation_observed": 2,
        },
    )
    assert put.status_code == 200, put.text

    # Row must report key_generation=2 (rotation set it; PUT must keep
    # it stamped — restating-on-write is the §10.4 lockstep guarantee).
    post = (await db_session.execute(select(EncryptedUserState))).scalar_one()
    assert post.key_generation == 2, (
        f"PUT /v1/state must stamp the row's key_generation with the "
        f"locked users.key_generation; got {post.key_generation}"
    )
    # And the response surfaces the same value.
    assert put.json()["key_generation"] == 2
