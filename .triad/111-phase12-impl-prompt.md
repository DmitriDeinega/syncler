=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 111 — Phase 12 (cards/delete replay + freshness)

Closes the V1.5 backlog item Codex consultation 95 flagged: the
`POST /v1/cards/delete` envelope previously carried no `nonce` or
`expires_at`, so a captured delete could replay indefinitely against
any future card with the same `(sender_id, user_id, card_key)`.

Shipped at commit `9b3c3ae` against `b24629c` baseline.

## Wire format change

Spec §8.3 envelope:

  Before: `{sender_id, user_id, card_key}`
  After:  `{sender_id, user_id, card_key, nonce, expires_at}`

Canonical JSON, same sort/separator rules. `nonce` is base64 of 12
random bytes. `expires_at` is ISO 8601 UTC, capped server-side at
now + 48 h (same as upsert).

## Server (server/app/{routers/cards.py, schemas.py})

- `LiveCardDeleteRequest` gains `nonce` + `expires_at` with the
  same validators as upsert.
- `_build_delete_envelope_bytes` includes both new fields in the
  canonical signed envelope.
- The route:
  1. Verifies signature first (so a malformed/forged envelope
     never touches the nonce table).
  2. Checks `expires_at > now` (400 if past) and
     `expires_at <= now + MAX_TTL` (400 if too far).
  3. Calls `record_nonce_or_reject` on the shared
     `nonce_replay` table (409 on replay).
  4. Calls `delete_live_card` (idempotent no-op if missing).
  5. **Explicit `await db.commit()`** — this is the critical
     piece. `delete_live_card` only commits when a row was
     actually deleted; for the idempotent missing-card branch
     the session never commits and the nonce row we recorded in
     step 3 rolls back on session-close, opening a replay
     window. The explicit commit closes that gap.

## SDK (sdk-python/syncler/client.py)

- `delete_card()` gains `nonce: bytes | None = None` and
  `expires_at: datetime | None = None` kwargs.
- Defaults: `nonce = os.urandom(12)`, `expires_at = now + 24 h`
  (well under the 48 h server cap).
- Envelope built via `canonical_json` with both new fields;
  body includes them verbatim.
- Existing call sites work unchanged.

## Tests (server/tests/test_phase3.py)

- `test_card_delete_signature_bound_to_user` (existing) — updated
  to send the new envelope shape.
- `_delete_body` helper added.
- 4 new tests:
  - `test_card_delete_rejects_replayed_envelope` — second submission
    of the same envelope returns 409 `nonce already used`.
  - `test_card_delete_rejects_expired_envelope` — `expires_at`
    in the past → 400.
  - `test_card_delete_rejects_exceeds_ttl_cap` — `expires_at >
    now + 48 h` → 400 with the matching message.
  - `test_card_delete_records_nonce_even_when_card_missing` —
    delete a non-existent card returns 204, replay returns 409.
    Proves the explicit-commit close on the no-op path.

121 server tests pass. The 6 remaining failures
(test_pairing_complete_rejects_expired_token, 5
test_publish_template_rejected_*) are pre-existing on master
(confirmed via `git stash`), unrelated to Phase 12.

## Docs

- `docs/crypto-spec.md` §7 (nonce-replay) updated to mention
  cards/delete is now in scope.
- §8.3 envelope JSON + python reference snippet updated.
- `docs/integration-guide.md` §5.5 mentions the auto-handled
  nonce/expires_at + 400/409 server responses.

## Risks I want eyes on

1. **Explicit `await db.commit()` in the route after
   `delete_live_card`.** Other code in the project relies on
   service functions managing their own commits. The route
   normally lets the service function decide. We deviate here
   specifically because the nonce row MUST persist regardless of
   whether a row was deleted, and the cleanest way to express
   that is at the route layer where both side effects converge.
   Worth challenging if there's a cleaner pattern.

2. **Default `expires_at = now + 24 h` in the SDK.** Server caps
   at 48 h; 24 h is the conservative midpoint. Senders that
   queue delete envelopes for long-deferred retries may need to
   pass an explicit value or refresh the envelope. Worth flagging
   if you think the default should be tighter or looser.

3. **Order of operations: signature → freshness → replay.**
   Verifying the signature first means an unauthenticated
   attacker can't burn nonces in the registry. The freshness
   check before the replay-registry insert lets us 400 a stale
   envelope without spending the registry slot.

4. **Pre-existing test failures.** Did NOT fix the 5 phase3
   template tests or the pairing-expired-token test — they fail
   the same way on `master`, and fixing them is out of Phase 12
   scope. Worth noting in case you want them tracked.

## Output

Per reviewer, terse:

1. Verdict: GREEN / YELLOW / RED + items.
2. Anything missing.
3. Anything new.

If dual-GREEN, Phase 12 ships as-is and I move to Phase 13 (sender
pairing-key revocation, the other Codex-flagged backlog item).

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
