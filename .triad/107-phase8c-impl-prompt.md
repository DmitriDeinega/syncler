=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 107 — Phase 8c Android UX impl (password_rewrap)

Phase 8b shipped at `16a873a` (dual-GREEN after rounds 104-106).
This consultation reviews the Android client side. The spec §10
contract (`docs/crypto-spec.md`) is the source of truth — same
spec you both already approved.

## Scope explicitly NOT in this phase

- **§10.9 AAD-binding** for `users.encrypted_master_key`,
  `encrypted_user_state.encrypted_blob`, `pairings.encrypted_state`.
  The existing wrap/unwrap + state-blob encryption paths pre-date
  Phase 8 and don't bind the spec'd AAD. Adding it would touch
  signup, login, state encryption, and inbox decryption — a
  cross-cutting refactor deferred to **Phase 8d**.
- **`root_hygiene_rotation` + `root_compromise_rotation`**. These
  need a `GET /v1/pairings/{id}/state` server endpoint (the client
  has to fetch every pairing's encrypted_state, decrypt, and re-
  encrypt under the new MK). That endpoint doesn't exist in
  Phase 8b. Deferred to **Phase 8e**.

Phase 8c ships:
- The wire-format pieces every endpoint needs (key_generation_observed,
  X-Syncler-Client-Min-Phase header).
- §10.10 downgrade defense at login.
- The user-facing `password_rewrap` "Change password" flow.

## What's in this commit

### Network layer (server/app/network)

- `SynclerApi`:
  - `LoginResponse.keyGeneration` (default 1 for pre-Phase-8
    server responses).
  - `StateGetResponseDto.keyGeneration` (default 1).
  - `StatePutRequestDto.keyGenerationObserved: Int?` (nullable so
    legacy callers still compile).
  - `StatePutResponseDto.keyGeneration`.
  - `PairingCompleteRequestDto.keyGenerationObserved: Int?`.
  - New endpoints: `rotateMasterKeyChallenge()` →
    `RotationChallengeResponseDto`, `rotateMasterKey(body)` →
    `Response<RotateMasterKeyResponseDto>`.
  - New DTOs: `RotateMasterKeyRequestDto` (with all 3 reasons'
    fields nullable; server validates the per-mode combinations),
    `RotationUserStateBodyDto`, `RotationPairingEntryDto`,
    `RotateMasterKeyResponseDto`, `RotationUserStateResultDto`,
    `RotationPairingResultDto`.
  - 426/409 detail-body DTOs for typed error handling
    (`KeyGenerationMismatch*`, `UpgradeRequired*`).
- `NetworkModule`: the OkHttp auth interceptor now stamps
  `X-Syncler-Client-Min-Phase: 8` on EVERY outbound request (per
  §10.11 — the server uses this for the mixed-client gate).

### Storage (core/storage)

- `SecurePrefs` gained `getInt` / `putInt`.

### Auth (core/auth)

- `KeyGenerationStore` interface + `SecurePrefsKeyGenerationStore`
  impl. Per-user-id `highest_key_generation_seen` (§10.10) —
  scoped by user id so logging into account B after A doesn't
  false-positive a downgrade.
- `SessionState` gains `keyGeneration`, `authSalt`,
  `encryptedMasterKey`. The latter two are kept in memory so
  "Change password" can re-derive Argon2 + re-wrap WITHOUT
  bouncing through pre-login → login (which would issue a stray
  token and confuse session observers).
- `Session.authenticate(token, masterKey, keyGeneration,
  authSalt?, encryptedMasterKey?)`.
- `Session.updateAfterRotation(newMasterKey, newKeyGeneration,
  newAuthSalt?, newEncryptedMasterKey?)`. Copies the new bytes
  BEFORE zeroing the previous arrays — `password_rewrap` passes
  the same `previous.masterKey` reference for `newMasterKey` and
  zeroing first would also zero the bytes about to be copied.
- `Session.currentUserId()` now uses `java.util.Base64` +
  defensive regex (no `org.json.JSONObject`) so unit tests on
  JVM work the same as the production runtime.
