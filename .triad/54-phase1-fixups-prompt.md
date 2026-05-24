# Consultation 54 — Phase 1 fix-up review (greenlight check)

Phase 1 base shipped in 27cefa3 + c5ba03e. Gemini greenlit in
consultation 53; Codex flagged 2 RED + 4 YELLOW. Fix-ups for all six
landed in commit `274557f` on top of the Phase 1 base.

Review the fix-up diff (`git diff c5ba03e..274557f`) against each
finding from consultation 53. Confirm full closure or flag residual
gaps. Both reviewers, please answer independently.

## Codex consultation 53 findings → fix-up mapping

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| 1 | RED | Cold-start refresh can advance `lastSince` past undecryptable messages (projection empty + migration async) | (a) `SyncedPairedSenderStore.activeFlow` now initialises synchronously from `mutator.state.value` so the very first refresh sees the right pairings. (b) `InboxRepository.refresh()` tracks `sawMissingPairing`; when true, it does NOT advance `lastSince` — the next refresh re-fetches the batch once pairings have synced. |
| 2 | RED | Legacy migration not session/user-gated; multi-user shared device could push user A's legacy entries into user B's account | New `UserStateMutator.isUnlocked: StateFlow<Boolean>` (`UserStateRepository` mirrors `session.sessionState.isUnlocked`). New `EncryptedUserState.phase1MigrationDoneAt: String?` field — per-user (the state IS user-scoped), sticky-once-set in the merger. `SyncedPairedSenderStore` observes `isUnlocked` and only migrates when (a) a user is logged in AND (b) that user's `phase1MigrationDoneAt` is null. The flag is set in the same `mutateAndPush` write as the legacy import. |
| 3 | YELLOW | Lexical sort on ISO-8601 timestamps in `SyncedPairedSenderStore` (×2) + `InboxRepository.decryptWithAnyKey` | Extracted `compareIsoTimestamps(a, b)` to a public helper (`Timestamps.kt`). All three sites — plus `StateMerger`, which had its own copy — now route through it. |
| 4 | YELLOW | Migration filters legacy by `pairingId` only; could re-add a sender that already has a newer manual pairing | `migrateLegacyEntriesIfNeeded` now also excludes legacy entries whose `senderId` matches an active (non-tombstoned) synced pairing. |
| 5 | YELLOW | `add()` defensively dropped any same-pairingId entry, including a tombstone, breaking tombstone monotonicity | Filter scoped to ACTIVE same-pairingId entries only. Tombstones with the same pairingId are preserved. |
| 6 | YELLOW | Production store not directly tested (uses a JVM logic duplicate) | Acknowledged. Robolectric/instrumented coverage of the real EncryptedSharedPreferences + Hilt-injected paths is queued for a follow-up test-infrastructure pass. The logic-duplicate test exercises every semantic the production class has. |

Also folded in from Gemini consultation 53: lexical-timestamp YELLOW
is the same fix as Codex's #3 above.

## Diff to review

`git diff c5ba03e..274557f` covers:

```
android/core/storage/src/main/kotlin/.../EncryptedUserState.kt        (+ phase1MigrationDoneAt field)
android/core/storage/src/main/kotlin/.../PairedSenderStore.kt         (session-gated migration + sync init + tombstone fix + sender filter)
android/core/storage/src/main/kotlin/.../StateMerger.kt               (phase1MigrationDoneAt sticky merge + uses shared timestamp helper)
android/core/storage/src/main/kotlin/.../Timestamps.kt                (new shared helper)
android/core/storage/src/main/kotlin/.../UserStateMutator.kt          (+ isUnlocked)
android/core/storage/src/test/kotlin/.../StateMergerTest.kt           (+ phase1MigrationDoneAt tests)
android/core/storage/src/test/kotlin/.../SyncedPairedSenderStoreTest.kt (FakeUserStateMutator updated for isUnlocked)
android/feature/inbox/src/main/kotlin/.../InboxRepository.kt          (missing-pairing skip + timestamp helper)
android/feature/inbox/src/main/kotlin/.../UserStateRepository.kt      (implements isUnlocked from session.sessionState)
```

## What to verify

For each numbered finding from consultation 53, confirm the fix
either:
- Fully addresses the concern, OR
- Has a residual issue (be specific).

Additional pressure-tests on the changes themselves:

- **`isUnlocked` propagation:** UserStateRepository computes it via
  `session.sessionState.map { it.isUnlocked }.stateIn(...)` with
  `SharingStarted.Eagerly`. Is the initial value (`session.sessionState.value.isUnlocked`)
  consistent with what observers will receive on first emission?
  Any race between the eager subscription and downstream collectors?
- **`phase1MigrationDoneAt` merge semantics:** "earlier non-null wins"
  is sticky-monotone. Verify the merger never returns null once any
  side has it set. Verify the chosen winner is reproducible across
  re-merges (idempotent).
- **`sawMissingPairing` cursor hold:** if every refresh batch
  contains messages from a sender the user genuinely doesn't have
  paired (e.g. unknown sender pushing spam), the cursor would
  permanently stall. Is this a real concern, or does the server-side
  pairing-required check prevent unknown-sender messages from
  landing in the inbox in the first place?
- **First-launch migration timing:** the `isUnlocked` flow fires
  once the user logs in. If the user is already logged in when the
  app starts (warm start with stored session), does `isUnlocked`
  emit `true` synchronously, triggering migration on the first
  emission? Verify there's no race between SyncedPairedSenderStore
  construction and UserStateRepository construction.
- **Tombstone fix in add() (YELLOW #5):** does the filter
  `it.pairingId == new.id && it.removedAt == null` correctly
  preserve a tombstoned same-pairingId entry while still allowing
  retry-add (same pairingId, still active) to be idempotent?

## Output

Per reviewer, independently:

1. Per-finding (1-6): green / yellow (specific gap) / red (blocker).
2. Any new concerns introduced by the fix-ups.
3. Overall: green-light Phase 1 to land / specific blocker before
   Phase 2.

If both green, Phase 2 (SSE event stream + remove polling) starts
immediately.
