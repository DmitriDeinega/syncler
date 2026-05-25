=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
=================================================================

# Consultation 103 — Phase 8a spec v5 (master-key rotation)

Consultation 102: **Gemini GREEN, Codex YELLOW** with three
precise pairing-CAS fixes + one route-name nit. Spec v5 closes
each.

## v5 deltas from v4 (Codex 102 items only)

### 1. §10.5 route-name correction

The actual codebase route is `POST /v1/pairing/{pairing_id}/revoke`
(confirmed at `server/app/routers/pairing.py:322`), not the
`DELETE /v1/pairings/{pairing_id}` I wrote in v4. Aligning.

Updated §10.5 endpoint list:

> - `POST /v1/account/rotate-master-key`
> - `PUT /v1/state` (encrypted_user_state CAS push)
> - `PUT /v1/pairings/{pairing_id}/state` (per-pairing state CAS)
> - `POST /v1/pairing/complete` (creates a pairing row)
> - `POST /v1/pairing/{pairing_id}/revoke` (revokes a pairing)
> - Any future endpoint writing or removing a
>   `key_generation`-tagged row.

### 2. §10.8 step 7 — verify exact active pairing ID set

Step 7 now reads (v4 said "count"; v5 says "exact set"):

> **For `root_*`: verify the exact active pairing ID set inside
> the user-row lock.** No concurrent pairing-create can happen
> per §10.5 MUST. Read the canonical active set:
>
> ```sql
> SELECT pairing_id FROM pairings
> WHERE user_id = :u AND revoked_at IS NULL
> ```
>
> Compute the request's deduped pairing-id set. If the two
> sets differ in any element (extra request ID, missing
> request ID, or duplicate request ID), respond
> **409 `pairing_set_changed`**. This is stricter than v4's
> "exact count" — UUID secrecy is not the authorization
> boundary; explicit set equality is.

### 3. §10.8 step 8 — pairing CAS scope hardening

Step 8 now requires `user_id` and `revoked_at` predicates in
the CAS clause (v4 only used `pairing_id` + `state_version`):

> **CAS each pairing in request** — for each:
>
> ```sql
> UPDATE pairings
> SET encrypted_state = :new,
>     state_version = :observed + 1,
>     key_generation = :new_generation
> WHERE pairing_id = :pid
>   AND user_id = :u
>   AND revoked_at IS NULL
>   AND state_version = :observed
> RETURNING state_version
> ```
>
> Affected-rows = 0 → 409 `pairing_state_changed` (include the
> list of mismatched pairing_ids in the response with their
> current `state_version`). The `user_id` and `revoked_at`
> predicates are defense-in-depth — step 7 already verified
> the set, but the predicates prevent rotating a stale or
> wrong-owner pairing if the implementation diverges.
>
> The `SELECT ... FOR UPDATE` then update-in-code form is also
> acceptable — but the SELECT must carry the same `user_id` +
> `revoked_at` filters.

### 4. §10.8 step 9 — explicit "affected rows = 0 → 409"

Step 9 (v4 said "same pattern"; v5 spells out the rowcount
check):

> **CAS `encrypted_user_state`** — for `root_*`:
>
> ```sql
> UPDATE encrypted_user_state
> SET encrypted_blob = :new,
>     state_version = :observed + 1,
>     key_generation = :new_generation
> WHERE user_id = :u AND state_version = :observed
> ```
>
> Affected-rows = 0 → 409 `state_version_mismatch` (response
> body carries `current_state_version`). `password_rewrap`
> skips this step entirely.

---

## All other sections unchanged from v4

§10.1 (rotation modes table), §10.2 (non-goals), §10.3 (schema
incl. `rotation_challenges` + `master_key_rotation_audit`),
§10.4 (CAS lockstep contract), §10.5 (now with the corrected
endpoint list), §10.6 (wire format), §10.7 (auth proof bearer-
credential + 5-layer defense), §10.8 (now with the four v5
refinements), §10.9 (AAD shapes), §10.10 (downgrade defense
incl. wrap-MK fetch), §10.11 (mixed-client 426 Upgrade
Required), §10.12 (client flow), §10.13 (Argon2id + AES-GCM
test-vector bytes).

## Output

Per reviewer:
1. §10.5 and §10.8 changes: GREEN / YELLOW / RED.
2. Anything still missing.
3. Anything new.

If dual-GREEN, this becomes `docs/crypto-spec.md §10` verbatim.

Reply text only. Do NOT call any write/mutation tool.
