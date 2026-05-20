# M4b.1 — Review fix-ups from Claude + Gemini review of M1–M4b

You are still the build lead. After M4b landed, Claude and Gemini reviewed in parallel (per the protocol). 8 items must be fixed before continuing to M5. Workspace-write granted to `d:\Projects\syncler\`. Touch only files listed.

Full synthesis: `.triad/25-review-synthesis.md`. Reading it is optional — the specifics are below.

## Fix H1 — Plugin dispatch error contract
File: `sdk-plugin/src/bridge.ts:41-60`

Currently:
```ts
export async function dispatchPluginHook(hook: DispatchHook, args: unknown[]): Promise<unknown> {
  try {
    ...
  } catch (error) {
    return JSON.stringify(serializeError(error));
  }
}
```

Change to: drop the try/catch wrapper. Errors should propagate out of `dispatchPluginHook` so the Android-side shell wrapper (`PluginHtmlShell.render` in `PluginLoader.kt`) can `.catch` them and send `{success: false, error: "plugin_dispatch_failed", message: ...}`. The shell already does this; the SDK is the side that's wrong.

Also: remove the `serializeError` helper since it's no longer used.

Update `sdk-plugin/test/base-plugin.test.ts` to assert that errors thrown by `onMessage` / `onAction` / `onDismiss` REJECT the returned promise rather than resolve with a string.

## Fix H2 — `showNotification` capability gating

File: `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginBridge.kt:77-87`

Currently:
```kotlin
private fun requiredCapability(method: String): String? = when {
    ...
    method == "platform.showNotification" -> "background-exec"
    ...
}
```

Change to: return `null` for `platform.showNotification`. Notifications are an inherent plugin surface, not gated by background execution. Background execution gates whether `onMessage` JS is allowed to run during push receipt — that's a different layer.

Update `PluginBridgeTest` if there's an assertion about this; otherwise add a test that confirms `requiredCapability("platform.showNotification")` returns `null`.

## Fix H3 — Pre-login salt endpoint + Android salt fetch

### Server changes
- `server/app/routers/auth.py` — add new route:
  ```
  POST /v1/auth/pre-login
  body: { "email": "..." }
  response 200: { "auth_salt": "<base64>", "argon2_params_version": 1 }
  response 404: { "detail": "user_not_found" } -- but ALSO 200 with a deterministic-from-email fake salt to prevent enumeration (TOFU pattern: real user → real salt, unknown email → consistent fake salt)
  ```
  Wire to existing rate-limit "login" config (or create new "pre-login" config with same limits).

- `server/app/schemas.py` — add `PreLoginRequest`, `PreLoginResponse`.
- `server/app/services/users.py` — service helper `pre_login_salt(session, email) -> (salt, version)`. For unknown email: HKDF(server-secret-pepper, email) → 16 bytes → "fake" salt. The pepper is a new env var `PRE_LOGIN_PEPPER` set at deployment.
- `server/app/config.py` — add `pre_login_pepper: str` (defaults blank for dev).
- Tests in `server/tests/test_auth.py` for: real user returns stored salt; unknown email returns deterministic fake salt; rate-limited.

### Android changes
- `android/core/auth/.../AuthSalt.kt` — DELETE this file. Salt derivation is no longer client-side.
- `android/core/network/.../SynclerApi.kt` — add `POST /v1/auth/pre-login` interface method.
- `android/core/auth/.../AuthRepository.kt` — `login(email, password)` now:
  1. Calls `api.preLogin({email})` → gets `auth_salt`.
  2. Derives auth_key from password+salt (existing KeyDerivation flow).
  3. Calls existing `api.login(...)`.
- `android/core/auth/.../AuthRepository.kt` — `signup(email, password)` already generates the salt client-side and POSTs it. Keep that flow; the server stores it on signup; pre-login serves it back.
- Update tests accordingly.

## Fix H4 — HTTPS-only plugin URLs

File: `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginLoader.kt:74-85` (fetchBytesNoCache) AND the manifest fetch in `load()`.

Add:
```kotlin
private fun requireHttps(url: String) {
    val allowHttpLocalhost = BuildConfig.DEBUG && (url.startsWith("http://localhost") || url.startsWith("http://10.0.2.2"))
    require(url.startsWith("https://") || allowHttpLocalhost) {
        "plugin URLs must use HTTPS (got $url)"
    }
}
```
Call at the top of `fetchBytesNoCache` AND before resolving the bundle URL in `load()`.

Add corresponding test that http://example.com throws.

## Fix M2 — Bridge result envelope

The bridge sends results via `__syncler_internal_callback(callbackId, result)`. Currently the JS-side checks `result.error` to distinguish success/failure, which collides if a real bridge result has an `error` key (e.g., an HTTP response body containing `{"error": "..."}`).

Change the contract:
- Native side: every bridge response is wrapped:
  - Success: `{"success": true, "value": <original>}`
  - Failure: `{"success": false, "error": "<code>", "message": "<text>"}`

### Native changes
- `android/feature/plugin-host/.../PluginBridge.kt`: wrap successful results in `{"success": true, "value": ...}` and errors in `{"success": false, "error": "...", "message": "..."}`.
- Update each capability bridge to return its raw JSON; PluginBridge does the wrapping.

### JS shell changes
- `android/feature/plugin-host/.../PluginLoader.kt` (PluginHtmlShell.render): JS-side callback:
  ```js
  window.__syncler_internal_callback = (callbackId, result) => {
    const cb = callbacks.get(callbackId);
    if (!cb) return;
    callbacks.delete(callbackId);
    if (result && result.success) cb.resolve(result.value);
    else cb.reject({ error: result?.error || 'unknown_error', message: result?.message });
  };
  ```
- `network.fetch` wrapper: works on the unwrapped `result.value` (the network bridge already returns `{status, headers, body}` — wrapped becomes `{success: true, value: {status, headers, body}}`).
- `storage.get` wrapper: same — `cb.resolve({value: "..."})` becomes unwrapped to `{value: "..."}`, then `.then((r) => r.value)`.

### Tests
- Update `PluginBridgeTest` (if exists) to verify the envelope; otherwise add.

## Fix M4 — Plugin WebView base URL

File: `android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginWebViewClient.kt`

Change `INITIAL_URL` from `file:///plugin/index.html` (or whatever it is) to `https://syncler-plugin-host/index.html`. WebView will use `https://syncler-plugin-host` as the origin for CORS/file/storage checks. No `file://` semantics anywhere.

