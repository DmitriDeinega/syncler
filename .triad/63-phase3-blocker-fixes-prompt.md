# Consultation 63 — Phase 3 blocker-fix re-review

Consultation 62 ended with the reviewers split:
- **Gemini**: all-GREEN unified Phase 3 landing.
- **Codex**: all-RED, 7 concrete file:line blockers, recommended split.

The 7 Codex blockers (treated as authoritative on substance) have
been fixed in the working tree. **Nothing is committed yet** — the
diff is still `git diff a2db9cb..HEAD` plus untracked files.
Android `./gradlew assembleDebug` is green. Server imports cleanly.

## Per-blocker fixes applied

### Phase 3a — Blocker #1: TemplateActionRunner auth-token leak (SECURITY)
`TemplateActionRunner` now builds its own unauthenticated
`OkHttpClient` instead of injecting the `:core:network` singleton,
which would have attached the user's Syncler JWT to every plugin
endpoint POST via the `AuthTokenProvider` interceptor at
`NetworkModule.kt:33`. Mirrors the `NetworkBridge` pattern in
`:plugin-host`. Also added an HTTPS-required scheme check (debug
allows http for LAN dev) so a malformed manifest with an `http://`
action endpoint can't fall through to cleartext on release.
File: `android/feature/inbox/.../TemplateCard.kt:181+`.

### Phase 3b — Blockers #2 + #3 + #4: cards.py upsert hardening
`server/app/services/cards.py:upsert_live_card` now:
- requires an active `(sender_id, user_id)` pairing (mirrors
  `services/messages.py:_pairing`); new `PairingMissingError`.
- requires `Plugin.revoked_at IS NULL` in the plugin lookup (so
  revoked plugins can't squat live-card slots).
- rejects `expires_at <= now` and clamps it against
  `MAX_TTL = timedelta(hours=48)` (server-enforced cap, not
  caller-controlled). New `ExpiredEnvelopeError`.

### Phase 3b — Blocker #5: cross-user delete vulnerability (SECURITY)
`LiveCardDeleteRequest` now requires `user_id`. The canonical
envelope signs `(sender_id, user_id, card_key)` via
`assemble_envelope` (matches upsert canonicalization). The router
deletes by `(sender_id, user_id, card_key)` directly; no more
unscoped lookup. SDK `delete_card(user_id=..., card_key=...)`
mirrors the new shape and uses `_canon_uuid(user_id)` for
canonical form.

### Side-fix discovered while wiring #5: upsert envelope signature mismatch
Server's `_build_upsert_envelope_bytes` was casting
`sequence_number` to `str` while the Python SDK signs the field as
an `int`. Canonical JSON would have emitted
`"sequence_number":"42"` server-side and `"sequence_number":42`
SDK-side, breaking every upsert with 401. Fixed by keeping
`payload.sequence_number` as int in the server canonical envelope.

### Phase 3c — Blocker #6: Archive + Sender views ignored mute
`InboxScreen.kt:425,431` filtered from the raw `items` list rather
than `nonMuted`, so muted senders re-surfaced in Archive and in any
Sender-scoped view. Both branches now consume `nonMuted`. Muted
senders are now invisible across All / Unread / Live / Archive /
Sender.

### Phase 3c — Blocker #7: settings-sheet revoke was local-only
`InboxRepository.revokeSender(senderId)` previously called
`pairedSenderStore.remove(pairingId)` directly, leaving the
server-side `Pairing.revoked_at` untouched. It now mirrors
`PairingRepository.revoke()` — calls `api.revokePairing(pairingId)`
first, then removes locally only on success. Per-pairing failure is
logged and the loop continues; the user can retry from the sheet.

## Items Codex 62 confirmed OK (kept unchanged)
- `card_type` / `card_key_path` persistence in
  `services/plugins.py:151`.
- `/v1/messages/inbox` shape: server returns `items`, Android reads
  `items`.
- Dismiss filter LEFT JOIN logic in `inbox_for_device`.
- Live-card AAD binding on Android (`Aad.kt:62`) and
  max-sequence client merge.
- SSE `card.upsert` / `card.delete` wiring on both sides.

## What I need from each reviewer

1. **Per-blocker confirmation**. For each of #1–#7 plus the
   side-fix, confirm GREEN or flag a residual gap. File + line
   refs if anything's still wrong.

2. **Phase 3c MuteStore semantics (Codex 61 YELLOW, deferred)**.
   The plan's `Set<String>` plus union merge means "unmute
   everywhere" can resurrect a removed value under stale/offline
   merge. The local-override layer in `MuteStore` lets a user
   unmute on the local device irrespective of synced state, so the
   user-facing experience is fine even if the synced set is
   monotone. Is keeping this as a known V1 tradeoff acceptable, or
   does it block Phase 3c?

3. **Commit strategy — settle the split from consultation 62**.
   Now that the blockers are closed and the tree is green, do you
   recommend split (3a → 3b → 3c) or unified Phase 3 landing?
   Consider: the cross-sub-phase coupling (mute filter consumes
   `nonMuted` which depends on V5 schema; live-card render path
   uses the same dispatch as templates).

4. **Tests required before commit**. List the specific tests that
   must land alongside this commit (or commits). Plan called for:
   - Server: template publish goldens, malformed manifests,
     dismiss filter, live-card upsert/delete, revoked-plugin
     rejection, pairing-missing rejection, TTL cap, duplicate
     card-key delete targeting.
   - Android: `resolveJsonPath`, `TemplateCard` action callback,
     template action does NOT carry Authorization, dismiss SSE
     refresh, live-card max-sequence merge, mute filtering across
     all views.
   - Storage: V0–V5 `EncryptedUserState` round trip, `StateMerger`
     muted union, `MuteStore` effective set (synced + local mute
     − local unmute).

5. **Anything new** introduced by the blocker fixes (regressions,
   incomplete migrations, new race windows).

## Diff to review
`git diff a2db9cb..HEAD` plus the untracked files:
- `server/alembic/versions/0006_plugin_renderer_template.py`
- `server/alembic/versions/0007_live_cards.py`
- `server/app/routers/cards.py`
- `server/app/services/cards.py`
- `android/feature/inbox/.../TemplateCard.kt`
- `android/feature/inbox/.../PluginSettingsSheet.kt`
- `android/core/storage/.../MuteStore.kt`
- `docs/ROADMAP.md`

## Output

Per reviewer:
1. Per-blocker green / yellow / red.
2. Commit-strategy: split / unified, one-sentence rationale.
3. Test list (specific names / scenarios).
4. Any new concerns.
5. Overall: ready to commit / ready after tests / hold.

If both reviewers GREEN with matching commit-strategy, I'll write
the tests, fire one more triad pass on the tests, then commit.
