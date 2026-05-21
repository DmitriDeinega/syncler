# Review 34 — M11 dogfood path (rate-limit fixes, FCM decoupling, QR scanner, WebView card render)

This session unblocked end-to-end dogfood: a separate Claude Code instance acting as the lottery sender is the unblocked consumer. We made changes across server, sdk-plugin, sdk-python (just docs), and Android.

## Pressure-test this — specific items

### Server

1. **`plugin_publish` rate-limit fix** ([server/app/routers/plugins.py:69-95](server/app/routers/plugins.py#L69-L95))
   - Pre: route used `Depends(rate_limit("pairing_initiate"))`, which required `X-Sender-ID` header that SDK doesn't send → 400 "missing rate limit actor" for every valid SDK call.
   - Now: cheap IP-based pre-check via `message_send_ip` dependency, then after `get_active_sender` + `verify_message_envelope`, set `request.state.sender_id` and call `check_rate_limit(db, request, RATE_LIMITS["plugin_publish"])`.
   - New actor `plugin_publish` (10/60s per-sender) added to [`server/app/middleware/rate_limit_config.py`](server/app/middleware/rate_limit_config.py) and the sender-keyed branch in [`_identify_actor`](server/app/middleware/rate_limit.py:144).
   - Regression test: [`tests/test_plugins.py::test_publish_with_only_body_sender_id_succeeds`](server/tests/test_plugins.py).
   - **Validate**: a spoofer with a different sender's key cannot inflate someone else's bucket because the per-sender check fires *after* envelope signature verification. The IP pre-check is the only thing reachable pre-sig.

2. **`manifest_fetch` actor switched to `sender_id`-from-path** ([`rate_limit.py:154-160`](server/app/middleware/rate_limit.py)). Was requiring `X-Device-ID` that nobody sets; `/v1/plugins/{sender_id}/{plugin_identifier}/latest` is unauthenticated and has `sender_id` in the URL, so the bucket keys off that. **Validate**: per-sender catalog-probe bucket is the right shape for unauthed reads.

3. **FCM decoupling** ([`services/messages.py:133-141`](server/app/services/messages.py), [`routers/messages.py:139`](server/app/routers/messages.py)). The gate at `store_message` used to refuse storage if no FCM-equipped device existed, contradicting the in-file comment that said "FCM-less devices can still pull via inbox". Now: gate switched to `_active_devices()` (any non-revoked device); 410 detail updated to "recipient has no active devices". `_active_devices_with_fcm()` helper deleted (unused). New test: `test_send_succeeds_when_device_has_no_fcm_token` asserts 201 + inbox-pull works; renamed gate test: `test_send_returns_410_when_user_has_no_devices`. **Validate**: storage-channel and push-channel separation is clean; no remaining place assumes FCM-or-nothing.

4. **`plugin_identifier` projected into inbox response** ([`schemas.py:174-189`](server/app/schemas.py), [`routers/messages.py:151-184,217-229`](server/app/routers/messages.py)). Required so the device can call `/v1/plugins/{sender_id}/{plugin_identifier}/latest` to fetch the bundle for rendering. Single batch `SELECT id, plugin_identifier FROM plugins WHERE id IN (...)` per inbox response. **Validate**: N+1-safe; missing plugin row (revoked, etc.) falls back to empty string — not a crash.

5. **Test-fixture fix**: [`tests/test_messages.py::_seed_pairing_and_plugin`](server/tests/test_messages.py) was inserting a Plugin without `plugin_identifier` (M8 made it NOT NULL). Fixed by adding `plugin_identifier="com.test.plugin"`. **Validate**: doesn't suggest a missed migration somewhere else.

### sdk-plugin (TypeScript)

6. **Bundle format flipped from ESM to IIFE** ([`examples/minimal/build.sh`](sdk-plugin/examples/minimal/build.sh)). Pre: bundle ended with `export { MinimalPlugin }`, which is a syntax error in a non-module `<script>` tag — i.e. the existing V1 plugin pipeline was untestable end-to-end. Now: `--format=iife --global-name=SynclerPluginExports`. **Validate**: integration guide and any other docs/examples telling senders how to build are consistent. The lottery sender is being told to use the same flag.

7. **`render` dispatch hook added** ([`src/bridge.ts`](sdk-plugin/src/bridge.ts)). `DispatchHook` union grew `'render'`; `dispatchPluginHook` routes it to `registeredPlugin.render(args[0])`. Host calls `__syncler_internal_dispatch('render', [payload], cb)` and receives the HTML string back via the standard callback machinery. **Validate**: backward compatibility — existing onMessage/onAction/onDismiss callers still work, no contract changes.

### Android — pairing UX

8. **QR scanner + dev-mode pairing handoff** ([`feature/pairing/PairingScreen.kt`](android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingScreen.kt), [`PairingRepository.kt`](android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingRepository.kt)).
   - ZXing embedded scanner (`com.journeyapps:zxing-android-embedded:4.3.0`); CAMERA permission added to manifest.
   - Real `SecureRandom`-generated 32-byte AES key replaces the placeholder bytes — persisted with the `PairedSender` in `EncryptedSharedPreferences`. Storage schema updated ([`core/storage/PairedSenderStore.kt`](android/core/storage/src/main/kotlin/app/syncler/core/storage/PairedSenderStore.kt)) with backwards-tolerant decode (missing field → empty ByteArray).
   - Success card shows copyable `user_id` (parsed from JWT `sub` via new `Session.currentUserId()`) and `pairing_key_hex` so the sender's CLI can be primed out-of-band in V1.
   - Paired-senders list with revoke button + confirmation dialog.
   - HTTP broker URLs accepted in debug builds (matches the plugin-host relaxation we did earlier).
   - **Validate**: the placeholder→real pairing key switch is sound (the placeholder was decorative; no in-flight messages encrypted against it). The two-phase identity-tuple check at confirm time still holds — see the `senderId/publicKey/fingerprint/name/nameHash` mismatch revoke path in `PairingRepository.confirm`.

9. **JWT parsing for user_id** ([`core/auth/Session.kt:33-46`](android/core/auth/src/main/kotlin/app/syncler/core/auth/Session.kt)). New `currentUserId()` decodes the JWT body (base64url + JSON parse) and returns the `sub` claim. No signature verification — that's the server's job; we just project the claim. **Validate**: this is dev-only affordance; production should not depend on it being well-formed.

### Android — Keystore Ed25519 quirk

10. **Stale-alias defensive cleanup** ([`core/crypto/Signing.kt:39-66`](android/core/crypto/src/main/kotlin/app/syncler/core/crypto/Signing.kt)). On Samsung S24 (and likely other OEMs), `KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")` silently substituted P-256, producing a 65-byte uncompressed EC point under our Ed25519 alias. We now detect non-32-byte returns and `deleteEntry()` the bad alias, then re-generate. If Ed25519 isn't actually supported by Keystore on the device, the whole `runCatching` block fails and `DeviceKeyProvider` falls back to its BC seed path. **Validate**: the delete-then-regenerate sequence doesn't risk losing existing-signed-on-the-bad-key state — there shouldn't be any because enrollment had been rejecting the bad key with 400 (so nothing was ever registered using it).

### Android — HTTP relaxation in debug

11. **Plugin URLs + action-callback endpoints accept `http://` in debug** ([`feature/plugin-host/PluginLoader.kt:92-99`](android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/PluginLoader.kt), [`feature/plugin-host/capabilities/NetworkBridge.kt:20-30`](android/feature/plugin-host/src/main/kotlin/app/syncler/android/pluginhost/capabilities/NetworkBridge.kt), [`feature/pairing/PairingRepository.kt:33-44`](android/feature/pairing/src/main/kotlin/app/syncler/feature/pairing/PairingRepository.kt)). Release builds unchanged. Unit test asserting strict HTTPS-only behavior was removed because debug now allows any HTTP. **Validate**: there's no path where a release artifact could carry the relaxation (the `BuildConfig.DEBUG` flag is the only gate). 

### Android — inbox + WebView card render

12. **Inbox polling + AES-GCM decrypt** ([`feature/inbox/InboxRepository.kt`](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/InboxRepository.kt)). Polls `/v1/messages/inbox?since=<ts>` every 15s while the screen is in scope. For each new message: looks up `PairedSender` by `senderId`, reconstructs the 5-field AAD canonical JSON (treating `min_plugin_version: null` as empty string to match what the Python SDK signs), reassembles `nonce || ciphertext`, calls `Aead.decrypt` from `:core:crypto`. De-dupes by message id. **Validate**: the AAD reconstruction matches what `services/messages.py` stores; particularly the `min_plugin_version` null-vs-"" handling.

13. **Lazy plugin fetch + cache** (`InboxRepository.fetchPluginOrNull`). Per `(senderId, pluginIdentifier)`: GET `/v1/plugins/{sender_id}/{plugin_identifier}/latest` → download `signed_bundle_url` → cache `(bundleJs, endpoints)` in memory. Cache lives for process lifetime; no signature verification on the bundle in this path (the existing PluginLoader does verification but is heavyweight to wire here — flagged as V1.5 work). **Validate**: NOT verifying the bundle signature in the inbox-render path means a compromised CDN or MITM could substitute a bundle. Acceptable for V1 dev dogfood (debug-build, LAN, user's own sender), unacceptable for prod. Document the gap.

14. **Per-card WebView with CardBridge** ([`feature/inbox/InboxScreen.kt`](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/InboxScreen.kt), [`CardBridge.kt`](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/CardBridge.kt), [`RenderShell.kt`](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/RenderShell.kt)).
    - Each card gets a `WebView` with hardened settings (no file access, MIXED_CONTENT_NEVER_ALLOW, dom/db disabled).
    - `CardBridge` is a `JavascriptInterface` exposing `call(method, argsJson, callbackId)`. Currently routes only `platform.network.fetch`; everything else throws "not wired in inbox mode". OkHttp client uses MODERN_TLS + CLEARTEXT (debug only) and NO_COOKIES.
    - Endpoint allowlist enforced via inlined glob matcher (copy of `:feature:plugin-host/EndpointMatcher`).
    - `RenderShell` installs the standard `window.platform` proxy, embeds the bundle inline, then runs a trigger script that dispatches `'render'`, injects the returned HTML into body, and re-creates inline `<script>` tags so onclick wiring activates.
    - WebView height is fixed at 280dp (deliberate V1 simplification; auto-height is V1.5).
    - **Validate critically**:
      - **`CardBridge` lifecycle**: a Compose recomposition or scroll-recycle that creates new CardBridge instances against a WebView that's about to be detached could leak the CoroutineScope. `destroy()` is defined but never called by the AndroidView factory. Real concern.
      - **JavascriptInterface security**: targeting API 26+, no `@JavascriptInterface` annotation visible due to import structure — verify the bridge method is actually annotated correctly and the WebView can call it.
      - **Endpoint matcher reuse**: deliberately duplicated from `:feature:plugin-host` to avoid dragging the module. If the canonical implementation changes, this copy will drift. Consider extracting to `:core:network` or similar.
      - **Bundle signature not checked** before execution (called out above).
      - **innerHTML re-script-execution**: spec-correct pattern (replace `<script>` with cloned node) but means an attacker who substitutes the bundle (see point above) gets full JS execution in the inbox WebView — same blast radius as the plugin had anyway, but worth confirming we haven't broadened it.
      - **No CSP**: the shell has no `Content-Security-Policy` header. The WebView is loaded via `loadDataWithBaseURL` so external resources are accessible. For a sandboxed plugin host, a CSP would be appropriate.

15. **Empty `payload.declaredEndpoints` is a permanent reject** — if a plugin's manifest didn't declare any endpoints, the user can't tap any action button (every URL fails the allowlist). **Validate**: that's the right default — fail-closed is correct. But the UX is a card with a dead button. Worth surfacing the bridge error to the user somehow.

### Docs

16. **Integration guide rewritten** ([`docs/integration-guide.md`](docs/integration-guide.md)).
    - Renamed from `lottery-integration-spec.md` — guide is generic; example payload + identifier are placeholders.
    - Added explicit `registerPlugin(new MyPlugin())` call at module scope to match the actual SDK contract.
    - Build command updated with the IIFE flag.
    - §3 calls out V1 bridge limitations (only `network.fetch` wired; storage/notifications/etc. reject).
    - §7 corrects the action-callback body: V1 does NOT inject `device_id`; senders dedupe by their own item_id.
    - §8 testing flow includes the QR scan + dev-mode user_id/pairing_key handoff.
    - §9 updated 410 message ("recipient has no active devices") and added two new common errors: missing-`registerPlugin` and "endpoint not declared".
    - **Validate**: does the doc still claim things the V1 implementation doesn't deliver? Especially around `platform.message.respond` / `onAction` (deferred to V1.5).

## Output

Standard format. Be especially aggressive on:

- **Crypto**: AAD byte-shape reconstruction vs what the sender signs (point 12). One wrong field → silent decrypt failure.
- **Bundle signature gap** (point 13). Document or fix.
- **CardBridge lifecycle** (point 14) — Scope leak.
- **Anywhere a `BuildConfig.DEBUG` relaxation could leak into a release build** (points 8, 11, 14).

Skip cosmetic feedback; the user wants to dogfood tonight.
