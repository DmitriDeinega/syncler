=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 150 — V4 post-work review

Triad 149 was the pre-work design pass for V4 #18 (guided
lost-device rotation) + V4 #19 (per-plugin user prefs).
Gemini was quota-blocked on that pass. Both specs landed at
docs/lost-device-flow.md + docs/plugin-prefs.md.

V4 closes the roadmap: ROADMAP.md V4 entries flip to
shipped at b8e8746 + b4bdd26 + d71fd87 + c4b1ef5 + a7085be.

This consultation is the post-work / final-shipped review
for both items together. Items can be flagged
independently.

v0.1 dev posture preserved.

## Commits in scope

| Commit  | Item | Content |
|---------|------|---------|
| b8e8746 | 149  | .triad archive (V4 design pre-work) |
| d3da9f5 | spec | docs/lost-device-flow.md + docs/plugin-prefs.md |
| a7085be | #19  | PluginSettings schema extension (V5 → V6) + tests |
| c4b1ef5 | #19  | PluginNotificationGate + service wiring + 11 gate tests |
| d71fd87 | #19  | PluginPrefsRepository + cross-device CAS merge test |
| b4bdd26 | #18  | LostDeviceFlowViewModel + SecurityPrefs + days-TTL banner tests |

## Files in primary review scope

### V4 #19 — Per-plugin preferences

- `android/core/storage/.../EncryptedUserState.kt` —
  PluginSettings extended with labelOverride, notificationCadence
  (enum + 4 const), quietHours (QuietHours data class),
  muted, unknownFields. Schema_V6 forward-migrate.
- `android/core/storage/.../StateMerger.kt` — unchanged;
  the existing "later modifiedAt wins" rule covers
  PluginSettings merge.
- `android/core/push/.../PluginNotificationGate.kt` —
  Decision sealed surface (ALLOW / MUTED / QUIET_HOURS /
  BATCHED). Pure decide() exposed for tests. Quiet-hours
  evaluation in the user's SAVED timezone (not device's
  current tz). Mute beats quiet-hours beats batched in
  precedence.
- `android/core/push/.../PluginNotificationService.kt` —
  gate.shouldPost gates notifications.post on every FCM
  delivery.
- `android/feature/inbox/.../PluginPrefsRepository.kt` —
  typed writers (setMuted / setLabelOverride / etc.).
  Label override 64-char cap + trim. Unknown cadence
  rejected via require(). Always rewrites modifiedAt at
  write time.
- `android/core/push/.../PluginNotificationGateTest.kt` —
  11 unit tests including saved-tz vs device-tz, wrap-
  midnight, empty window, precedence rules.
- `android/core/storage/.../StateMergerTest.kt` — V4 #19
  cross-device merge test, unknown-field preservation,
  unknown-cadence fallback.

### V4 #18 — Guided lost-device flow

- `android/feature/settings/.../LostDeviceFlow.kt` —
  LostDeviceFlowViewModel + sealed-class State (9
  states + 4 Failure variants). Orchestrates
  SynclerApi.revokeDevice + RotationRepository.rotateHygiene.
- `android/feature/settings/.../SecurityPrefs.kt` —
  EncryptedSharedPreferences-backed marker; days-based
  TTL with auto-clear on read.
- `android/feature/settings/.../SecurityPrefsTest.kt` —
  5 unit tests on the marker's TTL + clear semantics.
- `docs/lost-device-flow.md` — spec.
- `docs/plugin-prefs.md` — spec.

## Privacy / contract claims

1. **Plugin code MUST NOT access user prefs.**
   PluginNotificationGate sits in :core:push, owns the
   read of UserStateMutator.state.value. Plugin SDK
   never sees the prefs map. ✓
2. **Server MUST NOT see prefs in plaintext.** prefs
   ride inside the existing encrypted user-state blob,
   wrapped under the user's master key. Server stores
   the AEAD output only. ✓
3. **Per-plugin mute MUST NOT block data ingestion.**
   The gate suppresses the user-visible notification
   only; PluginMessagePipeline still runs to completion
   so the inbox row + live-card update lands. The user
   sees the missed update on next inbox open. ✓
4. **Cross-device CAS merge regression.** PluginSettings
   merge picks later-modifiedAt whole-row (last writer
   wins). Tests cover the concurrent-edit scenario
   (mute on Device A, quiet hours on Device B → B wins
   whole row). v0.1 trade-off documented. ✓
5. **Banner timeout is days-based, not launches.**
   SecurityPrefs auto-clears the marker once 30 days
   elapse. Tests verify the boundary. ✓
6. **Lost-device flow password hygiene.** rotateNow
   takes CharArray; both success and failure paths
   fill it with spaces before returning. ✓

## Specific concerns I'd like flagged

