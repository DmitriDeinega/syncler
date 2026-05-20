# Review of M6.1 (review fix-ups for M6)

Commit `5a0b9e0`. Applied 10 fixes per `.triad/29-codex-review.txt` + `.triad/29-gemini-review-v2.txt`. Confirm fixes are correct + no new bugs.

## What changed

**Server**
- `/v1/pairing/initiate`: IP rate-limit pre-auth, per-sender post-auth (`request.state.sender_id` set AFTER signature verify)
- `/v1/pairing/preview?token=<b64>`: new non-consuming endpoint; returns sender identity (fingerprint, name, name_hash, public_key, expires_at)
- `complete_pairing`: atomic `UPDATE ... WHERE consumed_at IS NULL AND expires_at > now RETURNING` (race-safe)
- `migration 0003_pairing_partial_unique`: drop UNIQUE(user, sender), add partial unique index on (user, sender) WHERE revoked_at IS NULL (re-pair-after-revoke works)
- Broker URL uses URL-safe base64; both URL-safe and standard accepted on /preview + /complete

**Android**
- `PairedSenderStore` switched from `|` delimiter to per-field JSONObject; per-record updates (no full rewrite)
- `PairingRepository`: 2-phase `preview` -> user confirms -> `confirm`. confirm() includes preview/complete identity-match check; revokes server pairing on mismatch
- `PairingViewModel`: 5-state flow (Idle / PreviewLoading / PreviewReady / Confirming / Success / Error) with real Cancel
- `MainActivity` + `MainViewModel`: TopLevelScreen enum; PairingScreen reachable from inbox via "Pair a sender" button
- `HostPluginMessagePipeline` replaces stub: looks up PairedSender by sender_id, rejects unpaired senders (I4 layer 4 scaffold; full envelope verification needs JWT user_id which lands in M7)
- `MessageInboxItemDto`: gained expires_at field
- `pairing` module: added hilt-navigation-compose for hiltViewModel()

## Verify

1. Preview/complete identity-match: does the Android confirm() reject correctly if server sends mismatched identity?
2. Atomic complete: any race window remaining?
3. Migration 0003 downgrade path works?
4. URL-safe vs standard base64 acceptance — any encoding edge case missed?
5. PairedSenderStore JSON encoding: any field type collision (e.g., binary bytes inadvertently lossy through JSONObject)?
6. MainViewModel enum migration: any UI path orphaned?
7. HostPluginMessagePipeline: I4 layer 4 scaffolding adequate as a stop-gap? (Full envelope sig verify deferred to M7.)
8. /pairing/preview not auth-required by design; any abuse vector? (Token brute-force in TTL window remains negligible at 2^256.)

## Output

Same shape — agreements with the 10 fixes, new bugs introduced, show-stoppers for M7, ready-for-M7 yes/no.
