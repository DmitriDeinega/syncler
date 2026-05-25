=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 104 — Phase 8b implementation (server-side master-key rotation)

Phase 8a spec landed at `0efd40e` (dual-GREEN after six review cycles
98-103). This consultation reviews the server-side implementation
against `docs/crypto-spec.md §10` (the spec you both already
approved).

Scope: server only. Android UX is deferred to Phase 8c. The Android
client unconditionally sends `X-Syncler-Client-Min-Phase: 8` once
upgraded.

## What changed

### Schema (alembic 0010_master_key_rotation)

- `users.key_generation INTEGER NOT NULL DEFAULT 1`
- `encrypted_user_state.key_generation INTEGER NOT NULL DEFAULT 1`
- `pairings.state_version INTEGER NOT NULL DEFAULT 1`
- `pairings.key_generation INTEGER NOT NULL DEFAULT 1`
- New `rotation_challenges` (PK = bytea challenge, CHECK
  `octet_length = 32`, FK user_id CASCADE, session_id, expires_at
  index)
- New `master_key_rotation_audit` (BIGSERIAL PK, reason CHECK,
  user_time DESC index)

File: `server/alembic/versions/0010_master_key_rotation.py`

### Models (server/app/models.py)

`User`, `EncryptedUserState`, `Pairing` gain `key_generation`
(Pairing also gains `state_version`). New ORM classes
`RotationChallenge`, `MasterKeyRotationAudit` — both with the
matching CheckConstraints so the ORM metadata cannot drift from
the migration (Codex 96 YELLOW lesson).

### Schemas (server/app/schemas.py)

- `LoginResponse.key_generation` (§10.10 downgrade-defense field)
- `StateGetResponse.key_generation` + `StatePutRequest.key_generation_observed` +
  `StatePutResponse.key_generation`
- `PairingCompleteRequest.key_generation_observed`
- New: `RotationChallengeResponse`, `RotationPairingEntry`,
  `RotationUserStateBody`, `RotateMasterKeyRequest`,
  `RotationUserStateResult`, `RotationPairingResult`,
  `RotateMasterKeyResponse`
- `RotateMasterKeyRequest._validate_combinations` enforces the
  per-mode field matrix (§10.1) at the schema layer:
  - `password_rewrap`: requires new_auth_salt + new_auth_key_proof;
    FORBIDS new_encrypted_user_state + pairings.
  - `root_hygiene_rotation`: FORBIDS new_auth_salt +
    new_auth_key_proof; requires new_encrypted_user_state +
    pairings.
  - `root_compromise_rotation`: requires all four.

### Service (server/app/services/rotation.py)

`perform_rotation` runs the 14-step transaction per §10.8:

1. validate request shape (pydantic upstream)
2. user-row FOR UPDATE lock (ORM `with_for_update`, dialect-aware)
3. consume challenge: `SELECT ... WHERE challenge AND user_id AND
   session_id AND expires_at > now FOR UPDATE`. 401 if missing.
4. verify proof: `secrets.compare_digest(SHA-256(proof),
   user.auth_key_hash)`. On mismatch, increment failed-proof counter
   in a SEPARATE `async_sessionmaker()` session that commits
   independently, then raise 401 (or 429 if counter > 10/h).
5. success rate-limit: count `master_key_rotation_audit` rows for
   this user in last 24h; 429 at >=3 with Retry-After computed
   from the oldest qualifying row.
6. `key_generation_observed == users.key_generation` else 409.
7. for `root_*`: read canonical active pairing-id set inside the
   user-row lock; verify `len(raw) == len(deduped)` FIRST (Codex
   103 duplicate-detection fix), then set-equality.
8. for `root_*`: per-pairing CAS via
   `UPDATE pairings SET ... WHERE pairing_id = :pid AND user_id =
   :u AND revoked_at IS NULL AND state_version = :observed
   RETURNING state_version`. Affected-rows = 0 → 409
   `pairing_state_changed` with the list of mismatched ids +
   current versions.
9. for `root_*`: encrypted_user_state CAS via `UPDATE ... WHERE
   user_id AND state_version = :observed`. Affected-rows = 0 →
   409 `state_version_mismatch` with `current_state_version`.
