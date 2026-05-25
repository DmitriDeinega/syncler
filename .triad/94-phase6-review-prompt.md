=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Your ONLY output is a text reply to the
prompt below. You are FORBIDDEN from:

  - Writing or editing ANY file (no write_file, no edit_file, no
    str_replace, no create_file, no apply_diff).
  - Running ANY shell command that mutates state (no git commit,
    no git add, no git checkout, no git push, no git stash, no
    mkdir, no pip install, no npm install, no rm, no mv, no touch,
    no chmod).
  - Creating or modifying ANY directory.

You MAY use:
  - read_file / cat / Get-Content
  - grep / ripgrep / Select-String
  - list_directory / ls / Get-ChildItem
  - git status / git log / git diff / git show (read-only)

Reply text only.
=================================================================

# Consultation 94 — Phase 6 code review (V1.5 DX follow-ups)

Phase 6 plan reached dual-GREEN at consult 93 (Codex 93 YELLOW on
Item B's localhost broker URL — folded into impl as the existing
`SYNCLER_LAN_HOST` env-var pattern; rest GREEN). This consultation
reviews the actual code.

## Files changed

### `sdk-python/syncler/broker_storage.py`
- New `UnknownPairingIdError` exception class.
- `BrokerStorage` Protocol now has `is_reserved(pairing_id) -> bool`.
- `reserve()` semantic upgraded from "optional no-op" to real
  pending-set tracking.
