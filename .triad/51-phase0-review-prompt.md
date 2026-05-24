# Consultation 51 — Phase 0 code review

Phase 0 of the agreed plan (see `.triad/50-agreement-and-plan.md`)
is implemented at HEAD (commit `9819df0`). Review the diff against
the phase-0 scope from the plan. Independent positions per reviewer.

## Phase 0 scope recap

Per `.triad/50-agreement-and-plan.md`:

1. **0a — CAS dirty-flag bug fix + regression test.** Codex flagged
   in consultation 47: `push()` could clear the dirty flag after a
   conflict-retry failure, losing pending changes.

2. **0b — Expose state-mutation interface from `:core:storage`.**
   Required so the Phase 1 synced-pairing work in `:core:storage`
   can mutate the encrypted user-state blob owned by `:feature:inbox`.

3. **0 main — Device-bound JWT + revocation enforcement.** JWTs
   include `did` claim; sensitive routes reject tokens without it or
   with revoked device. Login + enroll flow swaps the bootstrap
   token for a device-bound one. Migration: pre-Phase-0 clients get
   401 with `WWW-Authenticate: device_required`.

## Diff to review

`git show 9819df0` or `git diff 9819df0^ 9819df0`.

Files changed:

```
android/core/auth/src/main/kotlin/app/syncler/core/auth/AuthRepository.kt
android/core/auth/src/main/kotlin/app/syncler/core/auth/Session.kt
android/core/auth/src/test/kotlin/app/syncler/core/auth/AuthRepositoryTest.kt
android/core/network/src/main/kotlin/app/syncler/core/network/SynclerApi.kt
android/core/storage/src/main/kotlin/app/syncler/core/storage/UserStateMutator.kt (new)
android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/InboxRepository.kt
android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStatePrefs.kt (new)
android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStateRepository.kt
android/feature/inbox/src/test/kotlin/app/syncler/feature/inbox/UserStateRepositoryPushTest.kt (new)
server/app/auth.py
server/app/routers/devices.py
server/app/routers/messages.py
server/app/routers/state.py
server/app/schemas.py
server/tests/test_devices.py
server/tests/test_messages.py
```

## What to check

**Per phase sub-task:**

1. CAS dirty-flag fix:
   - Is the throw on retry-failure actually catchable by push()'s
     outer runCatching? Verify the call stack.
   - The new regression test covers permanent-conflict; is there a
     case it misses (e.g. retry succeeds after pull-merge but
     newStateVersion is still less than expected)?
   - Did the refactor to `UserStatePrefs` interface preserve every
     prefs operation? (Diff the old/new prefs touches.)

2. `UserStateMutator`:
   - Is the interface stable enough for Phase 1 to consume?
     Adding fields later breaks ABI.
   - Hilt @Binds wiring correct? Does `:core:storage` depend on
     Hilt enough to consume the binding?
   - `mutateAndPush` combines mutate + push. Are there callers
     downstream that would want them separate?

3. Device-bound JWT:
   - Pre-Phase-0 token rejection path: any sensitive endpoint left
     using `current_user` instead of `current_auth_context`?
     Specifically check senders, pairing, plugins routes.
   - `current_auth_context` makes an extra DB query per request to
     verify the device. Acceptable overhead? Cacheable?
   - The bootstrap token (from /v1/auth/login) is still valid for
     `/v1/auth/devices/enroll`. Is this the right scope? Could an
     attacker use a leaked bootstrap token to enroll a new device
     under a victim's account?
   - Token swap on Android (Session.replaceToken): any race with
     in-flight requests that started with the bootstrap token?
   - Test for the bootstrap-token rejection + device-revoked
     rejection paths — sufficient? Any other paths?

**Cross-cutting:**

- Does the `:core:storage` module now have any new transitive
  dependencies it didn't before?
- Any new lints / warnings introduced?
- Any code path that calls `api.inbox(...)` or `api.dismissMessage(...)`
  with the now-removed `device_id` parameter that we missed?
- Anything that depends on `/v1/messages/inbox?device_id=...` query
  param (third-party tools, CI, integration tests)?
- The cleanup of `assert_device_belongs_to_user` — is it referenced
  anywhere else (sender routes, plugin routes, future code paths)?

## Output

Per reviewer, independently:

1. Per-task verdict: green / yellow (specific fix) / red (blocker).
2. List any line-level concerns with `path:line` references.
3. Overall recommendation: land as-is / specific fix-ups before
   landing / hold for design rework.

Keep it focused — Phase 0 is foundation work, not new product
surface. Phase 1 starts as soon as we agree this is solid.
