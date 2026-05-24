# Syncler Roadmap

This roadmap outlines the planned evolution of the Syncler platform. The order is driven by platform dependencies, with foundational infrastructure leading the way.

## Phase 4: Foundational Milestone (V1)
- [x] Device-bound JWT + revocation enforcement
- [x] Synced pairing via encrypted user-state blob
- [x] SSE event stream for real-time delivery
- [x] Native Material 3 template renderer
- [x] Persistent, upsertable "Live Cards"
- [x] Host-owned plugin settings sheet (mute/revoke)

## Phase 5: Multi-Process & Security (V2)
1. **Multi-process plugin host**: Move plugin execution to a dedicated Android process via AIDL/Binder. Essential for isolation and stability.
2. **Native Kotlin plugin runtime**: Support for plugins written in native code for maximum performance and platform access.
3. **Master-key rotation**: Allow users to rotate their master encryption key without losing access to historical data.
4. **Per-device envelope encryption**: Senders encrypt separately for each device, enabling true forward secrecy and immediate device revocation without key rotation.
5. **Durable nonce-replay protection**: Move nonce tracking from memory to persistent storage on the server to prevent replay attacks across worker restarts.

## Phase 6: Expanded Capability Bridge
6. **Capability expansion**: Add support for `camera`, `storage`, `file`, `location`, and `gallery` to the plugin bridge.
7. **`message.respond` & `showNotification`**: Allow script plugins to trigger native notifications and use a request-response pattern for actions.
8. **Script-fast (Javy/QuickJS) runtime**: A faster, lighter-weight alternative to the full WebView for logic-heavy plugins.

## Phase 7: Advanced Real-time & Scaling
9. **WebSocket / Two-way live channel**: Support for low-latency, bidirectional communication between plugins and backends.
10. **`platform.live.subscribe(...)`**: API for script plugins to subscribe to specific real-time data feeds.
11. **Field-level live subscriptions**: Allow template fields to update independently without re-rendering the whole card.
12. **Redis pub/sub for SSE scaling**: Scale the SSE event bus across multiple server workers using Redis.

## Phase 8: UX & Refinement
13. **Guided lost-device rotation flow**: Automated UX for re-pairing and key rotation after a device is marked lost.
14. **Per-plugin user preferences**: Advanced settings like custom notification cadence, custom labels, and delivery schedules.