10. user-row writes: encrypted_master_key always; auth_salt +
    auth_key_hash for password_rewrap + root_compromise;
    `key_generation = old + 1` for root_*.
11. INSERT audit row with `(user_id, reason, old_generation,
    new_generation, initiating_session_id, initiating_device_id,
    ip, user_agent, paired_count, occurred_at)`. No secrets.
12. for `root_compromise`: `UPDATE devices SET revoked_at = NOW()
    WHERE user_id AND revoked_at IS NULL` — revokes ALL sessions
    including the initiating one. The next request from any device
    returns 401 `device_revoked`.
13. DELETE the consumed challenge.
14. `await db.commit()`.

Failed-proof counter is the ONLY operation outside the rotation
transaction. It writes to `rate_limit_events` with
`actor_type='user'`, `actor_id=user_id`,
`route='rotate_proof_fail'`, 1-hour windows, via a separate
session opened from the global `async_sessionmaker` — so the
counter persists even when the rotation transaction rolls back
(per §10.8 step 4 implementation note).

### Router (server/app/routers/rotation.py)

Two endpoints behind `current_auth_context` (device-bound JWT):

- `POST /v1/account/rotate-master-key/challenge` — calls
  `issue_challenge(db, user_id=ctx.user.id, session_id=ctx.device.id)`.
- `POST /v1/account/rotate-master-key` — decodes binary fields,
  builds `RotationContext`, calls `perform_rotation(db,
  session_factory, ctx=...)`, translates typed exceptions into
  spec-mandated HTTP shapes (401/409/429 with the documented
  detail bodies and Retry-After headers).

Wired into `app/main.py` at prefix `/v1/account`.

### Mixed-client gate (server/app/services/key_generation.py)

`lock_user_and_gate(db, request, user_id, key_generation_observed)`:
1. SELECT users ... with_for_update on Postgres (plain SELECT on
   SQLite — its per-write serialization makes the lock implicit).
2. Parse `X-Syncler-Client-Min-Phase` (0 if missing or unparseable).
3. If client_phase < 8 AND user.key_generation > 1 → 426
   `account_upgraded_requires_newer_client`,
   `minimum_supported_phase: 8`.
4. If client_phase >= 8:
   - missing `key_generation_observed` → 400
     `key_generation_observed_required`.
   - mismatch → 409 `key_generation_mismatch`,
     `current_key_generation`, `client_action:
     refetch_master_key_and_state`.

Applied as the FIRST DB op in:

- `PUT /v1/state` (server/app/routers/state.py)
- `POST /v1/pairing/complete` (server/app/routers/pairing.py)
- `POST /v1/pairing/{id}/revoke` (lock only, no
  key_generation_observed required — revoke is just `revoked_at =
  NOW()`, no AAD concern)

The rotation endpoint itself does NOT go through this gate
(rotation has its own key_generation_observed check at step 6).

### Auth (server/app/routers/auth.py)

`login` now returns `key_generation=user.key_generation` for the
§10.10 downgrade-defense client-side check.

### Retention (server/app/jobs/retention.py)

The periodic retention job also DELETEs expired
`rotation_challenges` rows. Challenges expire in ~5 min so this
mostly mops up dropped requests.

### Tests (server/tests/test_master_key_rotation.py)

20 tests, all passing locally against Postgres test DB:

- challenge issue (32 bytes, persisted, device-bound; auth gate)
- password_rewrap happy path (MK unchanged, auth_salt + hash
  changed, key_generation unchanged, audit row written, no
  encrypted_user_state in response)
- password_rewrap schema rejects extra fields (400)
- root_hygiene_rotation happy path (key_generation bumped 1→2,
  state CAS, pairing CAS, devices stay live)
- root_compromise_rotation revokes ALL devices, post-rotation
  session 401s
