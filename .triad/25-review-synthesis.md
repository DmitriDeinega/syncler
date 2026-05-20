# Combined review synthesis — M1–M4b

Sources: `.triad/25-claude-review-m1-m4b.md` + `.triad/25-gemini-review.txt`.

## Fix NOW (M4b.1 follow-up Codex prompt)

These either block correctness of further milestones or are cheap-to-fix high-severity issues. Filing one consolidated Codex prompt.

| ID | Title | Source | Action |
|---|---|---|---|
| H1 | Plugin dispatch contract: M4a swallows errors, M4b expects throws | Both | sdk-plugin/src/bridge.ts: drop the try/catch wrapper around `dispatchPluginHook`, let errors propagate. M4b's shell wrapper handles them. |
| H2 | `showNotification` gated by `background-exec` capability | Both | PluginBridge.kt:84 — return `null` for `platform.showNotification` (no capability required). |
| H3 | Deterministic auth salt from email | Both | Server: add `POST /v1/auth/pre-login {email}` returning `{auth_salt, argon2_params_version}` with rate limit. Android: rewrite AuthSalt to fetch from server before deriving auth_key. |
| H4 | Plugin URL HTTPS not enforced | Both | PluginLoader.kt: assert `url.startsWith("https://")` for manifests + bundles; allow `http://localhost` only in debug builds via BuildConfig. |
| M2 | Bridge result envelope inconsistency | Both | PluginBridge.kt: wrap every result as `{"success": true, "value": ...}` or `{"success": false, "error": ..., "message": ...}`. HTML shell + JS dispatchers updated to match. |
| M4 | `loadDataWithBaseURL` uses file:// origin | Gemini | Change `PluginWebViewClient.INITIAL_URL` from `file:///plugin/index.html` to `https://syncler-plugin-host/index.html`. Origin checks use https; no file scheme implications. |
| G-M5 | Kotlin AAD uses hardcoded linkedMapOf insertion order | Gemini | `android/core/crypto/.../Aad.kt`: explicitly sort keys before serialization. Match server canonical AAD exactly. |
| G-M6 | No Android crypto test against spec vectors | Gemini | Add `android/core/crypto/src/test/.../SpecVectorsTest.kt` with the hex test vectors from M2 (Argon2, HKDF, AEAD, Ed25519, wire). Each must reproduce the spec output bit-for-bit. |

## Defer to M11 polish

| ID | Title | Reason for deferral |
|---|---|---|
| M1 | Binary network response not supported | V1 plugins are JSON-heavy; design extension via `{body, encoding: text\|base64}` later. |
| M3 | PluginPermissionStore not Hilt-DI'd | Cosmetic; functional. |
| M5 | DOM storage disabled — needs docs | Already correct behavior, just document. |
| M6 | MessageBridge respond has no server endpoint yet | M5 adds the action endpoint; MessageBridge already wired to call it. |
| L1 | No Gradle wrapper | One-time setup; needs developer machine with gradle installed. |
| L2 | JSON number serialization may diverge | Manifests have no numeric fields today. Add lint rule when they do. |
| L3 | Email enumeration via signup 409 | Behind I3 (email verification flow); rolling into pre-ship checklist. |
| G-CRIT (downgraded) | `addJavascriptInterface` reflection vulnerability | Mitigated by `@JavascriptInterface` annotation on API 17+; we target minSdk 26 so reflective access is blocked. Migration to `WebMessageListener` is a defense-in-depth improvement, queued for V1.1. |
| G-H replay | In-memory nonce registry forgets on restart | Impact bounded: replay = duplicate delivery only (decryption proves message unforged; plugin dedupes per Q11/Gap 2 user decision). Move to DB-backed registry in V1.5. |
| G-H key-revoke | No plugin key revocation list | M6 introduces sender revocation. Full plugin-key CRL = V1.5. Documenting in `docs/crypto-spec.md` as known risk. |
| G-Low | WebView nav → external Intent | Cosmetic UX detail; current behavior (silently block) is functionally safe. |

## Status

- 8 fixes in M4b.1 prompt.
- 11 items deferred to M11 (with rationale).
- After M4b.1 commits, the build continues with M5 (push + delivery).