- `complete()` raises `UnknownPairingIdError` if called for an
  unreserved + uncompleted ID (defense-in-depth alongside the
  broker handler's 404 gate).
- `InMemoryBrokerStorage`:
  - `_reserved: set[str]` added, locked under existing `_lock`.
  - `reserve()` adds to set; idempotent.
  - `is_reserved()` returns True if reserved OR completed (so
    re-POSTs after completion get to the CAS layer, not bounced
    as 404 — Gemini 93 + Codex 93 note).
  - `complete()` first checks both `_reserved` and `_entries`;
    raises `UnknownPairingIdError` if neither.
- Doc-string updated: removed the "V1.5 fixed-config deviation"
  language; pointer to Phase 6 registry semantics.
- `__all__` includes `UnknownPairingIdError`.

### `sdk-python/syncler/broker/app.py`
- Imports `UnknownPairingIdError`.
- Handler has a new pre-decrypt gate (after JSON parse + shape
  validation, BEFORE base64 length checks + decrypt):
  ```python
  if not storage.is_reserved(body["pairing_id"]):
      raise HTTPException(status_code=404, detail="pairing slot not found")
  ```
  Detail body is opaque — does NOT echo the requested `pairing_id`
  (Codex 93).
- `complete()` call wrapped to catch `UnknownPairingIdError` →
  HTTP 404 with the same opaque message (race condition between
  the gate and the call).
- Module doc-string updated to add the 404 status, remove the
  "V1.5 deviation" language, mention the registry as the gate.

### `sdk-python/syncler/client.py`
- No behavior change — the existing
  `if sender_broker_url is not None and self._broker_storage is not None:
      self._broker_storage.reserve(self._pending_pairing_id)`
  block at line 188 already does the right thing per the new
  Protocol. Verified by tests below.

### `sdk-python/tests/test_broker_app.py`
- Storage fixture now pre-reserves the canonical test
  `_PAIRING_ID` so existing happy-path tests still work.
- New `test_unreserved_pairing_id_returns_404_opaque`: builds a
  cryptographically valid envelope for a different (unreserved)
  pairing_id; asserts 404 + the body does NOT echo the ID.
- New `test_replay_after_completion_does_not_404`: completes
  once; re-POSTs; asserts is_reserved stays True after completion
  and the second POST reaches the CAS layer (200, not 404).

### `sdk-python/tests/test_bootstrap.py`
- `test_in_memory_broker_storage_cas` extended: asserts
  `complete()` on unreserved raises `UnknownPairingIdError`;
  reserves first; asserts `is_reserved` stays True after
  completion.
- New `test_in_memory_broker_storage_reserve_idempotent`: covers
  double-reserve, reserve-after-complete, and `is_reserved` for
  an unknown ID returning False.
- New `test_client_create_pairing_qr_reserves_pairing_id`: patches
  `client.session.post` to return a known pairing_id; asserts
  `storage.is_reserved(pairing_id)` is True after
  `create_pairing_qr(sender_broker_url=...)`. Closes the Codex 93
  missing-test item.
- New `test_client_create_pairing_qr_does_not_reserve_without_broker_url`:
  V1 manual-pairing path — no reserve when sender_broker_url is
  None.

### `examples/trading-bot/bot.py`
- New module constants: `BOOTSTRAP_PRIV_FILE` (default
  `~/.syncler/keys/trading-bot-bootstrap.bin`), `BROKER_PORT`
  (default 8002 via `SYNCLER_BROKER_PORT`), `SENDER_BROKER_URL`
  (default `http://${SYNCLER_LAN_HOST}:8002/` — matches the
  existing ack-server convention; Codex 93 YELLOW addressed).
- New `_load_or_create_bootstrap_keypair(state)`: idempotent;
  persists raw X25519 private key to disk with 0600 best-effort
  (Codex 93: noted the Windows ACL limitation in the doc-string +
  README); persists pub hex to state.json.
- New `_ensure_bootstrap_key_registered(client, state, pub_raw)`:
  idempotent server registration; tracks `bootstrap_key_id_hex`
  in state for rotation detection.
- New `_start_broker_thread(priv, pub_raw, url, storage)`:
  spawns uvicorn in a daemon thread of the bot's process so the
  Client and the broker share `InMemoryBrokerStorage`. Fail-fast
  port-bind check before spawn (Codex 93 hang-prevention). Waits
  briefly for `server.started` so the device's POST doesn't race
  startup.
- `cmd_pair` rewritten:
  1. Load/create bootstrap keypair.
  2. Register bootstrap key if not already.
  3. Build new Client with `InMemoryBrokerStorage`.
  4. Start broker thread.
  5. `create_pairing_qr(sender_broker_url=...)` — which calls
     `storage.reserve(pairing_id)`.
  6. Block on `wait_for_pairing(timeout_seconds=120)`.
  7. Persist user_id + pairing_key_hex to state.json.
  - On TimeoutError, prints fallback hint pointing at
    `set-pairing`.
- `cmd_set_pairing` preserved as V1 manual fallback with a
  doc-string explaining when it's needed.
- `cmd_loop` unchanged — still reads `user_id` + `pairing_key_hex`
  from state.json; both automated and manual paths populate them.

### `examples/trading-bot/README.md`
- §3 rewritten to describe the new automated flow: bootstrap
  keypair persistence, in-process broker, wait_for_pairing, with
  Windows ACL caveat called out. `set-pairing` documented as the
  V1 fallback.

### `docs/crypto-spec.md` §9.3
- "V1.5 deviation: fixed-config broker" subsection replaced with
  "Single fixed `sender_broker_url` and the pending-pairing
  registry" — explains the AAD-binding half is still satisfied
  by the fixed URL, the "reject unknown pairing_id" half is now
  enforced by the registry. V2 per-pairing-URLs are still listed
  as deferred (separate from the registry work, which is now
  shipped).

### `docs/integration-guide.md`
- §8 trailing pointer no longer says "the example still uses V1
  manual pairing"; now describes the trading-bot as using the
  automated flow with set-pairing as fallback.
- §8.5 "Production hardening" callout rewritten: registry
  multi-worker requirement (Client.reserve in one process must
  be visible to broker.is_reserved in another), rate limiter
  still mandatory as the CPU defense, link to crypto-spec §9.3.

### `docs/ROADMAP.md` #7
- Strikes the "example still uses V1 manual pairing" parenthetical;
  notes Phase 6 migrated `pair` to the automated flow with the
  `set-pairing` fallback preserved.

## Test counts (local)

- `sdk-python/tests/`: 38/38 passing.
- New tests:
  - `test_unreserved_pairing_id_returns_404_opaque`
  - `test_replay_after_completion_does_not_404`
  - `test_in_memory_broker_storage_reserve_idempotent`
  - `test_client_create_pairing_qr_reserves_pairing_id`
  - `test_client_create_pairing_qr_does_not_reserve_without_broker_url`
- Updated tests:
  - `test_in_memory_broker_storage_cas` extended with reserve/
    is_reserved/UnknownPairingIdError assertions
  - `test_broker_app.py` fixture pre-reserves canonical pairing_id

`bot.py` imports cleanly (`python -c "import bot"` passes).

## What I need

Per reviewer, per file area:
1. `broker_storage.py` — GREEN / YELLOW / RED. Is the Protocol
   change documented sufficiently for third-party implementers?
   Is `is_reserved` returning True for completed IDs the right
   call?
2. `broker/app.py` — GREEN / YELLOW / RED. Is the 404 gate at the
   right position in the handler pipeline (after JSON parse +
   shape + type checks, before base64 length + decrypt)? Is the
   opaque body correct? Is the UnknownPairingIdError race-recovery
   path correct?
3. `client.py` (no change) + `test_bootstrap.py` (new tests) —
   GREEN / YELLOW / RED. Do the tests actually prove the wiring?
4. `examples/trading-bot/bot.py` — GREEN / YELLOW / RED. Is the
   in-process broker thread + storage sharing correct? Does the
   timeout fallback message guide the user to the right place?
   Any bind-race / startup-race concerns?
5. Docs (crypto-spec §9.3, integration-guide §8 + §8.5, ROADMAP,
   trading-bot README) — GREEN / YELLOW / RED.
6. Anything missing from the consult 93 plan.
7. Anything new (security, footgun, doc accuracy).
8. Commit-readiness vote.

Reply text only. Do NOT call any write/mutation tool.
