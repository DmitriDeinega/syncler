=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 105 — Phase 8b impl v2 (post-104 fix-ups)

Consultation 104:
- **Codex RED** — `POST /v1/pairing/complete` ran the
  `key_generation_observed` gate but the row was inserted with the
  server-default `key_generation=1`. After a root rotation, a new
  pairing AAD bound to gen 2 was stamped on a row carrying gen 1.
  Spec §10.4 violation.
- **Gemini YELLOW** — `lock_user_and_gate` 400'd Phase-8 clients
  calling `POST /v1/pairing/{id}/revoke` because the revoke route
  passes `key_generation_observed=None` and the helper required
  it.
- Minor (Codex): audit index spec'd as DESC but written ASC.

## v2 deltas

### Codex 104 RED fix

- `app/services/pairing.py:complete_pairing()` now takes an optional
  `key_generation: int = 1` kwarg and writes it onto the new
  `Pairing` row.
- `app/routers/pairing.py:complete()` captures the locked-user from
  `lock_user_and_gate` and forwards
  `key_generation=locked_user.key_generation` into
  `complete_pairing`. The locked read is race-free against
  rotation (the user-row `with_for_update` is the first DB op).
- New test
  `test_post_rotation_pairing_complete_stamps_new_generation`
  rotates 1→2, then completes a fresh pairing with
  `key_generation_observed=2`, then SELECTs the row and asserts
  `pairing.key_generation == 2`.

### Gemini 104 YELLOW fix

- `app/services/key_generation.py:lock_user_and_gate()` gains a
  `require_observed: bool = True` flag.
  - `True` (default) — Phase-8+ clients MUST send
    `key_generation_observed` (the existing blob-writing endpoints
    PUT /v1/state and POST /v1/pairing/complete keep this
    behavior).
  - `False` — `key_generation_observed=None` is accepted for
    endpoints that don't write a new blob; if the client DOES pass
    a value we still 409 on mismatch.
- `app/routers/pairing.py:revoke()` passes `require_observed=False`.
- New test
  `test_phase8_revoke_works_without_key_generation_observed`
  rotates 1→2 then revokes a pairing as a Phase-8 client without
  the `key_generation_observed` field; expects 204.

### Codex 104 minor — audit index DESC

- `alembic/versions/0010_master_key_rotation.py` index switched to
  `(user_id, occurred_at DESC)` using `sa.text("occurred_at DESC")`.
- `app/models.py:MasterKeyRotationAudit.__table_args__` aligned via
  `Index("...", "user_id", text("occurred_at DESC"))`.

## Files touched in v2

- `server/app/services/pairing.py` (new `key_generation` kwarg)
- `server/app/services/key_generation.py` (new `require_observed`
  flag)
- `server/app/routers/pairing.py` (forward locked generation +
  revoke flag)
- `server/app/models.py` (DESC index)
- `server/alembic/versions/0010_master_key_rotation.py` (DESC
  index)
- `server/tests/test_master_key_rotation.py` (2 new tests)

## Test status

`22 passed, 22 warnings` for `tests/test_master_key_rotation.py`.

`49 passed, 1 failed` across rotation + state + pairing + events +
auth surfaces — the single failure is the pre-existing
`test_pairing_complete_rejects_expired_token` schema-validation
miscoding (`_b64(b"opaque-state")` = 12 bytes; schema requires 16).
Confirmed via `git stash` to fail on master too. Not in scope for
Phase 8b.

## Output

Per reviewer, terse:

1. Verdict on the two 104 fixes: GREEN / YELLOW / RED.
2. Anything still missing.
3. Anything new found in fixing.

If dual-GREEN, I commit Phase 8b + transcripts and move to Phase
8c (Android UX).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
