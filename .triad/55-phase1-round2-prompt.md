# Consultation 55 — Phase 1 fix-up round 2 review

Codex consultation 54 stayed RED on RED #2 (legacy migration ownership)
despite the session-gate + per-user flag added in 274557f. Codex's
prescribed fix landed in commit `20326df`:

- `PairedSenderStore.migratePhase1Owned(ownedPairingIds: Set<String>)`
  is now an explicit interface method. The store no longer auto-fires
  migration from `init`.
- `AuthRepository.login` calls `api.listPairings()` after successful
  authenticate, takes the active (non-revoked) pairingIds, and
  forwards them to the store.
- Filtering against current synced state moved INSIDE the
  `mutateAndPush` transform (closes consultation 53 YELLOW #4 stale
  snapshot concern properly this time).

Review the diff `git diff 274557f..20326df`. Confirm the
ownership-proof model fully closes RED #2 across the relevant
threat scenarios, or flag residuals.

## Threat scenarios to verify closed

1. **Multi-user shared device — base case.** User A has legacy
   prefs on the device. A logs out → device prefs wiped for user
   state, BUT legacy prefs survive (device-scoped, not user-scoped).
   User B logs in. Confirm B's `migratePhase1Owned` is called with
   B's server-side pairings only (not A's). B's listPairings result
   excludes A's pairingIds entirely, so legacy entries are filtered
   out before the synced-state push.

2. **Single-user warm start.** User logs in. listPairings returns
   the user's pairings (which match the legacy entries they themselves
   created pre-Phase-1). All legacy entries are imported. Confirm
   the per-user flag is set so subsequent logins no-op.

3. **Pre-Phase-1 device with no pairings.** Legacy prefs is empty.
   listPairings returns either empty or the user's server-side
   pairings (which can't match anything in empty legacy). Confirm
   migration is still effectively no-op AND `phase1MigrationDoneAt`
   is set so we don't re-try every login.

4. **Network failure during listPairings.** AuthRepository's
   `runCatching` swallows the exception. The store's
   `migratePhase1Owned` is never called. `phase1MigrationDoneAt`
   stays null. Next successful login retries. Confirm this retry
   path is idempotent and doesn't accumulate.

5. **Concurrent re-merge race (Codex 53 YELLOW #4 fully closed?).**
   `migratePhase1Owned` reads `mutator.state.value.phase1MigrationDoneAt`
   outside the transform to short-circuit pointless work, but the
   actual filtering + write happens INSIDE the transform. Confirm
   the transform's re-check (`if (current.phase1MigrationDoneAt != null) return current`)
   correctly absorbs a concurrent merge that set the flag elsewhere.

6. **Legacy entry whose pairingId is server-owned by a different user.**
   Edge case: user A revokes a pairing on their account → A's
   listPairings returns it with `revokedAt` set → A's filter
   excludes it (we filter on `revokedAt == null`). User B logs in
   later — listPairings for B doesn't contain that pairingId at all.
   Either way A's old pairingId never re-imports into B. Confirm.

## Diff to review

```
android/core/auth/build.gradle.kts                                    (+ timber dep)
android/core/auth/src/main/kotlin/.../AuthRepository.kt               (+ pairedSenderStore + listPairings + migratePhase1Owned call)
android/core/auth/src/test/kotlin/.../AuthRepositoryTest.kt           (+ FakePairedSenderStore + new test + constructor updates)
android/core/storage/src/main/kotlin/.../PairedSenderStore.kt         (- auto-trigger, + migratePhase1Owned method)
```

## Output

Per reviewer, independently:

1. Per-scenario (1-6): green / yellow / red.
2. Any new concerns introduced by this round 2.
3. Overall: green-light Phase 1 (final?) / hold for more fix-ups.

If both reviewers green, Phase 2 (SSE event stream + remove polling)
starts immediately.
