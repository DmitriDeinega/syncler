=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 136 — Phase 11b mid-track review

Phase 11a design closed at triad 135 dual-GREEN
(`.triad/135-phase11a-design-v4-prompt.md`, commit d03fb0e).
Phase 11b implementation is mid-flight; this is the mid-track
review before the closeout. Same project context as 132–135:
"we still developing on my local pc. so everything is v0.1".

## Commits in scope

| Commit  | Step | Content                                       |
|---------|------|-----------------------------------------------|
| 2913f80 | 1    | spec digest at `docs/plugin-host-native-kotlin.md` |
| bc3d551 | 2    | server: PluginPublishRequest gains `entry_class` + `native_sdk_abi`; CHECK constraint by renderer; migration 0012 |
| 5f8d9bf | 3    | AIDL: PluginLoadParcel WIRE_VERSION=2 (entry_class + native_sdk_abi appendix); loadPlugin gains nullable ParcelFileDescriptor bundleFd |
| 32fb675 | 4    | NEW `:plugin-sdk-runtime` module: SynclerPlugin, PluginContext, NATIVE_SDK_ABI=1 |
| 51af4e0 | 5    | NEW `:feature:plugin-native-sandbox` module: PluginNativeSandboxService (isolatedProcess=true), RealNativePluginHost (DEX loader, ABI gate, prefix scan), BridgePluginContext, BoundedPluginDispatcher, DexClassNameReader |
| 16ec46d | 6    | Host-side routing: SandboxRouter switches on renderer; NativePluginSandboxConnection uses bindIsolatedService(instanceName=token); per-token death decoupled from JS shared-process death |
| 505ab51 | 7    | 17 unit tests: ForbiddenPackagePrefixesTest, DexClassNameReaderTest, NativeLoadFailureCodesTest |

## Files in scope

### Spec / Server (already triad-reviewed at 132–135, included for cross-reference)

- `docs/plugin-host-native-kotlin.md` — Phase 11 spec digest.
- `server/app/schemas.py` — PluginPublishRequest validators for
  `entry_class` + `native_sdk_abi` cross-renderer constraints
  (regex, length cap, ABI >= 1, native-only requirement).
- `server/app/services/plugins.py` — service-layer re-check.
- `server/app/routers/plugins.py` — `_publish_envelope`
  conditional inclusion (so older publishes byte-identical).
- `server/alembic/versions/0012_plugin_native_kotlin.py` — TEXT
  + INTEGER columns + tri-state CHECK constraint with renderer.
- `server/tests/test_phase11_schema.py` — 10 server schema tests.

### Android — primary review target

- `android/core/plugin-aidl/src/main/aidl/app/syncler/core/pluginaidl/IPluginSandbox.aidl`
  — `loadPlugin(parcel, callback, @nullable ParcelFileDescriptor bundleFd)`.
- `android/core/plugin-aidl/src/main/kotlin/app/syncler/core/pluginaidl/PluginLoadParcel.kt`
  — WIRE_VERSION=2; `entryClass: String = ""`, `nativeSdkAbi: Int = 0`;
  CREATOR accepts both v1 and v2 readers (tail-byte appendix).
- `android/plugin-sdk-runtime/src/main/kotlin/app/syncler/plugin/runtime/*.kt`
  — SynclerPlugin interface, PluginContext + data classes,
  `NATIVE_SDK_ABI = 1`.
