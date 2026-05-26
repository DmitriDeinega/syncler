# Syncler Roadmap

The Syncler platform is built and released in milestones. **V1 is what ships today**; the items below it are sequenced by platform dependencies (security primitives first, capability surface second, scaling third, UX last).

This file is the public roadmap. Internal implementation phases (Phase 0 / 1 / 2 / 3 / 4) used in the codebase and consultations track *how V1 was built*; this document tracks *what comes next*.

## V1 — Foundational milestone (shipped)

- [x] Device-bound JWTs with server-side revocation enforcement
- [x] Synced pairing via the encrypted user-state blob (M7 CAS state)
- [x] Server-Sent Events stream for real-time hint delivery (`inbox.changed`, `state.changed`, `dismiss`, `card.upsert`, `card.delete`)
- [x] Native Material 3 template renderer (no-JS plugins via JSONPath manifest)
- [x] Persistent, upsertable Live Cards with server-enforced 48h TTL + sequence-monotone updates
- [x] Host-owned plugin settings sheet (sender mute, pairing revoke). Sender-side plugin-row revoke (`client.revoke_plugin(...)`) is a separate published-side API; the host UI exposes pairing revoke, not plugin-row revoke.
- [x] Cross-device dismiss filter (server-side `DeliveryStatus` join)
- [x] Per-device envelope auth-token isolation for template-renderer action POSTs

## V1.5 — Plugin runtime + key hygiene

1. **Multi-process plugin host.** ~~Move JS plugin execution to a dedicated Android process via AIDL/Binder.~~ **Shipped Phase 10 (13c0a72).** WebView-backed JS plugins now run in `:plugin` subprocess; the host binds via `IPluginSandbox` AIDL. PluginSandboxConnection refcounts a single shared bind. Crash isolation + clean ProcessRecord boundary.
2. **Native Kotlin plugin runtime (out-of-process).** ~~A second plugin format alongside the JS bundle.~~ **Shipped Phase 11 (2913f80 through 255f1c7).** Signed **DEX** loaded into a per-plugin isolated process via `Context.bindIsolatedService(instanceName=sandboxToken)`. Synthesized isolated UID has no INTERNET / no host data access; capability dispatch flows through the same audited AIDL bridge JS plugins use. `:plugin-sdk-runtime` ships `SynclerPlugin` + `PluginContext` (NATIVE_SDK_ABI = 1). `:feature:plugin-native-sandbox` (minSdk 29, isolatedProcess=true) contains the DEX loader, 4MB cap, forbidden-prefix scan (`app.syncler.*` + `android.*` + `androidx.*` + `kotlin.*` + `kotlinx.*` + `java.*` + `javax.*`), 10s onInit timeout, bounded per-plugin coroutine dispatcher (1..4 threads, AbortPolicy). Server schema gains `entry_class` + `native_sdk_abi`. Spec: `docs/plugin-host-native-kotlin.md`. Triad consultations 132–135 (design) + 136 (mid-track review). Native plugins need minSdk 29; JS plugins still minSdk 26 via runtime gate.
3. **Master-key rotation.** UX + protocol for rotating the user's 32-byte master key without losing access to historical state. Required before per-device encryption (#4) lands.
4. **Per-device envelope encryption.** Senders encrypt the payload separately for each of the user's enrolled devices, keyed by per-device public keys instead of the shared user master key. Enables **per-device confidentiality + rotation-free revocation** — compromise of one device's X25519 key doesn't reveal other devices' payloads, and revoking a device is just omitting its `recipient_envelope` on the next publish (no user-wide `master_key` rotation needed). Does NOT provide forward secrecy in the message-by-message sense (that requires ratcheting; out of V1.5 scope). Active server key substitution is out of scope for Phase 9 and tracked under a future device-attestation track. Protocol spec: `docs/crypto-spec.md §11`.
5. ~~**Durable nonce-replay protection.**~~ **Shipped.** Per-sender nonce registry moved from in-memory LRU to a Postgres `nonce_replay` table (composite PK on `(sender_id, nonce)`, atomic INSERT ON CONFLICT DO NOTHING). The replay row commits transactionally with `store_message` / `upsert_live_card` so a failed downstream write rolls back the nonce burn and the sender can retry. Retention pruning (30 days, matching `MAX_RETENTION`) runs hybrid: best-effort on-write batch plus a periodic pass in `app/jobs/retention.py` under a Postgres advisory lock for multi-worker safety. Card-upsert scoped in (defense-in-depth on top of sequence-number CAS). Spec: `docs/crypto-spec.md §7`. Shipped in Phase 7.

## V1.5 — Developer experience (shipped)

DX track surfaced by external plugin-author review (lottery-claude, consultation 68). Phase 4.1 closed the small doc/validator wins; the four items below are the larger pieces that needed protocol design or app-side UX work. All shipped under the Phase 5 cycle (`.triad/70-phase5-agreement.md`).

- [x] **6. Automated pairing handshake.** HPKE-style bootstrap envelope (X25519 + HKDF + AES-GCM, AAD-bound to sender state) replaces the manual `user_id` + `pairing_key_hex` paste. Sender registers an X25519 bootstrap key alongside its Ed25519 signing key (signed at registration); device verifies the signature against the user-confirmed fingerprint, encrypts `(user_id, pairing_key)` to the bootstrap key, POSTs to a sender-operated broker URL. Sender's `Client.wait_for_pairing(...)` polls the broker storage. Protocol spec: `docs/crypto-spec.md §9`. Walkthrough: `docs/integration-guide.md §8.5`. Shipped:
  - Phase 5 agreement: `2de2e4b`
  - Phase 5a-1 (spec + vectors in `docs/crypto-spec.md §9`): `57bb488`
  - Phase 5a-2 (server + Android crypto + SDK protocol foundation): `a9fe84e`
  - Phase 5a-2.1 (Android pairing UX + FastAPI broker app + integration-guide §8.5): `7e5ec4d`
