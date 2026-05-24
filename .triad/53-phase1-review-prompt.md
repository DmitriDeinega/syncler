# Consultation 53 — Phase 1 code review (synced pairing)

Phase 1 of the agreed plan (see `.triad/50-agreement-and-plan.md`,
expanded for the disagreements resolved in consultation 49) is
implemented across two commits on top of Phase 0:

```
27cefa3 phase 1 (schema): synced pairing — EncryptedUserState V4 + merger
c5ba03e phase 1 (integration): synced PairedSenderStore + multi-key decryption
```

Review the diff against the Phase 1 scope. Independent positions per
reviewer.

## Phase 1 scope recap

From `.triad/50-agreement-and-plan.md` + consultations 47/49:

- **Schema bump V3 → V4.** Add `pairedSenders: List<PairedSenderEntry>`
  to `EncryptedUserState`. Forward-migration handles V0..V3.
- **StateMerger.** Per-`pairingId` merge. Tombstone (`removedAt` set)
  wins. Otherwise oldest `firstPairedAt` wins on add conflict.
- **`PairedSenderStore` becomes a projection of synced state.**
  Implementation backed by `UserStateMutator.mutateAndPush`. Public
  interface unchanged.
- **First-launch migration.** Push existing local entries from the
  legacy `EncryptedSharedPreferences` into the synced blob with
  `source: "migration"`. One-shot flag prevents re-running.
- **Multi-key decryption.** `InboxRepository.refresh()` tries each
  active pairing key for a `senderId` until one decrypts (handles
  the pre-sync transition window where each device paired separately).
- **Re-pair tombstoning.** Adding a new pairing for an existing
  `senderId` tombstones the old one in the same atomic write.

Out of scope for Phase 1 (next phases):
- SSE event stream (Phase 2).
- Templates / live cards / settings sheet (Phase 3).
- Migration banner UI (will land alongside Phase 3c host-owned settings).
- Mute settings (Phase 3c).

## Diff to review

`git diff 119422d..c5ba03e` (Phase 0 tip → Phase 1 tip).

Files changed:

```
android/core/storage/build.gradle.kts                                 (+ coroutines-test test dep)
android/core/storage/src/main/kotlin/.../EncryptedUserState.kt        (schema V4 + PairedSenderEntry)
android/core/storage/src/main/kotlin/.../StateMerger.kt               (pairedSenders merge)
android/core/storage/src/main/kotlin/.../PairedSenderStore.kt         (new SyncedPairedSenderStore impl)
android/core/storage/src/main/kotlin/.../StorageModule.kt             (Hilt binding swap)
android/core/storage/src/test/kotlin/.../StateMergerTest.kt           (+ paired sender merge tests)
android/core/storage/src/test/kotlin/.../SyncedPairedSenderStoreTest.kt (new)
android/feature/inbox/src/main/kotlin/.../InboxRepository.kt          (multi-key decryption)
```

## What to pressure-test

**Schema + merger (commit 27cefa3):**

1. The `source` field defaults to `"manual"` on parse — does the
   `source: "migration"` discriminator survive merge correctly when
   the local has a `"migration"` entry and the remote doesn't have
   the entry at all (so the migrated copy is what propagates)? Same
   sender across two devices' migrations: does one side's `"migration"`
   beat the other's, or do we deduplicate by pairingId before that
   matters?
2. Forward-migration V0..V3 → V4: a V3 blob has no `paired_senders`
   field; new clients parse it as empty list and emit V4. Is the
   pre-existing V3 forward-migration test (the V2→V3 example in
   `StateMergerTest`) sufficient coverage, or should we add a V3→V4
   explicit?
3. Tombstone GC was deferred indefinitely (consultation 49). Confirm
   this is acceptable for Phase 1 — the entries are small (~300 bytes
   each) and we don't expect heavy churn.

**Store + integration (commit c5ba03e):**

4. **Re-pair atomic tombstone:** the `add()` impl tombstones existing
   active pairings for the same senderId in the same `mutateAndPush`
   transform. Is the transform actually atomic against concurrent
   writes? `mutateAndPush` serializes through the syncMutex inside
   `UserStateRepository`; verify the re-pair semantics survive a
   concurrent `markRead` or other state mutation interleaving with
   the pairing add.

5. **First-launch migration race:** if the user installs Phase 1 for
   the first time, immediately logs in, and the local prefs have
   entries from a pre-Phase-1 install, `migrateLegacyEntries` runs
   in the background (`scope.launch` from `init`). What if the
   user revokes one of those pairings before the migration push
   completes? Could the migration re-add the revoked entry?

6. **Migration flag location:** the `MIGRATION_FLAG` lives in the
   legacy `EncryptedSharedPreferences` (the same store that holds
   the pre-Phase-1 entries). Is this the right location, or should
   it live in the synced state (so a user installing on a brand-new
   device after losing their old one doesn't re-migrate from an
   empty legacy store and skip migration that another device might
   still need)?

7. **Multi-key decryption ordering:** `decryptWithAnyKey` tries
   candidates by `firstPairedAt` descending. Is "newest first"
   correct, or should the sender's most recent pairing always be
   tried first by some other criterion? Specifically: what if the
   sender re-paired but is still encrypting with the old key
   transiently — does newest-first cause a brief decryption failure
   window?

8. **`activePairingsForSender` semantics:** this exposes ALL active
   pairings (potentially multiple during transition). Are there
   other callers that should be aware they may see more than one
   entry? Anything in `PairingRepository`, `PairingScreen`, or
   `Senders` tab that assumes one-per-sender?

9. **Tombstone resurrection:** the test
   `paired sender tombstone wins over active entry` covers the
   merger side. Does the integration side (SyncedPairedSenderStore)
   correctly avoid re-adding tombstoned entries? Specifically: if
   the synced state has a tombstoned entry and a client calls
   `add()` with a fresh PairedSender that happens to share a
   pairingId (impossible in practice with fresh UUIDs, but
   defensively), what happens?

10. **Legacy local store:** the legacy `EncryptedSharedPreferences`
    store (`syncler.paired_senders.enc`) is still used for the
    migration source and the flag — should we wipe it after
    migration succeeds, or leave it as a backup?

**Cross-cutting:**

11. **Test coverage:** unit tests use a `TestPairedSenderStore` that
    duplicates the production semantics in a pure-JVM environment
    (no Robolectric). Is this acceptable for now, or should we add
    Robolectric to exercise the real `SyncedPairedSenderStore`?

12. **Any latent bugs the Phase 1 changes might surface?** Specifically
    in `PairingRepository.confirm` (it now writes to the synced state
    via `pairedSenderStore.add` — does its failure handling cover the
    new "push to server" failure mode in addition to the local-write
    failure mode it knew about?).

## Output

Per reviewer, independently:

1. Per-task (1-12): green / yellow (specific gap) / red (blocker).
2. Any line-level concerns (`path:line` references).
3. Overall: land Phase 1 as-is / specific fix-ups before landing /
   hold for design rework.

If both reviewers green-light, Phase 2 (SSE event stream + remove
polling) starts immediately.
