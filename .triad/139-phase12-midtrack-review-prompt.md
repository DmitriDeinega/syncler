=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 139 — Phase 12 mid-track review

Phase 12 (V2 #10 plugin capability expansion) is mostly
implemented; this is the mid-track review before closeout.
v0.1 dev posture preserved.

Design history:
- Triad 137: codex + gemini YELLOW with convergent FIX list
  on spec v1.
- Triad 138: gemini GREENLIGHT v2; codex YELLOW with one
  blocker (grant-row contradiction) addressed in spec v3
  (7877b47).

## Commits in scope

| Commit  | Steps | Content |
|---------|-------|---------|
| 7877b47 | 1-2   | spec v3 (post-triad 137+138 fixups) |
| 42a477f | 3     | server + SDK location capability split |
| f31e459 | 4-7   | PluginCapabilityDb, GrantStore, HandleStore, ActivityCoordinator |
| 6835c6c | 8-12  | GrantDialog + 4 bridges (Location/File/Gallery/Camera) |
| 0f62aa1 | 13-14 | fileBytes / releaseHandle bridge entries; NATIVE_SDK_ABI=2 |
| d8fa90d | 17    | CapabilityHandleStoreTest (13 cases) |

Not in scope (deferred to a follow-up):
- Step 16: Settings sheet per-plugin grant list + revoke UI
  (needs an Activity context I'm not touching without the
  user reviewing the UX).

## Files in scope for review

### Server / SDK (already triad-reviewed at 137-138)

- `server/app/services/plugins.py` — `_validate_capabilities`
  rejects legacy `location`, unknown names, duplicates, and
  the both-location pair (`location_double_declaration`).
- `server/tests/test_phase12_capabilities.py` — 8 tests.
- `sdk-plugin/src/enums.ts` — `Capability` enum updated
  (LOCATION_COARSE + LOCATION_FINE; LOCATION removed).

### Android — primary review target

- `android/plugin-sdk-runtime/src/main/kotlin/.../NativeSdkAbi.kt`
  — `NATIVE_SDK_ABI = 2` (bumped from 1).
- `android/plugin-sdk-runtime/src/main/kotlin/.../PluginContext.kt`
  — added `CapabilityHandle` data class; swapped
  CameraResult/FileResult/GalleryResult to handle-bearing;
  added `precision` to LocationResult; added `fileBytes` +
  `releaseHandle` to PluginContext interface; added
  `FileBytesChunk` data class.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/PluginCapabilityDb.kt`
  — two Room entities (`plugin_capability_grants`,
  `plugin_capability_audit`), DAOs, SQLCipher-backed db.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityGrantStore.kt`
  — grant CRUD with in-memory negative-cache; touch
  on success; reverifyGrant for mid-flight revocation check.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityHandleStore.kt`
  — file-backed staging at cacheDir/plugin-capability/<token>/;
  16 MB cap; 32 concurrent per plugin; 5 min TTL via
  SystemClock.elapsedRealtime (triad 138 B); stateless
  seek-and-read via RandomAccessFile (triad 138 C);
  per-token wipe on plugin unload.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityActivityCoordinator.kt`
  — singleton bound to Application; pre-registers AndroidX
  ActivityResultContracts (OpenDocument, PickVisualMedia,
  PickMultipleVisualMedia, TakePicture, RequestPermission);
  stable registry keys survive config change; pending-call
  map keyed by call_id; one-active-op concurrency gate;
  matching-by-kind in the result callbacks.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityGrantDialog.kt`
  — CapabilityGrantPrompter (state-flow-based); Compose
  CapabilityGrantDialogHost. Used ONLY for `location.*`.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/LocationBridge.kt`
  — platform LocationManager single-fix; Category B grant
  sequence (in-app prompt → OS perm → grant row insert →
  fix); returns `precision` reflecting actual provider.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/FileBridge.kt`
  `GalleryBridge.kt` `CameraBridge.kt`
  — Category A grant sequence (no in-app prompt; OS UI is
  consent); SAF URI immediate-copy; atomic multi-staging for
  gallery; immediate camera-tempfile delete after staging
  (triad 138 D).
- `android/feature/plugin-host/src/main/kotlin/.../PluginBridge.kt`
  — added `platform.fileBytes` + `platform.releaseHandle`
  dispatcher entries with optional handle-store dependency.
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../BridgePluginContext.kt`
  — updated decoders for handle-bearing returns; added
  `fileBytes` + `releaseHandle` overrides marshalling via
  the existing bridgeCall transport.
- `android/feature/plugin-host/src/test/kotlin/.../CapabilityHandleStoreTest.kt`
  — 13 unit tests (scope, caps, EOF, idempotent reads).

## Concerns I want a second opinion on

1. **Matching-by-kind in ActivityCoordinator's result handlers.**
   AndroidX's `register()` callback doesn't carry our `call_id`,
   so when a result arrives we look up the first pending call
   whose `kind` matches. With the one-active-op concurrency
   gate this is safe (only one pending of each kind), but it
   relies on the gate. Is there a sharper invariant — e.g.
   embed `call_id` into the launched intent's extras and
   recover it on the result — that's worth the complexity?

2. **Continuation type smuggling.** `launchActivityResult` is
   `suspendCancellableCoroutine { cont -> ... }`. The
   `PendingCall.continuation` field is typed as `Any` and cast
   back to `kotlin.coroutines.Continuation<Any?>` for
   `resume()`. Works but loses type safety. Is there a clean
   way to keep the `CancellableContinuation<Any?>` typed?
   I went with `Any` because the suspend type varies by
   kind (Uri? for file, Boolean for camera, etc.).

3. **CapabilityGrantPrompter cooperative wait.** The prompter
   spins in a `while (_pendingPrompt.value != null) delay(50)`
   loop when called while another prompt is up. The spec
   says one Activity-result op at a time but doesn't pin the
   prompt concurrency. Is the cooperative spin acceptable
   for v0.1, or should it be a proper Mutex / Channel-based
   queue?

4. **`isReturnDefaultValues = true` in plugin-host's unit test
   config.** Triggered by `SystemClock.elapsedRealtime` calls
   from CapabilityHandleStore in the test path. With this
   on, all Android-stubbed methods return their primitive
   defaults (0, null, false) instead of throwing. That
   silently masks any *other* Android-API call I haven't
   noticed. Is the trade-off OK, or should I plumb the
   clock as an injected dep?

5. **Grant-row TTL.** Grants are persisted indefinitely until
   the user revokes from settings (or plugin uninstall calls
   `forget()`). Is that right, or should grants expire after
   N days of non-use to force re-consent? Codex 138 didn't
   flag this; just want a sanity check.

6. **Cross-plugin handle visibility.** Test confirms a plugin
   knowing Plugin B's handle string still can't read it
   because the lookup is keyed by `(plugin_row_id, handle)`.
   But the handle string IS in the audit log (well, audit
   excludes the handle per spec). Anywhere else a handle
   could leak? Currently:
   - Audit log: no.
   - SecurePrefs: no.
   - Bridge call JSON: yes, but only in the response to the
     plugin that owns it.
   - Logs / crash dumps: possibly if a runtime throws with
     the handle in its message? Worth a defensive `redact`
     pass?

7. **Settings UI deferral.** Step 16 (per-plugin grant list +
   revoke UI) is the user's only revoke mechanism right now.
   Without it, granted permissions are permanent at v0.1
   (uninstall is the only way out). Is this an acceptable
   trade-off for shipping the rest of Phase 12, or is it a
   blocker?

8. **Camera FileProvider manifest entry.** CameraBridge calls
   `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ...)`
   but I haven't added the corresponding `<provider>` to the
   app manifest. Camera calls will fail with `io_error` until
   it's wired. Is that worth fixing before mid-track or in
   the same follow-up as step 16?

9. **One-active-op gate scope.** The concurrency gate is
   process-global: only one Activity-result call at a time
   across ALL plugins. That means Plugin A launching the
   camera blocks Plugin B from launching a file picker.
   Spec says this is intentional ("avoids layered Intent
   stacks and weird Activity-recreation behavior"), but is
   it actually a real concern, or should it be per-plugin?

10. **`platform.fileBytes` chunk reassembly.** Plugins call
    `fileBytes(handle, offset, length)` in a loop, accumulating
    bytes. For a 16 MB max file at 256 KB chunks that's up
    to 64 IPC round-trips. Acceptable, or should we offer a
    larger-chunk mode for trusted in-process plugins? V0.1
    keeps the 256 KB cap.

## Test status

```
:feature:plugin-host:testDebugUnitTest:
  CapabilityHandleStoreTest — 13 / 13 ✓
  (other existing tests unchanged, all green)
:feature:plugin-native-sandbox:testDebugUnitTest — all green
:feature:plugin-sandbox:testDebugUnitTest — all green
:app:assembleDebug — green
server pytest tests/ — 188 / 188 ✓ (was 180; +8 Phase 12)
```

No connectedAndroidTest coverage for the Activity coordinator
flow — that needs an emulator and is realistically a Phase 13
or ultrareview-time concern.

## What I'm asking for

For each numbered concern, a verdict:
- **OK** — current implementation is defensible.
- **NIT** — minor improvement; not blocking.
- **FIX** — change before Phase 12 closeout.
- **DESIGN** — disagree; expand.

Plus anything you spot in the named files that I missed.
Focus on:
- Activity-result lifecycle correctness across config change /
  Activity destroy / process death.
- Handle-store security boundaries (scoping, TTL, leak vectors).
- Coroutine lifecycle (cancellation, timeout propagation).
- Grant-row invariants across all 4 bridges' Category A vs
  Category B paths.

Skip cosmetics; flag substance.
