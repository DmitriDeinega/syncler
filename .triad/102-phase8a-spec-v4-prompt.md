=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
=================================================================

# Consultation 102 — Phase 8a spec v4 (master-key rotation)

Consultation 101: **Gemini GREEN, Codex RED narrowly** on
§10.8's locking inconsistency. Spec v4 closes that:

1. Lock MUST now explicitly covers every state-mutating
   endpoint, not just pairing create/delete (Codex 101 RED).
2. §10.8 CAS implementation tightened to either
   `SELECT ... FOR UPDATE` on the blob row OR conditional
   `UPDATE WHERE state_version = :observed AND key_generation = :observed`
   with affected-row check (Codex 101 clarity).
3. §10.8 reordered so the user-row `SELECT ... FOR UPDATE`
   happens BEFORE proof verification (Codex 101 clarity).
4. §10.8 step 3 explicitly notes the failed-proof counter
   needs a separate connection / autonomous transaction so the
   increment persists across the main transaction's rollback
   on 401 (Gemini 101 impl observation).

No other §10 content changes from v3.

## Updated §10.5 (delta)

> ... 409 with `current_key_generation` in the response if it
> doesn't match `users.key_generation`. This prevents the post-
> rotation race where a pre-rotation client still holds the old
> master key in memory and tries to push state encrypted under
> it after the rotation committed.
>
> **MUST.** Every endpoint that mutates a
> `key_generation`-tagged blob serializes against rotation by
> taking `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE`
> as its first DB operation, before checking
> `key_generation_observed` or applying any CAS write.
> Endpoints in scope:
>
> - `POST /v1/account/rotate-master-key`
> - `PUT /v1/state` (encrypted_user_state CAS push)
> - `PUT /v1/pairings/{pairing_id}/state` (per-pairing state CAS)
> - `POST /v1/pairing/complete` (creates a pairing row)
> - `DELETE /v1/pairings/{pairing_id}` (revokes a pairing)
> - Any future endpoint writing or removing a
>   `key_generation`-tagged row.
>
> The user-row lock + per-blob CAS together prevent both
> lost-update races (concurrent pushes to the same blob) AND
> generation-mixing races (a pre-rotation client's push
> landing after the rotation commit).

## Updated §10.8 (full rewrite)

The rotation handler runs inside a single Postgres transaction:

1. **Validate request shape** (Pydantic — reject unknown fields,
   verify required combinations per `reason`).
2. **Lock the user row** —
   `SELECT users.* FROM users WHERE id = :user_id FOR UPDATE`.
   Per the §10.5 MUST clause, every state-mutating endpoint
   takes this lock first; rotation is the same. Read
   `users.auth_key_hash`, `users.key_generation`,
   `users.auth_salt` from the locked row.
3. **Consume the rotation challenge** —
   `SELECT challenge FROM rotation_challenges
    WHERE challenge = :c AND user_id = :u AND session_id = :s
    AND expires_at > NOW() FOR UPDATE`.
   401 if missing.
4. **Verify `current_password_proof`** — compute SHA-256,
   constant-time-compare against the locked
   `users.auth_key_hash`.
   - On mismatch: increment a per-user failed-proof counter in
     a SEPARATE database transaction (separate connection /
     `BEGIN`-`COMMIT` block) so the increment persists even
     after this transaction rolls back. The counter is keyed on
     `user_id` with a 1-hour window (10 attempts → 429
     `Retry-After`).
   - Then raise 401. The main transaction rolls back — the
     challenge is NOT consumed (consumption is only on
     successful rotation; step 13).
5. **Rate-limit successful rotations** — `SELECT COUNT(*) FROM
    master_key_rotation_audit WHERE user_id = :u AND
    occurred_at > NOW() - INTERVAL '24 hours'`. 429
   `Retry-After` if at 3.
6. **Verify `key_generation_observed`** —
   `key_generation_observed == users.key_generation` (already
   loaded from the locked row in step 2). 409 if mismatch.
7. **For `root_*`: lock + verify pairing set.** Inside the
   user-row lock (no concurrent pairing-create can happen per
   §10.5 MUST), count pairings:
   `SELECT COUNT(*) FROM pairings WHERE user_id = :u AND revoked_at IS NULL`.
   Verify the request lists exactly that count. 409
   `pairing_set_changed` if not.
