# Claude's review of M1–M4b (after M4b commit 7eea147)

Format: Issue — severity — file:line — proposed action.

## Critical
None.

## High

**H1. Plugin dispatch contract mismatch between M4a and M4b**
- `sdk-plugin/src/bridge.ts:57-60` catches all errors in `dispatchPluginHook` and **returns** `JSON.stringify(serializeError(error))` as a successful value.
- `android/feature/plugin-host/.../PluginLoader.kt:271-281` (PluginHtmlShell.render) wraps the dispatcher and resolves on success or rejects on error.
- Result: plugin errors silently arrive at the host as successful-looking results with a stringified error blob inside `.value`. Host can't distinguish "plugin ran fine and returned a string" from "plugin threw."
- **Fix:** make `dispatchPluginHook` re-throw on error so the M4b wrapper's `.catch` runs and the host sees `{error: "plugin_dispatch_failed", message: ...}`. Drop the try/catch in bridge.ts.

**H2. `showNotification` gated by `background-exec` capability**
- `android/feature/plugin-host/.../PluginBridge.kt:84` maps `platform.showNotification` to `"background-exec"`.
- Not in the Q21 capability list (network, storage, camera, gallery, file, location, background-exec). showNotification is part of the standard plugin surface — even render-only plugins should be able to post notifications when their plugin is invoked.
- **Fix:** `requiredCapability` returns `null` for `platform.showNotification`. background-exec should gate whether the plugin's `onMessage` JS is allowed to run during push receipt — that's a different layer (M5's PluginNotificationService dispatch decision), not a per-call check.

**H3. Deterministic auth_salt from email (M3)**
- `android/core/auth/.../AuthSalt.kt:11-14` derives the salt as `SHA-256("syncler-v1-auth-salt:" + lowercase(email))[0:16]`.
- Defeats the purpose of salt: anyone who knows the email can compute it. Two effects: (1) a global rainbow table for any given email is feasible; (2) on a third device, the user can't decrypt their master key blob if the original signup salt was different (it won't be because the deterministic derivation matches — but that's the only reason it works at all).
- **Fix:** Add server endpoint `POST /v1/auth/pre-login { email }` returning `{ auth_salt, argon2_params_version }`. Client fetches salt before deriving auth_key. Salt was generated client-side at signup and stored on server (already in `users.auth_salt` per M1.3 schema). Rate-limit per-IP + per-email to prevent email enumeration.

**H4. Plugin manifest/bundle URL HTTPS not enforced**
- `android/feature/plugin-host/.../PluginLoader.kt:75-85` (fetchBytesNoCache) accepts any URL scheme.
- HTTP manifest URLs would happily be fetched. Signature still protects content, but network adversary can see which plugins are being installed.
- **Fix:** assert `url.startsWith("https://")` for production builds; allow `http://localhost` for dev mode via BuildConfig flag.

## Medium

**M1. `Response` body always treated as string**
- `android/feature/plugin-host/.../PluginLoader.kt:243-244` (HTML shell `asResponse`) constructs `new Response(payload.body || "", ...)` where body is the bridge's serialized string.
- `NetworkBridge.kt:48` calls `response.body?.string()`. Plugins fetching binary data (images, PDFs) get garbage.
- **Fix:** add `{body, bodyEncoding}` where encoding can be `"text"` or `"base64"`; bridge inspects Content-Type to decide.

**M2. Bridge result envelope inconsistency**
- `PluginBridge.kt` returns raw result JSON for success (e.g., `{"status": 200, ...}` for fetch, `{"value": "x"}` for storage.get) AND raw error JSON for errors (`{"error": "..."}`).
- HTML shell distinguishes by checking `result.error`. If a real result happens to contain an `error` key (e.g., fetch returns a 4xx with `{"error": "validation_failed"}` in the body), the shell will incorrectly reject.
- **Fix:** envelope all results: `{success: true, value: ...}` or `{success: false, error: ..., message?: ...}`. Single decision point.

**M3. PluginPermissionStore not Hilt-injected**
- Inconsistent with `:core:auth`, `:core:network` using Hilt. Standalone class.
- **Fix:** wrap in a `@Singleton`-bound provider; declare in a `@Module` in `:feature:plugin-host`.

**M4. `loadDataWithBaseURL` baseURL needs inspection**
- `PluginLoader.kt:201` uses `PluginWebViewClient.INITIAL_URL` as baseURL. If that's a `file://` URL, even though `allowFileAccess=false`, JS origin checks may treat it as file scheme. Need to confirm it's `https://syncler-plugin-host/` style.
- **Fix:** confirmed during review — read `PluginWebViewClient.kt` for the actual value.

**M5. `domStorageEnabled = false, databaseEnabled = false` may break plugins using localStorage**
- `PluginLoader.kt:213-214` disables DOM storage and DB. M4a's `platform.storage` routes through the native bridge, but if a plugin transitively uses a library that touches localStorage, it'll fail.
- **Fix:** acceptable — plugins must use `platform.storage`. Document in `docs/plugin-sandbox.md`.

**M6. Server-side message-action route not present yet for the `respond` bridge call**
- M4a defines `platform.message.respond(actionId, payload)` and M4b's MessageBridge implements it.
- But the server has no `POST /v1/messages/{id}/action` endpoint yet (M5 will add). MessageBridge presumably posts to a server route that doesn't exist. Need to verify either MessageBridge or its server counterpart.
- **Fix:** confirm MessageBridge is a stub or wire to a placeholder until M5.

## Low

**L1. Gradle wrapper not present in android/**
- `M3` notes flag this. Without `gradlew` you can't actually build the Android project from a clean checkout.
- **Fix:** part of M11 polish; one-time `gradle wrapper` invocation.

**L2. `M4b` PluginSignatureVerifier number serialization may diverge from Python**
- Kotlin's `numberToJson` converts Doubles that equal an integer back to int string ("3" not "3.0"). Python's `json.dumps` writes "3.0" for floats. Manifests currently have no numeric fields, but future versions could.
- **Fix:** standardize manifest schema to disallow numeric fields (use string serialization for any version/timestamp), or add a clarifying note in `docs/crypto-spec.md`.

**L3. Email enumeration via signup 409**
- M1.4 returns 409 on duplicate email during signup. Combined with no email-existence check on login (M1.4 returns generic 401), this still leaks via signup: try signup with target email → 409 confirms existence.
- **Fix:** signup returns 201 always (or 202), then sends verification email or silently no-ops. Defer fully until M3+I3 (email verification flow).

## Documentation / housekeeping

- `.triad/` is committed inside the repo. That's intentional but should be called out in the top-level README (which doesn't exist yet).
- No top-level README explaining how the four directories (server, android, sdk-plugin, docs) relate.
- **Fix queue for M11.**

## Action items
- **H1, H2:** fix immediately (file follow-up Codex prompt M4b.1).
- **H3:** server change (`POST /v1/auth/pre-login`) + Android change to fetch salt. File as M1.9 (server) + M3.1 (Android). Can fold into M5 or M6 prep.
- **H4:** add the assert. Tiny.
- **M1, M2, M3, M4, M5, M6:** fold into M4b.1 follow-up + M11.
- **L1, L2, L3:** defer to M11.