1. **PluginSettings identity = `plugin_identifier` only.**
   Codex 149 #7 preferred `{sender_id}/{plugin_identifier}`
   for the case where two senders ship the same manifest
   id. I shipped the existing `plugin_identifier` key
   shape (matches the pre-V4 pluginSettings map) and
   documented the v0.1 limitation. Acceptable for v0.1
   or worth migrating to the compound key now?

2. **Notification gate's runtime read is `state.value`,
   not a fresh fetch.** A user changes prefs on Device A
   while a notification is in-flight on Device B; B
   gates against its locally-cached prefs (which may be
   minutes stale until next CAS sync). Acceptable for
   v0.1 (notifications are hints, not load-bearing
   security) or worth a refresh on hot-path?

3. **Quiet hours evaluation in SAVED timezone.** A user
   sets quiet hours in NY, then travels to Tokyo with the
   device's tz auto-updating to Asia/Tokyo. Their NY
   quiet hours still apply at NY-local hours. Intentional
   per spec, but worth re-confirming this is the right
   semantic.

4. **Wrap-midnight quiet window edge cases.** start==end
   = empty window per spec (not all-day). Tests cover
   this. But a user could TYPE-error their settings into
   that state — UI should validate before save. v0.1 the
   PluginPrefsRepository accepts whatever QuietHours
   struct it's given. Worth adding validation, or accept
   "trust the UI" for v0.1?

5. **PluginNotificationGate's Hilt dep is UserStateMutator.**
   That's the existing interface bound from :feature:inbox.
   FCM service is in :core:push which only depends on
   :core:storage. The interface lives in :core:storage so
   the dep chain works (no cyclic dep). Spot-check this
   is clean.

6. **PluginPrefsRepository sets `modifiedAt = now` on every
   write.** Two devices that legitimately want to write the
   SAME prefs row to the SAME value (idempotent) would
   bump the timestamp and cause unnecessary state-blob
   churn. Acceptable v0.1?

7. **LostDeviceFlowViewModel's 409 mapping is coarse.**
   Server returns 409 with `detail.error` discriminator;
   v0.1 always maps to RotationExpired (the most
   actionable). Codex 149 #4 wanted three distinct
   states (already-completed / in-progress / expired).
   v0.1 ships one; richer parsing is V0.2 once the
   server response shape is JSON-structured. Acceptable
   or block on V0.2?

8. **CheckRotationReady is a no-op short-hop.** Spec said
   "preflight that rotation prerequisites are reachable".
   v0.1 transitions straight to ConfirmRevoke because
   RotationRepository is always bound. A genuine remote
   probe (e.g. a HEAD on /v1/account/rotate-master-key/
   challenge) would catch outage states. Worth the
   complexity v0.1, or V0.2?

9. **CharArray zeroing on rotateNow.** I zero on both
   success + failure paths via `currentPassword.fill(' ')`.
   But the password also lives in any CharBuffer / view
   the UI passed in; the ViewModel can't reach into
   those. Worth flagging that the UI screen must also
   zero its own buffer after handing off to rotateNow.

10. **SecurityPrefs is in :feature:settings.** Other
    features (e.g. an Inbox-side banner that reads the
    marker) would need to depend on :feature:settings or
    a new dedicated module. v0.1 the home-screen banner
    is also in the settings flow itself; cross-feature
    consumers TBD. Worth restructuring now or wait for
    a concrete consumer?

11. **Deferred items**:
    - V4 #19 Settings UI (Plugins tab) — V0.2.
    - V4 #19 WorkManager batched-cadence boundary
      schedules — V0.2.
    - V4 #18 Compose screens — V0.2.
    - V4 #18 affected-senders inference from inbox — V0.2.
    All flagged in the commit messages + ROADMAP.

## Test status

```
:core:storage:testDebugUnitTest          — all green (incl. V4 #19 merge + forward-compat)
:core:push:testDebugUnitTest             — all green (11 gate tests)
:feature:settings:testDebugUnitTest      — all green (5 SecurityPrefs TTL tests)
:feature:inbox:testDebugUnitTest         — all green
server pytest                             — BLOCKED on PG dev-env (V4 has no new server-side code)
```

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus anything you spot that would block V4 close.

Focus on:
- Cross-device CAS merge correctness (V4 #19 — the main
  regression risk codex flagged at 149).
- Privacy invariants around prefs (no server visibility,
  no plugin visibility).
- Banner timeout correctness (V4 #18 days-based TTL).
- Rotation password-handling hygiene.
- State machine completeness for the lost-device flow
  (any state transitions I missed?).

Skip cosmetics; flag substance. Goal: greenlight V4 close
+ ROADMAP retirement of #18 #19 (or a clear FIX list to
apply first).