- `AuthRepository.login`:
  - §10.10 downgrade check: `response.keyGeneration <
    highWater` → throw `KeyGenerationDowngradeError`. Done
    BEFORE the MK unwrap (otherwise the client would happily
    operate on a frozen snapshot under the old key).
  - `keyGenerationStore.bump(userId, response.keyGeneration)`
    AFTER the check.
  - `session.authenticate(...)` now passes `keyGeneration`,
    `authSalt`, `encryptedMasterKey`.
- `RotationRepository.rewrapPassword(currentPassword,
  newPassword)`:
  1. Get `RotationChallenge` from server.
  2. Derive Argon2id(currentPassword, currentSalt) →
     currentAuthKey + currentWrapKey.
  3. Local sanity unwrap-test on the cached
     `encryptedMasterKey`. If it throws,
     `WrongCurrentPasswordError` — surfaced BEFORE the proof
     hits the server so a typo doesn't burn the server's
     failed-proof rate limit (§10.8 step 4).
  4. Generate a fresh 16-byte newSalt.
  5. Derive Argon2id(newPassword, newSalt) → newAuthKey +
     newWrapKey.
  6. Re-wrap the existing MK under newWrapKey.
  7. POST `/v1/account/rotate-master-key` with
     `reason="password_rewrap"`, `key_generation_observed =
     session.currentKeyGeneration()`,
     `rotation_challenge = ...`,
     `current_password_proof = currentAuthKey`,
     `new_encrypted_master_key = rewrapped`,
     `new_auth_salt = newSalt`,
     `new_auth_key_proof = newAuthKey`.
  8. On success: `keyGenerationStore.bump(...)` +
     `session.updateAfterRotation(...)` with the (same) MK +
     new salt + new wrapped MK.
- Zeroing on every code path: currentKeys.{authKey,
  masterKeyWrapKey}, newKeys.{authKey, masterKeyWrapKey},
  unwrapped MK scratch.

### State + pairing call sites

- `UserStateRepository.doPut` now passes
  `keyGenerationObserved = session.currentKeyGeneration()`.
- `PairingRepository.confirm` takes a `Session` dep and passes
  the same on `pairing/complete`.

### UX (feature/settings)

- `ChangePasswordCard` Composable (drop-in card; embed in
  app/ui/SettingsScreen.kt — done) with backup-or-lose-access
  warning, current+new+confirm password fields, password-mismatch
  validation, mandatory checkbox before submission.
- `SettingsViewModel` translates `WrongCurrentPasswordError`,
  401/409/426/429 into user-facing copy.
- `app/ui/SettingsScreen.kt` embeds the card under the existing
  "Account" section.

### Tests

`core/auth/testDebugUnitTest`: **11 passed** (8 existing +
3 new in `RotationRepositoryTest`):
- `rewrapHappyPathUpdatesSessionAndKeyGenerationStore`
- `rewrapWithWrongCurrentPasswordSurfacesWrongCurrentPasswordError`
- `rewrapServer429IsBubbledUp`
- `rewrapRequiresUnlockedSession`

The happy-path test asserts:
- The request body sent has `reason="password_rewrap"`,
  `keyGenerationObserved=1`, no new_encrypted_user_state, no
  pairings.