- [x] **7. Full round-trip example plugin.** `examples/trading-bot/` ships both a plugin and a sender in one directory, sharing `state.json`. `python bot.py register` → `pair` → `publish-plugin` → `ack-server` + `loop` produces a real card on a paired device with an Acknowledge button that POSTs back to the bot. Initial round-trip shipped in Phase 5b (`893d783`); Phase 6 migrated `pair` to the automated V1.5 bootstrap flow (in-process broker thread sharing `InMemoryBrokerStorage`, `Client.wait_for_pairing(timeout_seconds=120)`). The `set-pairing` subcommand remains as a V1 manual fallback for offline / no-broker setups.
- [x] **8. `npm create @syncler/plugin` scaffold.** Stdlib-only Node generator (`create-plugin/index.js`) prompts for plugin id, name, sender id, renderer, card type, capabilities, and emits a complete starter project (`src/plugin.ts`, `manifest.json`, `build.sh`, `package.json`, `README.md`, `.gitignore`). Generated manifest passes `validatePluginManifest` in lenient mode; `build.sh` walks parents to find `sdk-plugin/src/index.ts` for V0.1 monorepo dev (npm distribution of `@syncler/plugin-sdk` is V2). Shipped in Phase 5c (`4604bce`).
- [x] **9. Validator polish backlog.** Five-item batch on `sdk-plugin/src/manifest.ts` + `server/app/schemas.py`: 6-digit semver component cap on `minPlatformVersion`; canonical-IPv4 lowercase-only printable-ASCII regex for `template.actions[].endpoint` schemes (HTTPS always, HTTP only on `localhost` / 10.x / 172.16-31 / 192.168.x / 127.x — five review cycles to get SDK/server byte-equivalent across WHATWG vs urlparse quirks); `template.fields` keys restricted to layout's allowed set; strict `$.field(.subfield)*` grammar for `cardKeyPath`; SDK↔server `plugin_identifier` regex parity. Shipped in Phase 5d (`cf7af9b`).

## V2 — Capability surface

10. ~~**Plugin capability expansion.**~~ **Shipped Phase 12** (spec at `docs/plugin-capability-expansion.md`, design triads 137+138, mid-track triad 139). Server validates the location-split (`location.coarse` / `location.fine`, legacy `location` rejected). Android ships: `CapabilityGrantStore` + `CapabilityHandleStore` (SQLCipher grants table; file-backed staging cap 16 MB / 32 per-plugin / 5 min TTL via SystemClock.elapsedRealtime); `CapabilityActivityCoordinator` (AndroidX ActivityResultRegistry, one global activity-result op at a time, survives config change); 4 bridges (LocationBridge: platform LocationManager single-fix returning OS-actual precision; FileBridge: ACTION_OPEN_DOCUMENT with synchronous content URI → staging copy; GalleryBridge: AndroidX PickVisualMedia/PickMultipleVisualMedia with atomic multi-staging up to 10 items; CameraBridge: ACTION_IMAGE_CAPTURE via FileProvider, immediate tempfile delete); `platform.fileBytes` + `platform.releaseHandle` for stateless seek-and-read; per-invocation audit log without coordinates/filenames/handles. `NATIVE_SDK_ABI` bumped 1 → 2. Outstanding for full closeout: per-plugin grant-list + revoke settings UI (codex 139 FIX, deferred to UX review).
11. **`message.respond` + `showNotification`.** Promote action callbacks from a fire-and-forget POST to a request/response handshake the plugin can await. Add a `showNotification` bridge so non-inbox-mode flows (background alerts) work.
12. **Template renderer layouts.** Expand beyond `standard_card` to `compact_row`, `score_card`, and a handful of other layouts driven by data-class types. Each new layout ships with golden render tests.
13. **Script-fast runtime (Javy/QuickJS).** A lighter, non-WebView execution mode for purely logic-heavy plugins that don't need DOM. Faster cold-start and less memory than the current WebView path.

## V3 — Two-way + scaling

14. **WebSocket two-way channel.** Bidirectional, low-latency channel between plugins and their backends; covers chat, live cursors, presence. Complements but does not replace the SSE hint channel for V1's pull-then-render model.
15. **`platform.live.subscribe(...)` for script plugins.** API for a script bundle to subscribe to a named feed and receive incremental updates without polling.
16. **Field-level live subscriptions on templates.** Update specific template fields without re-rendering the whole card — the live-card path today is whole-card upsert; this adds a finer grain.
17. **Redis pub/sub for SSE scaling.** Move the in-process event bus to a shared backplane so the SSE fanout works across multiple FastAPI workers and pods.

## V4 — UX polish

18. **Guided lost-device rotation flow.** Click-through UX for: revoke lost device → re-pair on trusted devices → rotate master key (depends on V1.5 #3). Today the user does steps 1–2 by hand; this automates the sequence.
19. **Per-plugin user preferences.** Notification cadence, label overrides, delivery-window scheduling, do-not-disturb integration — all surfaced from the host's settings sheet without plugin-side code.

---

Cross-reference: implementation-level work tracked in `.triad/50-agreement-and-plan.md`. Plugin authors should read `docs/integration-guide.md` and `docs/crypto-spec.md` — those two documents are the public contract.