- `android/feature/plugin-native-sandbox/src/main/AndroidManifest.xml`
  — service declaration: `android:isolatedProcess="true"`,
  `android:process=":nativePlugin"`, `android:exported="false"`.
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../PluginNativeSandboxService.kt`
  — AIDL Stub; one-shot load per process; closes orphaned
  bundleFd; routes hooks + bridge results; defensive token-mismatch
  no-ops.
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../RealNativePluginHost.kt`
  — read DEX from PFD with 4MB cap, SHA-256 verify, defense-in-depth
  forbidden-prefix scan, ABI gate BEFORE InMemoryDexClassLoader,
  entry-class resolve + SynclerPlugin assignability + no-arg ctor,
  async onInit under 10s withTimeoutOrNull, error-FIRST-then-cancel
  ordering on init failure (triad 135 invariant).
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../BridgePluginContext.kt`
  — typed PluginContext impl marshalling each `platform.*` call into
  `bridgeCall(method, argsJson, callbackId)` and suspending on
  `deliverBridgeResult`; ConcurrentHashMap for pending callbacks.
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../BoundedPluginDispatcher.kt`
  — ThreadPoolExecutor(core=1, max=4, queue=64, CallerRunsPolicy),
  daemon threads named for the token.
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../DexClassNameReader.kt`
  — minimal DEX header walker (string_ids → type_ids → class_defs).
- `android/feature/plugin-native-sandbox/src/main/kotlin/.../ForbiddenPackagePrefixes.kt`
  — `app.syncler.`, `android.`, `androidx.`, `kotlin.`, `kotlinx.`,
  `java.`, `javax.`.
- `android/feature/plugin-host/src/main/kotlin/.../sandbox/SandboxRouter.kt`
  — routes by `parcel.renderer` (`script` vs `native_kotlin`);
  per-token death handler decoupled from `handleSandboxDeath` (which
  now scopes to JS handles only).
- `android/feature/plugin-host/src/main/kotlin/.../sandbox/NativePluginSandboxConnection.kt`
  — `bindIsolatedService(intent, BIND_AUTO_CREATE, instanceName=token, direct executor, conn)`;
  one binding per token in a ConcurrentHashMap; per-token
  onServiceDisconnected fires `onTokenDeath(token)`.

## Concerns I want a second opinion on

1. **bundleFd lifecycle on the success path.** SandboxRouter
   `loadNative` opens the PFD in the host UID and passes it
   over Binder. On success, the host's PFD dup is currently
   NOT explicitly closed — we rely on GC because closing here
   would race the sandbox's read (Android dup'd the FD on its
   end at marshalling time, but I'm not 100% sure how aggressive
   Android is about that dup). What's correct?
   - (a) Close immediately after `loadPlugin` returns — Android
     already dup'd at IPC time, so this is safe.
   - (b) Don't close, let GC reclaim (current behavior) — risk
     of host UID's FD table pressure under heavy churn.
   - (c) Close in a `try/finally` around `bundleFd.close()` only
     after some sandbox-acks "I have read the bytes" signal.

2. **bundleFd ownership on the failure path.** Currently if
   `loadPlugin` over Binder throws (any AIDL exception), we run
   `runCatching { bundleFd.close() }` then `unbindForToken`. Is
   that the right order? Specifically: if the sandbox already
   read part of the FD, does an explicit host-side close cause
   the sandbox's `FileInputStream.read()` to return EOF mid-read
   (which would then be a half-read corruption it can't detect)?
   Or does each side hold its own dup?

3. **InMemoryDexClassLoader parent = SDK runtime classloader.**
   We set parent to `SynclerPlugin::class.java.classLoader`. In
   the isolated process this is the system class loader because
   the SDK runtime AAR is in the APK. Result: the plugin can
   resolve `SynclerPlugin`, `PluginContext`, etc. (which it
   must, to implement the entry interface). It CANNOT resolve
   anything else from `:feature:plugin-native-sandbox` or the
   host's modules — because the isolated process classpath
   doesn't carry them. (The isolated UID can't even read the
   host APK's main module, IIRC — only the parent app's APK
   resources are visible.) Is the parent classloader choice
   safe? Specifically: could a malicious plugin reach upward
   through the parent chain to load something we don't want it
   to see?

4. **BridgePluginContext JSON encode/decode.** I hand-rolled
   it to avoid pulling Moshi/JSON into the isolated sandbox
   module (keeps the trusted code surface small). The decoders
   use `String.indexOf` lookups for fields — fine for the
   host's canonical output (we control both sides) but
   borderline brittle. The escape() encoder handles `\"`, `\\`,
   `\n`, `\r`, `\t` but NOT control chars or non-ASCII. Concern:
   a plugin can send arbitrary Strings via PluginContext (e.g.
   `notification.title` or `storage.set(key, ...)`). If a
   plugin sends a control character in `key`, the JSON we emit
   to the host will be malformed. Host should reject; plugin
   sees `Result.failure`. Adequate, or should I escape more
   aggressively?