Verify `loadDataWithBaseURL(INITIAL_URL, ...)` in `PluginLoader.kt` is using this constant.

## Fix G-M5 — Kotlin AAD canonical JSON must sort keys

File: `android/core/crypto/src/main/kotlin/app/syncler/core/crypto/Aad.kt`

Currently uses a hardcoded `linkedMapOf(...)` insertion order. Even if it accidentally matches alphabetical today, it'll silently break if a new field is added out of order. Server's Python `json.dumps(..., sort_keys=True)` always sorts.

Change: in `toCanonicalJsonBytes`, explicitly sort the keys before serializing. Use `TreeMap` or sorted `entries` chain. Same logic as `PluginSignatureVerifier.canonicalJson` (which already sorts).

Add a test: build an AAD map with fields in NON-alphabetical insertion order, serialize, assert bytes match Python's output for the same fields (use the test vectors from `docs/crypto-spec.md`).

## Fix G-M6 — Android crypto test against spec vectors

Create `android/core/crypto/src/test/kotlin/app/syncler/core/crypto/SpecVectorsTest.kt` with the M2 test vectors hard-coded:

```kotlin
class SpecVectorsTest {
  // These exact hex strings come from docs/crypto-spec.md and server/tests/test_crypto.py
  private val ARGON2_HASH_HEX = "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"
  private val AUTH_KEY_HEX    = "e23ed7b136661e69f2424d8440777943827d9981e2fb409d69e48bce72dd7f82"
  private val WRAP_KEY_HEX    = "c8327524d69330d1993ba67e26a3576718d29f0602e44a881d924ca36836699c"
  private val PAIRING_KEY_HEX = "f6ed649481dd8a5ffc57401b816803fba79556731c5c9ff53be49f7862f8cb8e"
  private val ED25519_PUBKEY_HEX = "712651f450ba05b63898b99ef5f7ba45632e8e2527f7f715cd671ec4024cc51e"
  private val ED25519_SIG_HEX    = "3d3a4963d6390f4392b36dac13938cadf015da019c6d0b2004e701656f544f6b336bb9da81ef4fde0b392f3ac33884c7dbb40dcd6f0ac30f1bbc06a464e68a06"

  // Inputs that produced those outputs — see server/tests/test_crypto.py for the exact bytes
  ...

  @Test
  fun `argon2id derives spec vector`() { ... }

  @Test
  fun `hkdf pairing key matches spec`() { ... }

  @Test
  fun `aead wire format roundtrip matches spec`() { ... }

  @Test
  fun `ed25519 verify accepts spec signature`() { ... }
}
```

Mirror `server/tests/test_crypto.py` test-by-test. Each Kotlin test produces the exact same hex bytes as the Python test.

If any Android impl drifts from spec → test fails → fix the Android side (NOT the spec).

## After

Run a self-check that all listed files compile (parser-level if no toolchain): `python -c "ast.parse(...)"` for Python; nothing for Kotlin (codex doesn't have kotlinc).

Print summary:
- Each fix applied (H1, H2, H3, H4, M2, M4, G-M5, G-M6) with file:line where the change landed
- Anything you couldn't fix (with reason)
- One-line confirmation that the test vector hex strings are byte-identical to server/tests/test_crypto.py
