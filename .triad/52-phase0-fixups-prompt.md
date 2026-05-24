# Consultation 52 — Phase 0 fix-up review (greenlight check)

After consultation 51, Gemini greenlit Phase 0 as-is; Codex flagged 3
RED + 3 YELLOW. Fix-ups for all six landed in commit `7d268e0` on
top of Phase 0 base (`9819df0`).

Review the fix-up diff (`git diff 9819df0 7d268e0`) against Codex's
findings. Verify each item is properly addressed, or flag if any are
incomplete. Both reviewers, please answer independently.

## Codex consultation 51 findings → fix-up mapping

| # | Sev | Finding | Fix |
|---|-----|---------|-----|
| 1 | RED | Revoked device-bound JWT can call enroll and regain access | New `bootstrap_only_user` dependency on `/v1/auth/devices/enroll` rejects tokens with `did` claim (401 `bootstrap_required`). New test `test_enroll_rejects_device_bound_token`. |
| 2 | RED | Pairing complete/list/revoke + account-delete still use `current_user` | All four switched to `current_auth_context`. |
| 3 | RED | Hilt `Clock` injection — Dagger doesn't honor Kotlin defaults; `:app:assembleDebug` fails | `@Provides Clock = systemUTC()` added to `UserStatePrefsModule`. `:app:assembleDebug` now succeeds. |
| 4 | YELLOW | AuthRepository publishes unlocked Session with bootstrap token before enroll swaps it (race) | enrollDevice now takes `@Header("Authorization")` explicit param. AuthInterceptor honors pre-set Authorization. AuthRepository.login does enroll BEFORE session.authenticate. Single atomic locked→unlocked transition with device-bound token already installed. Session.replaceToken removed. |
| 5 | YELLOW | CAS retry control flow could double-retry (handleConflictAndRetry throws HttpException; attemptPush catches HttpException with code 409 and calls handleConflictAndRetry again) | attemptPush linearized: one initial PUT + one optional retry, no recursion. handleConflictAndRetry collapsed into attemptPush itself. doPut() helper extracts the encrypt + PUT pair. |
| 6 | YELLOW | Stale tests still send bootstrap tokens to sensitive routes | test_state, test_auth, test_integration_auth_devices, test_pairing updated to use device-bound tokens from enroll response. Tests that need to enroll multiple devices on the same user now use `bootstrap_header` for those enroll calls (since enroll rejects device-bound). Two new tests for the bootstrap_required + device_revoked rejection paths. |

Plus cleanup (Codex called it out separately):
- Unused `SessionStore` import in `UserStateRepositoryPushTest.kt` — removed.
- Now-unused `assert_device_belongs_to_user` import in `messages.py` — removed.

## Diff to review

`git diff 9819df0 7d268e0` covers:

```
android/core/auth/src/main/kotlin/app/syncler/core/auth/AuthRepository.kt
android/core/auth/src/main/kotlin/app/syncler/core/auth/Session.kt
android/core/auth/src/test/kotlin/app/syncler/core/auth/AuthRepositoryTest.kt
android/core/network/src/main/kotlin/app/syncler/core/network/NetworkModule.kt
android/core/network/src/main/kotlin/app/syncler/core/network/SynclerApi.kt
android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStatePrefs.kt
android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStateRepository.kt
android/feature/inbox/src/test/kotlin/app/syncler/feature/inbox/UserStateRepositoryPushTest.kt
server/app/auth.py
server/app/routers/auth.py
server/app/routers/devices.py
server/app/routers/pairing.py
server/tests/test_auth.py
server/tests/test_devices.py
server/tests/test_integration_auth_devices.py
server/tests/test_pairing.py
server/tests/test_state.py
```

## What to verify

For each numbered Codex finding from consultation 51, confirm the fix
either:
- Fully addresses the concern, OR
- Has a residual issue (be specific about what's left).

Additional checks:

- **enroll auth path:** is there any path where a device-bound token
  can still reach `bootstrap_only_user`-protected enroll? (Look for
  forgotten import sites, custom auth bypasses, etc.)
- **AuthInterceptor pre-set Authorization passthrough:** does the
  interceptor correctly skip Session-provided token when the request
  already has an Authorization header? Could a malicious caller in
  the same process inject their own header to bypass Session auth?
- **AuthRepository enroll-before-authenticate:** is the bootstrap
  token guaranteed to be zeroed/dropped if enroll fails? Currently
  the flow drops the masterKey but does it drop the bootstrap?
- **CAS retry linearization:** does the new flow preserve all
  edge cases — locked-session no-op, future-schema refusal, retry
  succeeds, retry fails (dirty stays true)?
- **Session.replaceToken removal:** any leftover callsite or test
  reference?
- **Test coverage:** does `test_enroll_rejects_device_bound_token`
  actually verify the bootstrap_required header? Are there other
  cases (e.g. expired device-bound JWT) that should be tested?

## Output

Per reviewer, independently:
1. Per-finding (1-6): green / yellow (specific gap) / red (blocker).
2. Any new concerns introduced by the fix-ups.
3. Overall: green-light Phase 0 to land, or specific blocker.

If both green, Phase 1 (synced pairing) starts immediately.
