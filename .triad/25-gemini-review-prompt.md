# Gemini security/edge-case review of M1–M4b

You are part of the build-phase triad as **reviewer** (Round 9 protocol = "lead-builds-others-review", Codex builds, Claude + Gemini review). Eight milestones have landed across server, Android, and JS plugin SDK. Claude has done a first-pass review; you do the security and edge-case pass.

## What you're reviewing

The codebase at `d:\Projects\syncler\` after commit `7eea147` (M4b: secure WebView plugin host). Specifically:

- **server/** — FastAPI + Postgres + zero-knowledge auth + crypto reference impl (M1, M2)
- **android/** — Kotlin/Compose multi-module app + secure plugin host with WebView isolation (M3, M4b)
- **sdk-plugin/** — TypeScript plugin SDK with JS bridge (M4a)
- **docs/crypto-spec.md** — canonical crypto spec all three impls follow

The original build plan: `.triad/08-codex-final-plan.txt`. Crypto-spec: `docs/crypto-spec.md`.

## Files you should pay particular attention to

### Crypto + key material
- `server/app/crypto/argon2.py`, `hkdf.py`, `signatures.py`, `aead.py`, `nonce.py`, `wire.py`
- `docs/crypto-spec.md`
- `android/core/crypto/.../KeyDerivation.kt`, `Hkdf.kt`, `Signing.kt`, `MasterKey.kt`, `Aad.kt`
- `android/core/auth/.../AuthSalt.kt` — Claude already flagged: deterministic salt from email defeats salt's purpose

### Plugin sandbox
- `android/feature/plugin-host/.../PluginLoader.kt` — bundle download, signature verify, hash check, instance creation, hardened WebView, HTML shell injection
- `android/feature/plugin-host/.../PluginSignatureVerifier.kt` — canonical manifest serialization (must match `server/app/crypto/signatures.py`)
- `android/feature/plugin-host/.../PluginBridge.kt` — single `@JavascriptInterface call(method, argsJson, callbackId)`; capability gating; result delivery
- `android/feature/plugin-host/.../PluginWebViewClient.kt` — navigation/request blocking
- `android/feature/plugin-host/src/main/AndroidManifest.xml` — `android:process=":pluginhost"` for OS isolation
- `sdk-plugin/src/bridge.ts` — dispatcher contract

### Server protocol
- `server/app/routers/auth.py`, `devices.py` — signup/login/devices
- `server/app/middleware/rate_limit.py` — UPSERT-style token bucket
- `server/app/services/users.py`, `devices.py` — service layer

## Claude's first-pass findings (don't re-do, but tell me if I'm wrong)

Already filed (see `.triad/25-claude-review-m1-m4b.md`):

- H1: M4a `dispatchPluginHook` returns serialized error string instead of re-throwing → M4b shell can't distinguish error from value
- H2: `showNotification` gated by `background-exec` capability is wrong; should be unrestricted
- H3: Deterministic auth salt from email — defeats salt purpose, no pre-login salt endpoint
- H4: Plugin manifest/bundle URL HTTPS not enforced
- M1: Network bridge returns response body as string only (no binary)
- M2: Bridge result envelope inconsistency (raw success vs raw error — collision possible)
- M3: PluginPermissionStore not Hilt-DI'd
- M4: `loadDataWithBaseURL` baseURL value not confirmed
- M5: domStorageEnabled=false may break plugins using localStorage
- M6: MessageBridge.respond has no server endpoint yet (deferred to M5)
- L1: No Gradle wrapper
- L2: Kotlin number serialization may diverge from Python on numeric manifest fields
- L3: Email enumeration via signup 409

## What I want from you

Focus on what Claude missed. Specifically dig into:

1. **Sandbox escape paths.** Even with `:pluginhost` process + hardened WebView, can a plugin escape? Reflection? Hilt-leaked references? Intent injection?
2. **Bridge auth/scope.** A plugin in a WebView could try to call native bridge methods for OTHER plugins — does PluginBridge actually verify the plugin identity behind each `call(...)`? (Each plugin gets its own bridge instance bound to its own PluginInstance — but is the JS bundle isolated enough that one plugin's JS can't reach another's bridge via shared global?)
3. **Replay/freshness in messages.** Server has a `NonceRegistry` LRU per sender (M2). Memory-only. App restart → empty → replay window. What's the bound?
4. **AAD field set is the full required set.** Any path where AAD is assembled with fewer fields?
5. **Race conditions in rate limiter.** UPSERT-then-read pattern — under high concurrency from same actor, can the count drift?
6. **Plugin update vector.** If sender's public key is the only verification anchor, and the sender's private key leaks, attacker can push any plugin. Is there ANY mechanism to limit blast radius post-leak? (Trust-on-first-use revocation is M6.)
7. **Crypto-spec drift between three impls.** Server is Python (`cryptography`). Android is Kotlin (BouncyCastle + AndroidX Security). Plugin SDK is JS (uses platform.network only, no crypto on client). Are the test vectors in `docs/crypto-spec.md` actually exercised by tests in all three?
8. **Local data exposure.** `PluginPermissionStore`, `LocalDb`, plugin bundle storage. Are they encrypted at rest? Backed up by ADB or cloud backup unless `noBackup` is set?
9. **Anything weird about Codex's adaptations.** Codex made several judgment calls outside the strict spec (SQLite-compat ORM types, deterministic auth salt, capability-mapping for showNotification). Audit them.
10. **What Claude rated wrong.** If H1–L3 are actually different severities or if Claude misdiagnosed anything, say so.

## Output format

```
=== AGREEMENTS WITH CLAUDE ===
<list of issue IDs you agree with + 1-line confirmation each>

=== DISAGREEMENTS WITH CLAUDE ===
<id + your reframe>

=== ADDITIONAL FINDINGS ===
[bullet list]
- <severity> <one-line title>
  evidence: <file:line or what you read>
  proposal: <what to do>

=== SHOW-STOPPERS (block any further milestone until fixed) ===
<list, or "none">

=== NOT ACTUALLY ISSUES (you confirmed safe) ===
<list of things you specifically pressure-tested and ruled out>
```

Be sharp. The user wants both reviews — yours is the security pass.