8. **CAS each pairing in request** — for each:
   `UPDATE pairings SET encrypted_state = :new, state_version = :observed + 1,
    key_generation = :new_generation
    WHERE pairing_id = :pid AND state_version = :observed
    RETURNING state_version`.
   Affected-rows = 0 → 409 `pairing_state_changed` (include the
   list of mismatched pairing_ids in the response with their
   current `state_version`).
   Equivalent forms (any of these is OK, but you MUST pick
   atomic conditional update OR SELECT FOR UPDATE on the blob
   row — never check-then-update with a gap):
   - `UPDATE ... WHERE state_version = :observed RETURNING ...`
     and check rowcount, OR
   - `SELECT ... FROM pairings WHERE pairing_id = :pid FOR UPDATE`,
     compare in code, then UPDATE.
9. **CAS `encrypted_user_state`** — same pattern. For `root_*`:
   ```
   UPDATE encrypted_user_state
   SET encrypted_blob = :new, state_version = :observed + 1,
       key_generation = :new_generation
   WHERE user_id = :u AND state_version = :observed
   ```
   `password_rewrap` skips this entirely.
10. **Apply user-row writes** —
    `users.encrypted_master_key = :new_blob`. For
    `password_rewrap` + `root_compromise_rotation`:
    `users.auth_key_hash = SHA-256(new_auth_key_proof)` and
    `users.auth_salt = :new_salt`. For `root_*`:
    `users.key_generation = old + 1`.
11. **INSERT audit row** — `master_key_rotation_audit` capturing
    `(user_id, reason, old_generation, new_generation,
     initiating_session_id, initiating_device_id, ip,
     user_agent, paired_count)`. NO secret material.
12. **For `root_compromise_rotation`: revoke sessions** —
    `DELETE FROM device_sessions WHERE user_id = :u` (ALL
    sessions including the initiating one).
13. **Consume the challenge** —
    `DELETE FROM rotation_challenges WHERE challenge = :c`.
14. **COMMIT.**

If any of steps 1-12 raises after some DB writes have happened,
the surrounding `async with db.begin()` / `get_db()` lifecycle
rolls everything back. The challenge is never consumed unless
the rotation succeeds (intentional: a hijacker who triggers a
failure shouldn't burn the legitimate user's challenge).

### Failed-proof counter implementation notes

Concrete: the failed-proof counter is a row in `rate_limit_events`
keyed on `(actor_type='user', actor_id=user_id, route='rotate_proof_fail')`
with the existing 1-hour window logic. The increment uses a
SEPARATE `async with session_factory() as fail_db:` (not the
request's `db`) and commits independently. This is the only
operation in the entire rotation flow that escapes the main
transaction.

### Response

Same as v3 (§10.8 response block).

```json
{
  "key_generation": <new>,
  "encrypted_user_state": {
    "state_version": <new>,
    "key_generation": <new>
  },
  "pairings": [
    {"pairing_id": "<uuid>", "state_version": <new>, "key_generation": <new>}
  ]
}
```

---

## Sections unchanged from v3

§10.1 (rotation modes table), §10.2 (non-goals), §10.3 (schema),
§10.4 (CAS lockstep), §10.6 (wire format), §10.7 (auth proof
security boundary), §10.9 (AAD shapes), §10.10 (downgrade
defense), §10.11 (mixed-client), §10.12 (client flow),
§10.13 (test vectors with embedded Argon2id + AES-GCM bytes).

## Specifically reviewing in v4

1. The expanded §10.5 MUST clause covering ALL state-mutating
   endpoints — confirm the list is complete.
2. §10.8 step 8/9 atomic UPDATE form vs SELECT FOR UPDATE
   — both forms documented; either is acceptable. Confirm.
3. §10.8 step 4 separate-transaction note for the failed-proof
   counter — does this match how `rate_limit_events` is used
   elsewhere in the codebase (e.g. `services/messages.py`)?
4. Step ordering (lock → challenge → proof → rate-limit →
   key_gen check → pairing-set → CAS) — any race window I
   missed?

## Output

Per reviewer:
1. Per-section: GREEN / YELLOW / RED on the §10.5 and §10.8
   updates.
2. Anything still missing.
3. Anything new.

If dual-GREEN, this becomes `docs/crypto-spec.md §10` verbatim.

Reply text only. Do NOT call any write/mutation tool.