- After success, the in-memory MK is byte-identical to before
  (password_rewrap doesn't change it).
- `authSalt` and `encryptedMasterKey` in Session ARE different
  bytes (re-wrap).
- High-water mark in KeyGenerationStore unchanged at 1.

The wrong-password test asserts:
- Local unwrap-test fails.
- `rotateCallBody == null` — the server's failed-proof rate
  limit is NOT incremented for typing errors.

Full Android build is green: `./gradlew assembleDebug` and
`./gradlew :core:auth:testDebugUnitTest :feature:inbox:testDebugUnitTest`
both pass. The one pre-existing JVM failure in
`:core:network:testDebugUnitTest` (`BrokerApiTest` uses
`org.json.JSONObject.keys` which is Android-only) is unrelated
to Phase 8 — confirmed via `git stash` on master.

## Files

Modified:
- `android/core/network/src/main/kotlin/app/syncler/core/network/SynclerApi.kt`
- `android/core/network/src/main/kotlin/app/syncler/core/network/NetworkModule.kt`
- `android/core/storage/src/main/kotlin/app/syncler/core/storage/SecurePrefs.kt`
- `android/core/auth/src/main/kotlin/app/syncler/core/auth/Session.kt`
- `android/core/auth/src/main/kotlin/app/syncler/core/auth/SessionStore.kt`
- `android/core/auth/src/main/kotlin/app/syncler/core/auth/AuthRepository.kt`
- `android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/UserStateRepository.kt`
- `android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingRepository.kt`
- `android/feature/settings/build.gradle.kts`
- `android/feature/settings/src/main/kotlin/app/syncler/feature/settings/Screen.kt`
- `android/app/src/main/kotlin/app/syncler/android/ui/SettingsScreen.kt`
- Test files updated to match.

New:
- `android/core/auth/src/main/kotlin/app/syncler/core/auth/RotationRepository.kt`
- `android/core/auth/src/test/kotlin/app/syncler/core/auth/RotationRepositoryTest.kt`
- `android/feature/settings/src/main/kotlin/app/syncler/feature/settings/SettingsViewModel.kt`

## Risks I want eyes on

1. **§10.10 high-water-mark scoping.** Per-user-id, not per-
   device-id. New device first-login for a user who has already
   rotated reads `read(user) == 0`, so the server's gen 2 passes
   `>= 0` and we bump to 2. Subsequent server gen 1 (downgrade)
   trips. The spec says "Every device persists
   `highest_key_generation_seen` in local unencrypted storage" —
   our SecurePrefs is encrypted but the value is not secret,
   only integrity-protected. Either reading of the spec works;
   does this scoping pass muster?

2. **Cached authSalt + encryptedMasterKey in Session.** Kept in
   memory only (never persisted). The authSalt is not secret
   (server returns it on pre-login). The encryptedMasterKey is
   the rest-encrypted MK — its plaintext is already in Session.
   The trade-off: holding these saves a pre-login → login round
   trip during "Change password" but adds them to the in-memory
   footprint. Acceptable?

3. **`WrongCurrentPasswordError` raised BEFORE network.** The
   local unwrap-test catches typos without burning the server's
   failed-proof rate-limit window. The flip side: a sophisticated
   attacker with read-access to the device's local storage could
   try unwraps without the server seeing rate-limit data. That's
   strictly weaker than "attacker who already has the password",
   so the trade-off is fine — but flag if you disagree.

4. **`Session.currentUserId()` regex-based sub extraction.** No
   `org.json` because JVM tests stub it. Defensive regex requires
   `"sub": "..."` with escape handling. If the JWT body has nested
   objects with a `sub` field elsewhere, we'd pick the wrong one.
   Server's pyjwt only emits flat top-level claims, so this is
   safe in practice. Worth flagging?

5. **`updateAfterRotation` copy-then-zero ordering.** The bug I
   fixed: `password_rewrap` passes `newMasterKey =
   previous.masterKey` (same byte array reference). Zeroing
   first would also zero the bytes we're about to copy. Fixed
   by copying first. Test
   `rewrapHappyPathUpdatesSessionAndKeyGenerationStore`
   asserts `masterKeyBefore.contentEquals(after.masterKey)`
   would catch a regression here.

6. **No `keyGenerationObserved` on revoke-pairing.** The route
   passes nothing; this matches Phase 8b's
   `require_observed=False` decision (revoke doesn't write a
   `key_generation`-tagged blob).

## Output

Per reviewer, terse:

1. Verdict: GREEN / YELLOW / RED + items.
2. Anything missing for Phase 8c scope (NOT 8d AAD-binding, NOT
   8e root_* modes — those are explicit follow-ups).
3. Anything new (races, leaks, footguns).

If dual-GREEN, I commit Phase 8c + transcripts and pick up Phase
8d or Phase 9 next.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