- wrong proof → 401, challenge NOT consumed, no audit row
- wrong proof × 11 → 429 with Retry-After
- 4th rotation in 24h → 429
- wrong `key_generation_observed` → 409 with `current_key_generation`
- duplicate pairing id in request → 409 `pairing_set_changed`
- missing pairing in request → 409 `pairing_set_changed`
- extra pairing id in request → 409 `pairing_set_changed`
- stale pairing state_version → 409 `pairing_state_changed`
- stale user-state state_version → 409 `state_version_mismatch`
- successful rotation consumes challenge
- bogus challenge → 401 `rotation_challenge_invalid`
- pre-Phase-8 PUT /v1/state after rotation → 426
- Phase-8 PUT /v1/state missing key_generation_observed → 400
- Phase-8 PUT /v1/state with stale key_generation_observed → 409

Full server-test summary (only my-touched surfaces shown):
`57 passed, 1 failed (pre-existing test_pairing test fails on
encrypted_initial_state length validation, unrelated to Phase 8;
confirmed via git stash on master = same failure on main).`

The other 11 failing tests in the full suite are pre-existing
(test_phase3, test_crypto vectors, test_migration on 0008's
varchar(32), test_retention SQLite UUID binding, test_nonce_replay
advisory-lock contention). Confirmed by `git stash` baseline.

## Files

- `server/alembic/versions/0010_master_key_rotation.py`
- `server/app/models.py` (User/EncryptedUserState/Pairing
  key_generation, Pairing.state_version, RotationChallenge,
  MasterKeyRotationAudit)
- `server/app/schemas.py` (rotation request/response models +
  field additions to LoginResponse / State* / PairingComplete*)
- `server/app/services/rotation.py`
- `server/app/services/key_generation.py`
- `server/app/routers/rotation.py`
- `server/app/routers/state.py` (lock_user_and_gate first)
- `server/app/routers/pairing.py` (lock_user_and_gate first on
  complete + revoke)
- `server/app/routers/auth.py` (login returns key_generation)
- `server/app/main.py` (mount rotation router)
- `server/app/jobs/retention.py` (rotation_challenges sweep)
- `server/tests/test_master_key_rotation.py`
- `server/tests/conftest.py` (dispose global engine per test —
  the rotation service opens a SEPARATE session for the failed-
  proof counter; otherwise its asyncpg connections inherit a
  stale event loop on Windows)

## Specific risks I want eyes on

1. **Failed-proof counter session semantics.** The counter increment
   opens a brand-new `async_sessionmaker()` session that commits
   independently. Inside a multi-worker deployment, two workers
   could attempt the same `ON CONFLICT DO UPDATE` concurrently;
   Postgres serializes them via the unique constraint. The Retry-
   After comes from the window-end calculation, not from row
   contention.

2. **Audit row's `old_generation` capture.** Captured BEFORE the
   user-row mutation in step 10 (`old_generation = user.key_generation`
   before `user.key_generation = new_generation`). Verified by
   `test_password_rewrap_happy_path` reading the audit row.

3. **Step 3 challenge check happens INSIDE the user-row lock.**
   The challenge row's user_id matches the locked user; no
   cross-user challenge confusion possible.

4. **§10.8 step 12 device revocation.** The spec says "DELETE FROM
   device_sessions". This codebase models sessions as the
   `devices` table; "revocation" means `UPDATE devices SET
   revoked_at = NOW()`. The initiating session is included
   (matches the spec). Verified by
   `test_root_compromise_rotation_revokes_all_devices`: post-
   rotation, the session token 401s on /v1/state.

5. **Mixed-client gate on revoke.** revoke doesn't write a
   key_generation-tagged blob, but I lock + 426-gate it anyway
   per §10.5 (which lists it). A pre-Phase-8 client that's
   already paired loses the ability to unpair after the user
   rotates. Acceptable for V0.1 since no production clients
   exist.

6. **No mixed-client gate on the rotation endpoint itself.** The
   schema requires Phase-8 fields, so a legacy client would 422.
   Decision: don't double-gate.

## Output

Per reviewer, terse:

1. Implementation matches spec: GREEN / YELLOW / RED + items.
2. Anything missing for V0.1 server-side scope.
3. Anything new (races, ordering bugs, secret leaks, schema
   drift, test holes).

If dual-GREEN, I commit Phase 8b and move to Phase 8c (Android UX).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
