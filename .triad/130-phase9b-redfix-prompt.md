=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 130 — Phase 9b RED fixups (post-129)

Triad 129: Gemini quota exhausted (1h19m reset). Codex RED with
two blocking items + four answers. Fixed at `7ed8c50`.

## v2 deltas vs 816dbfc

### Block 1: Android recipient-side Ed25519 verify (Codex 129 RED #1, spec §11.8)

`android/core/crypto/Aad.kt`:
- New `V2Aad.eventSignedEnvelopeBytes(...)` and
  `liveCardUpsertSignedEnvelopeBytes(...)`. Match the server's
  `app/services/envelopes_v2.py:build_*_envelope_bytes` byte-
  for-byte.
- `canonicalJsonBytes()` extended to handle nested
  `List<Map<String, Any>>` via a recursive `encodeCanonicalValue`
  — necessary for the sorted `recipient_envelopes` array.

`android/feature/inbox/InboxRepository.kt`:
- `verifyV2Signature(dto, candidates)` rebuilds the canonical
  signed bytes from the DTO (recipients sorted by lowercase
  device_id), runs `Signing.verify(senderPublicKey,
  canonicalBytes, signatureBytes)`. Tries every paired-sender
  record's public key (rotation can leave multiple records);
  any matching verify passes.
- Refresh loop calls `verifyV2Signature` BEFORE any other
  envelope field is trusted. Bad signature → drop the item +
  set `sawDecryptFailure = true`.

### Block 2: Don't advance lastSince on event decrypt failure (Codex 129 RED #2)

Previously: an event message whose HPKE/AES/signature failed
got silently dropped AND `lastSince` advanced past it →
permanent data loss across key rotation or corruption.

Now: `sawDecryptFailure` is set on any event failure (sig
verify, no own envelope, HPKE open, AES open). The cursor
stays at `lastSince` for the next poll — that gives a
re-fetched envelope, and pinned `payload_ciphertext_sha256` in
HPKE info catches any swap.

Live cards aren't cursor-tracked (they refetch wholesale each
poll), so the flag only gates events.

### Nit 4: pin cryptography upper bound

`server/pyproject.toml`: `cryptography>=48.0.0,<49`. SDK was
already pinned; this matches.

## Deferred (Codex 129 nits 3 + 5; not blocking)

- **Device-side own-envelope membership check explicit.** Codex
  noted `firstOrNull` already fails closed so this is a diagnostic
  improvement, not a cryptographic blocker. Logged at INFO level
  ("no recipient_envelope for device") in the existing
  `openV2EnvelopeOrNull`.
- **Delete dead V1 LiveCardDeleteRequest schema.** Tracked under
  the V1 test cleanup pass.

## Files in scope

- `android/core/crypto/src/main/kotlin/.../Aad.kt`
- `android/feature/inbox/src/main/kotlin/.../InboxRepository.kt`
- `server/pyproject.toml`

## Tests passing

- `:core:crypto:testDebugUnitTest` — cross-platform vector still
  green; the new canonical recursive encoder doesn't break the
  existing fixtures.
- `:feature:inbox:testDebugUnitTest` passes.
- `:app:assembleDebug` succeeds.

## Output

Per reviewer, terse:

1. Verdict on the RED fixups: GREEN / YELLOW / RED + items.
2. Anything still blocking Phase 9 close-out.

If GREEN, Phase 9 closes here. Otherwise, items listed land
next.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
