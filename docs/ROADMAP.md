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

1. **Multi-process plugin host.** Move JS plugin execution to a dedicated Android process via AIDL/Binder. Isolates a crashing or runaway plugin from the host UI process and gives us a clean ProcessRecord boundary for the OS to kill independently.
2. **Native Kotlin plugin runtime (out-of-process).** A second plugin format alongside the JS bundle: a signed AAR loaded into the isolated process. Performance + platform-API parity for plugin authors who want a native experience, with the IPC boundary preventing in-process ClassLoader poisoning.
3. **Master-key rotation.** UX + protocol for rotating the user's 32-byte master key without losing access to historical state. Required before per-device encryption (#4) lands.
4. **Per-device envelope encryption.** Senders encrypt the payload separately for each of the user's enrolled devices, keyed by per-device public keys instead of the shared user master key. Enables forward secrecy and immediate per-device revocation without rotating user-wide keys.
5. **Durable nonce-replay protection.** Move the per-sender nonce registry from in-memory to Postgres so a worker restart can't accept a replayed envelope.

## V2 — Capability surface

6. **Plugin capability expansion.** Add `camera`, `storage`, `file`, `location`, `gallery` to the bridge with per-grant prompts. Each capability lands with its own audit-log + user-visible permission dialog.
7. **`message.respond` + `showNotification`.** Promote action callbacks from a fire-and-forget POST to a request/response handshake the plugin can await. Add a `showNotification` bridge so non-inbox-mode flows (background alerts) work.
8. **Template renderer layouts.** Expand beyond `standard_card` to `compact_row`, `score_card`, and a handful of other layouts driven by data-class types. Each new layout ships with golden render tests.
9. **Script-fast runtime (Javy/QuickJS).** A lighter, non-WebView execution mode for purely logic-heavy plugins that don't need DOM. Faster cold-start and less memory than the current WebView path.

## V3 — Two-way + scaling

10. **WebSocket two-way channel.** Bidirectional, low-latency channel between plugins and their backends; covers chat, live cursors, presence. Complements but does not replace the SSE hint channel for V1's pull-then-render model.
11. **`platform.live.subscribe(...)` for script plugins.** API for a script bundle to subscribe to a named feed and receive incremental updates without polling.
12. **Field-level live subscriptions on templates.** Update specific template fields without re-rendering the whole card — the live-card path today is whole-card upsert; this adds a finer grain.
13. **Redis pub/sub for SSE scaling.** Move the in-process event bus to a shared backplane so the SSE fanout works across multiple FastAPI workers and pods.

## V4 — UX polish

14. **Guided lost-device rotation flow.** Click-through UX for: revoke lost device → re-pair on trusted devices → rotate master key (depends on V1.5 #3). Today the user does steps 1–2 by hand; this automates the sequence.
15. **Per-plugin user preferences.** Notification cadence, label overrides, delivery-window scheduling, do-not-disturb integration — all surfaced from the host's settings sheet without plugin-side code.

---

Cross-reference: implementation-level work tracked in `.triad/50-agreement-and-plan.md`. Plugin authors should read `docs/integration-guide.md` and `docs/crypto-spec.md` — those two documents are the public contract.