5. **Per-token process death attribution.** When the OS reaps
   an isolated process,
   `NativePluginSandboxConnection.PerTokenConnection.onServiceDisconnected`
   fires. I dispatch `onTokenDeath(sandboxToken)` which the
   router uses to remove ONLY that handle and emit
   `bridgeDispatcher.onPluginCrashed(token, "process_died")`.
   `handleSandboxDeath` (JS-shared path) now filters to
   `!isNative` handles. Is there any race I'm missing where
   both fire for the same token?

6. **bindIsolatedService executor choice.** I pass
   `{ runnable -> runnable.run() }` (direct executor) since
   bindings are infrequent. The docs say this executor receives
   `onServiceConnected` / `onServiceDisconnected` callbacks.
   Direct executor means those fire on whatever Binder thread
   delivered them — same threading model as the JS path. Safe?
   Or should I post to a dedicated single-thread executor for
   consistency / re-entrancy avoidance?

7. **Native sandbox concurrent-load semantics.** The native
   `loadPlugin` rejects with `concurrent_load_in_progress` if
   `loadedToken != null`. But unlike the JS sandbox (shared
   `:plugin` process), each native sandbox process should ONLY
   ever see one load in its lifetime — the host binds a fresh
   isolated process per token via `instanceName`. So
   `concurrent_load_in_progress` here is purely defensive
   against a buggy host. Worth keeping the check, or is the
   "this should never happen" comment + an `error("...")` more
   honest about the invariant?

8. **Forbidden-prefix scan defense-in-depth.** The HOST scans
   the DEX at staging time and rejects with
   `forbidden_package_prefix`; the SANDBOX repeats the same scan
   inside the isolated process. With per-token isolated UIDs,
   what's the actual threat the sandbox-side scan defends
   against? Possibilities:
   - (a) A compromised host that skips its own scan — but a
     compromised host has bigger problems (it can hand the
     sandbox any DEX it wants by the time we're inside the
     bind).
   - (b) Defense against future code changes that accidentally
     skip the host scan.
   - (c) Just paranoia tax for the sandbox's threat model.
   Worth keeping, or simplify?

9. **DEX size cap enforcement timing.** Currently `readDex`
   reads up to `DEX_MAX_BYTES + 1` bytes then throws
   `DEX_TOO_LARGE` after the read completes. With 4MB cap and
   a 4MB+1-byte malicious DEX, we still buffer 4MB into heap
   before the throw. Acceptable, or should I stream and throw
   earlier (the 64KB chunk loop already checks per-iteration,
   so worst case we over-read by < 64KB — actually that's
   what's happening already).

10. **API gate placement.** SandboxRouter's `loadNative` throws
    `native_only_api_29` synchronously on API < 29 BEFORE any
    bind. The string `native_only_api_29` is in the spec doc
    but I haven't added it to `NativeLoadFailureCodes` (it's a
    host-side error, sandbox never sees it). Consistent, or
    should the constant exist in both places?

## Test status

```
:feature:plugin-native-sandbox:testDebugUnitTest — 17 tests, 0 failures
  ForbiddenPackagePrefixesTest — 8 tests
  DexClassNameReaderTest       — 5 tests
  NativeLoadFailureCodesTest   — 4 tests
:feature:plugin-host:testDebugUnitTest          — unchanged, all green
:feature:plugin-sandbox:testDebugUnitTest       — unchanged, all green
:app:assembleDebug                              — builds clean
server pytest                                   — 180 tests, 0 failures (Phase 11 schema = 10)
```

End-to-end coverage of the isolated-process behavior (real DEX
load via InMemoryDexClassLoader, real PFD round-trip, per-token
death surviving siblings) requires connectedAndroidTest on an
API 29+ emulator — deferred to Phase 11c or surfaced in
ultrareview.

## What I'm asking for

For each numbered concern, a verdict:
- **OK** — current implementation is defensible.
- **NIT** — minor improvement; not blocking.
- **FIX** — change before closeout.
- **DESIGN** — disagree with the design; expand.

Plus any issues NOT in the list above that you spot in the
named files. Focus on:
- AIDL boundary safety (Binder threading, PFD lifecycle, error
  propagation).
- Class loading isolation correctness (parent chain, dex
  classloader, security boundaries the isolated process
  actually enforces vs. what we ASSUME it enforces).
- Coroutine lifecycle (scope.cancel ordering, init timeout
  cancellation, dispatcher shutdown).
- JSON marshalling robustness in BridgePluginContext.

Skip cosmetics; flag substance.
