=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 112 — Phase 12 fixups + Phase 13 (pairing revoke on compromise)

Two threads, one consult — they shipped together at commit
`c80fb23` against the `9b3c3ae` Phase 12 baseline.

## Phase 12 fixups (from triad 111)

Codex 111 YELLOW found three items, Gemini YELLOW found the
docstring nit. All addressed:

- `sdk-python/syncler/crypto.py:assemble_live_card_delete_envelope`
  helper updated from the 3-field shape to the new 5-field shape
  `(sender_id, user_id, card_key, nonce, expires_at)`. Direct
  callers of this public helper now produce signatures the server
  accepts.
- `sdk-python/syncler/client.py:delete_card` docstring corrected
  ("now + 24 h" matches implementation, not the "7 days" mistake).
- `server/app/schemas.py:LiveCardDeleteRequest.expires_at` gains
  `require_timezone_aware` validator (mirrors MessageSendRequest).
  Without it, a naive datetime reached the route's tz-aware
  comparison and TypeError'd to 500; now it's 400 at parse.
- New `test_card_delete_rejects_naive_expires_at` in test_phase3.

## Phase 13 — root_compromise auto-revokes pairings (closes Codex 98)

Codex consultation 98 §4 flagged that for
`root_compromise_rotation` the attacker who stole the old master
key still holds every sender's pairing key bytes (they live in
`encrypted_user_state`). Re-encrypting the blob under the new MK
preserves those bytes; an attacker keeps sending until each
pairing is server-side revoked.

Phase 8e shipped with this documented as a V2 follow-up. Phase 13
closes it.

### Change

`server/app/services/rotation.py:perform_rotation` step 12 — the
compromise branch now updates both tables, not just devices:

```sql
UPDATE devices  SET revoked_at = NOW()
  WHERE user_id = :u AND revoked_at IS NULL;
UPDATE pairings SET revoked_at = NOW()
  WHERE user_id = :u AND revoked_at IS NULL;
```

Subsequent sends under any of those pairing keys hit the existing
`PairingMissingError` path in `services/messages.py` /
`services/cards.py` and get 410. The legitimate sender re-pairs
via the existing pairing flow with fresh key material.

`root_hygiene_rotation` and `password_rewrap` leave pairings
alone — their threat models don't include sender-channel
compromise.

### Spec updates (docs/crypto-spec.md)

- §10.1 table — new "Pairings revoked" column.
- §10.2 "Sender-held pairing keys" bullet rewritten — no longer
  a V2 follow-up; root_compromise revokes, other modes don't.
- §10.8 step 12 rewritten to cover both UPDATEs.

### Test

`test_root_compromise_rotation_revokes_all_pairings` seeds two
active pairings, runs root_compromise, asserts both rows
flipped to `revoked_at != null`. Pre-rotation sanity check
confirms both were active.

## Test status

- 8 delete-related phase3 tests pass (6 from Phase 12 + the
  naive-expires-at test + the tz-aware validator now hardened).
- 16 master_key_rotation tests pass (15 existing + 1 new
  compromise-pairings test).
- No new regressions. Pre-existing test_pairing + 5
  test_publish_template_rejected_* + 1 nonce_replay_advisory
  failures unchanged.

## Risks I want eyes on

1. **No SDK-side change for Phase 13.** Senders don't get a push
   notification "your pairing was revoked"; they learn on the
   next send attempt via 410. Acceptable for V1.5 — the SSE
   stream is per-user not per-sender, and a sender-discovery
   ping endpoint adds protocol surface. Worth flagging if you
   think the protocol owes senders a more proactive signal.

2. **Pairing revoke ordering inside rotation.** The pairing
   UPDATE runs AFTER the per-pairing CAS in step 8 has already
   re-encrypted them under the new MK. The re-encryption is
   wasted work (the rows immediately get `revoked_at` set), but
   the alternative — short-circuiting the CAS — would diverge
   the compromise path's step ordering from hygiene's and risk
   subtle bugs. Worth flagging if you'd prefer a cleaner
   short-circuit.

3. **CAS state on revoked pairings.** Once `revoked_at` is set
   the row is still readable; the encrypted_state column now
   carries new-MK ciphertext that nobody will use (the
   attacker can't unwrap MK; the legitimate user re-pairs with
   fresh material). Acceptable — just dead data. Worth
   confirming.

4. **The fix's correctness depends on the existing 410 path
   on send.** `services/messages.py` already raises
   `PairingMissingError` when there's no active pairing.
   Phase 13 just flips the active flag; it leans on that
   pre-existing behavior. Worth double-checking that path
   still works for an attacker-controlled send (not a
   legitimate user's send).

5. **SDK helper rename consideration.** Should
   `assemble_live_card_delete_envelope` be versioned (e.g.
   `v2`) given the signature shape changed? Decision: no — the
   old 3-field shape was never spec'd to work against the
   Phase-12 server, and Phase 12 is the first time the public
   spec covers it. Worth challenging.

## Output

Per reviewer, terse:

1. Verdict on Phase 12 fixups: GREEN / YELLOW / RED + items.
2. Verdict on Phase 13: GREEN / YELLOW / RED + items.
3. Anything missing.
4. Anything new.

If dual-GREEN, both backlog items close and the V1.5 backlog
is empty.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
