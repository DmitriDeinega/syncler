# Review 38 — Phase 2: read state + M7 CAS sync

Shipped commit `M11.2` (head). Adds per-message read + archive state to the M7 encrypted user-state blob, wires the inbox UI to show unread visual treatment, marks read on detail-view open, and pulls/pushes through the existing M7 `/v1/state` CAS endpoints.

## What to pressure-test

### Schema + merge

1. **`EncryptedUserState.fromJson` forward-migration.** Pre-V2 blobs (V0 and V1) get `readMessages = []` and `archivedMessages = []`, schemaVersion gets coerced to `SCHEMA_CURRENT`. Verify the migration is non-destructive: an old V1 blob with installedPlugins/dismissedMessages/pluginSettings round-trips through fromJson → toJson with no field loss. The included test covers V1 blob → V2 with read/archived defaulting empty — is that enough, or do we need a stronger compatibility guarantee (e.g., a separate test that an unknown V3 schema_version doesn't crash)?

2. **Merger semantics for read state.** `max(read_at) wins per message_id`. Two cases to validate:
   - Device A marks read at T1, device B marks read at T2 (T2 > T1): merged blob keeps T2. ✓ tested.
   - Device A marks read at T, device B never marks read: A's entry survives in the merged blob. ✓ tested implicitly via the union behavior.
   - Edge case the test doesn't cover: same timestamp, different devices. The current `>=` operator means "local wins" if equal; that's deterministic but biased. Is the bias acceptable, or should we add device-id tiebreaker?

3. **`StateMerger.merge` schema-version handling.** The function takes `maxOf(local, remote)`. Combined with `fromJson` forward-migration: if a device with V2 client pulls from a server that still has a V1 blob (legacy), it forward-migrates to V2 locally. On push back, the blob is V2. But: a V1 client pulling a V2 blob written by a V2 device would NOT understand `read_messages` or `archived_messages` — fromJson on the V1 client doesn't know to skip them; it would ignore them via `optJSONArray` (returns null → empty list), then on push it would write a blob WITHOUT those fields, *destroying read marks*. Is that a problem in practice? V1 client doesn't exist (we own all clients), but as a forward-compat lesson, should the V1 client preserve unknown fields rather than dropping them on write?

### UserStateRepository

4. **Concurrent markRead + push** ([UserStateRepository.kt:88-100](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStateRepository.kt)). `markRead` updates `_state` then calls `push()`. If the user marks two messages read in quick succession, the second `applyLocal` happens between the first `push` and its server response. The second push enters `syncMutex.withLock` after the first releases. Is this safe? Specifically:
   - Second push uses `lastKnownRemoteVersion()` which was just updated by the first push's success — good.
   - But if the first push 409s and the conflict-handler pulls + re-pushes, the second markRead's data is now in the local state but hadn't been written when the conflict-handler did its merge. The conflict-handler's `merged = StateMerger.merge(local = _state.value, remote = ...)` reads the current `_state.value` which INCLUDES the second markRead. So the conflict-handler push includes both reads. ✓
   - But the second `push()` call still runs after the first finishes. It will re-encrypt and re-push the same state. Redundant but not wrong.
   - Worth debouncing? Not in V1, but flag if there's a correctness issue I'm missing.

5. **`pull()` and `push()` are both gated on `syncMutex`** but `applyLocal` is not. `markRead` updates `_state.value` outside the mutex. Is there a race where `pull` finishes its merge and is about to call `applyLocal` while `markRead` ALSO calls `applyLocal` with stale data? The MutableStateFlow assignment is atomic but the read-then-write pattern in markRead (`_state.value.let { ... }.copy(...)`) is NOT atomic. Two concurrent markReads could lose one.
   - Likely fix: synchronize the local-mutation paths too, or use `update {}` on the MutableStateFlow.

6. **Master-key dependence.** Both pull and push no-op if `session.sessionState.value.masterKey == null`. Is that the right behavior, or should the calls raise/return an error so the caller knows the operation didn't happen? Currently `markRead` issues a `push()` that silently no-ops if locked — local state is updated but never syncs.

### UI

7. **Unread dot + bold title.** The dot is 8dp, primary-colored, sits to the left of the row content. Bold title (FontWeight.SemiBold) for unread. When read, dot is invisible but its 8dp slot is preserved so the row doesn't reflow. Trade-off: 8dp of horizontal whitespace permanently consumed for read rows. Is that the right call, or should the layout reflow when read (and accept a one-time jitter when the user marks read)?

8. **Mark-read trigger.** Currently fires on `viewModel.open(itemId)`, which is the click handler that sets `_selectedId` and the detail screen mounts. If the user taps and immediately backs out (<200ms), we still mark read. Is that the right behavior, or should we wait for the detail screen to actually mount + briefly dwell? The review-35 consensus was "mark on detail open" — opening IS the trigger, regardless of dwell.

9. **`readMessageIds` derived flow.** Uses `stateIn(WhileSubscribed(5_000))` with an initial value from `userState.state.value` at construction. Confirm: when the inbox is recomposed (config change, navigation), the InboxViewModel survives, so the flow stays subscribed. When the user logs out and back in, the new InboxViewModel re-reads from the current `userState.state` which still has the OLD user's read state (UserStateRepository is @Singleton). Is logout supposed to clear the user state blob?

### Sync correctness

10. **CAS retry on 409.** [`handleConflictAndRetry`](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStateRepository.kt:153-175) pulls the latest blob, merges, and pushes once. If that retry also 409s, we log and bail. Next foreground tick / next markRead would pull-merge-push again. Is one retry the right policy? Codex's earlier review (#31) on M7.1 might have an opinion on this; check `.triad/31-codex-review.txt` for prior signal.

11. **Encryption format compatibility.** The blob is encrypted as `nonce || ciphertext+tag` via `Aead.encrypt` — same primitive the message body uses. Is that the right format for the server's `encrypted_blob` field? The server treats it as opaque base64, so any format works; the question is whether the encryption format matches what a future V1.5 client would expect.

### What's NOT in scope

- Bottom nav, date buckets, archive UI (Phase 3 — `archivedMessages` schema added now, but no swipe-to-archive / archive screen yet)
- Bundle-by-hash retention (Phase 4)
- Host-side search (Phase 5)
- Mark-unread feature (deliberately not in V1)
- UserStateRepository unit tests (deferred — needs Hilt + Context fixtures)

Skip cosmetic feedback. Pressure-test the merge semantics and the concurrent-write story above all.
