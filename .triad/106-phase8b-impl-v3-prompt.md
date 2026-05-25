=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 106 — Phase 8b impl v3 (post-105 fix-ups)

Consultation 105:
- **Codex** marked all three 104 fixes GREEN but found a new
  **RED**: `PUT /v1/state` echoes `locked_user.key_generation` in
  the response while `upsert_state_cas()` never writes
  `EncryptedUserState.key_generation` on insert or update. The row
  could carry the server default `1` even though the user is on
  generation `2`.
- **Gemini** marked everything GREEN, only nitpicked the v2 audit
  index using `sa.text("user_id")` instead of `"user_id"` (would
  confuse alembic autogenerate).

## v3 deltas

### Codex 105 RED fix — stamp encrypted_user_state.key_generation

- `app/services/state.py:upsert_state_cas()` now accepts
  `key_generation: int = 1`. Both the INSERT-new-row branch and
  the UPDATE branch write that value.
- `app/routers/state.py:put_user_state()` forwards
  `key_generation=locked_user.key_generation` (the value read
  inside the user-row FOR UPDATE lock via `lock_user_and_gate`,
  race-free against concurrent rotation).
- New test `test_put_state_stamps_row_with_locked_key_generation`:
  1) seed state at gen 1, assert row.key_generation == 1,
  2) root_hygiene rotation to gen 2,
  3) Phase-8 PUT with key_generation_observed=2,
  4) SELECT — assert row.key_generation == 2,
  5) assert response JSON also says key_generation == 2.

### Gemini 105 nit — alembic column-vs-text consistency

- `alembic/versions/0010_master_key_rotation.py` index switched to
  `["user_id", sa.text("occurred_at DESC")]` — `user_id` is a
  plain column reference now, so `alembic revision
  --autogenerate` will see it as a column index, not an expression
  index, and won't flag a phantom diff on future migrations.

## Files touched in v3

- `server/app/services/state.py` (key_generation kwarg + writes on
  insert/update)
- `server/app/routers/state.py` (forward locked generation)
- `server/alembic/versions/0010_master_key_rotation.py` (alembic
  autogen consistency)
- `server/tests/test_master_key_rotation.py` (new test)

## Test status

`tests/test_master_key_rotation.py`: **23 passed** (was 22).

`tests/test_master_key_rotation.py + test_state.py + test_events.py
+ test_pairing.py`: 39 passed, 1 failed. The single failure is the
pre-existing `test_pairing_complete_rejects_expired_token`
(`_b64(b"opaque-state")` = 12 bytes vs schema's `minimum=16`),
unrelated to Phase 8 (confirmed via `git stash`).

## Output

Per reviewer, terse:

1. Verdict on the v3 fix: GREEN / YELLOW / RED.
2. Anything still missing.
3. Anything new.

If dual-GREEN this round, I commit Phase 8b + transcripts and
move to Phase 8c (Android UX).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
