=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 131 — V1 test cleanup strategy (post-Phase 9b)

Phase 9b closed at 4a29487. 70 server tests now fail. The user
asked me to fix them, with triad guidance on the approach.
Gemini quota exhausted earlier today (1h reset); Codex-only
verdict requested.

## Failure inventory (70 total)

### Bucket A — Enroll-cascade KeyErrors (~41 tests)

Modules that build a test fixture via inline
`POST /v1/auth/devices/enroll` and read
`enroll.json()['session_token']`. Phase 9b made
`encryption_public_key` mandatory; missing field → 422
Unprocessable Entity → no `session_token` in response →
`KeyError`. Affected modules:

- `tests/test_auth.py` (2 calls)
- `tests/test_events.py` (2 calls — 3 failing tests)
- `tests/test_integration_auth_devices.py` (2 calls — 1
  failing test)
- `tests/test_master_key_rotation.py` (1 helper call — 26
  failing tests)
- `tests/test_messages.py` (2 calls — but ALSO uses V1 wire
  for send, see bucket B)
- `tests/test_pairing.py` (1 call — 3 failing tests)
- `tests/test_phase3.py` (1 call — covers card tests AND
  template publish validators)
- `tests/test_state.py` (1 call — 3 failing tests)
- `tests/test_integration_retention.py` (uses enroll
  indirectly via store_message helper)
- `tests/test_nonce_replay.py` (1 test about advisory-lock
  contention, uses session indirectly)

The fix shape per call site is mechanical: add
`"encryption_public_key": <base64 32 bytes>` to the enroll
JSON body. Most tests don't care about the value beyond
"valid X25519 bytes" — a deterministic constant like
`bytes(32) { 0x42 }`-base64 suffices. NO test logic changes
needed.

`tests/test_devices.py` already updated to V2 enroll shape
(commit 6b3e0de) — 9 tests passing.

### Bucket B — V1 publish/upsert/delete wire shape (~21 tests)

These tests construct V1-shaped bodies (`encrypted_body`,
`nonce`, `envelope_signature` for messages; `encrypted_payload`
+ `nonce` for cards) and POST them to endpoints that now demand
V2 wire (`protocol_version=2`, `recipient_envelopes`,
`payload_ciphertext`, etc.).

- `tests/test_messages.py` (9 tests) — `/v1/messages/send`.
- `tests/test_phase3.py`:
  - `test_card_upsert_*` (5) — `/v1/cards/upsert` V1 shape.
  - `test_card_delete_*` (5) — `/v1/cards/delete` V1 shape
    (no `plugin_id`, no `protocol_version`).
  - `test_inbox_omits_dismissed_messages` (1) — uses V1
    inbox response shape.
- `tests/test_retention.py::test_prune_expired_deletes_expired_message`
  (1) — uses V1 store_message via fixture.

These tests can't be patched into V2 by adding fields. The V2
publish flow requires:
1. Fetch device directory (or stub it).
2. Generate CEK + AES-GCM payload.
3. HPKE-wrap CEK per device.
4. Ed25519-sign canonical envelope including sorted
   `recipient_envelopes`.
5. POST.

A V2 test helper that does steps 1-5 is feasible but the
helper itself is ~80 lines and needs to track the
directory_version. Once that helper exists, the per-test
patches are similar in size to bucket A.

### Bucket C — Pre-existing failures unrelated to Phase 9b (~6 tests)

- `tests/test_crypto.py::test_aead_encrypt_decrypt_and_vectors`
  — visible at session start, ciphertext-length validation.
- `tests/test_crypto.py::test_assemble_envelope_canonical_and_validation`
  — same era.
- `tests/test_phase3.py::test_publish_template_rejected_*`
  (5 tests) — plugin publish endpoint template validators;
  some reference `spinning_3d_card` layout that isn't
  registered. Pre-existing too (visible at session start).
- `tests/test_migration.py::test_alembic_upgrade_head_and_downgrade_base`
  — possibly broken by 0011 migration's data wipe or unique
  constraint flip; needs investigation.

## Concrete question

Three viable strategies. Pick one.

### Strategy 1: Fix everything in-place

- Add `encryption_public_key` to every inline enroll call
  (~9 modules, ~12 sites).
- Write a `publish_v2_helper(client, ...)` in conftest or a
  shared test utility that fetches the directory (or accepts
  it as a parameter), HPKE-seals, signs, and returns the V2
  body. ~80 LOC.
- Rewrite the 21 V1-wire tests to use it. Most can keep
  their assertion shape — just swap the POST body.
- Investigate + fix bucket C separately.

Pros: Maximum test coverage retained. Catches V2-protocol
regressions.
Cons: ~3 hours of work; helper crypto + UUID handling needs
to be byte-correct or the new tests fail their own
signatures.

### Strategy 2: Delete the V1-wire tests, keep the rest

- Add `encryption_public_key` to enroll helpers (bucket A).
- Delete bucket B (`test_messages.py`, the card tests in
  `test_phase3.py`, `test_retention.py` if it depends on V1
  publish).
- Investigate bucket C — fix or skip.

Pros: ~1 hour of work; coverage holes are V2-equivalent (the
recipient classifier tests already cover the security
boundaries the deleted tests covered, just at a different
level).
Cons: ~21 tests lost. The behavior tested by the deleted
tests (replay protection, expires_at clamping, pairing
gating, sequence regression, etc.) is V2 work but only at
the schema/router layer, not e2e. The recipient classifier
tests cover §11.10 but not the publish path's other
verifications.

### Strategy 3: Hybrid

- Bucket A fix in-place (mechanical).
- Bucket B delete the worst V1 ones, rewrite the most
  security-relevant (replay protection, signature
  verification) using a slim V2 helper.
- Bucket C separate cleanup later.

## Concerns

1. **Test helper canonicalization.** The V2 helper would need
   to produce byte-identical canonical JSON to the server's
   `envelopes_v2.py`. The Python helper code from
   `sdk-python/syncler/crypto.py:seal_v2_envelopes` already
   does this — could the test helper import + reuse that
   directly?
2. **Directory_version stubbing.** Tests don't realistically
   need cross-device coverage; a one-device user with
   `directory_version=1` is enough. The classifier tests
   already cover the 8-row matrix.
3. **Maintenance burden.** Strategy 1 leaves a V2 test helper
   in the codebase that's mostly duplicating SDK code. If
   the SDK changes, the test helper drifts.

## Output

Per reviewer, terse:

1. Strategy 1 / 2 / 3 — pick one and explain why.
2. If 1 or 3, recommend whether to import SDK functions
   directly or duplicate inline.
3. If 2, confirm bucket B deletion is acceptable risk.
4. Anything I'm missing in the failure analysis.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
