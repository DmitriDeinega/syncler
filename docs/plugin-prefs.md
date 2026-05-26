# V4 #19 — Per-Plugin User Preferences

Per-(user, plugin) settings sheet for notification cadence,
label override, quiet hours, and per-plugin mute. **Plugin
code is NOT involved** — pure host policy surfaced from the
host's settings sheet.

Spec triad: codex pre-work at consultation 149 (gemini
quota-blocked).

## Storage

Inside the **encrypted user-state blob** alongside
`paired_senders` and `dismissed_ids`. Syncs cross-device
automatically via the existing M7 CAS state lockstep.

Trade-off: blob grows with active plugin count. Currently
small for v0.1; long-term sharding deferred until measured
bloat.

**NOT** server-visible state. **NOT** device-local-only.

## Identity key

The encrypted user-state already carries
`pluginSettings: Map<plugin_identifier, PluginSettings>`
(granted_capabilities + dismiss_behavior_override) keyed
by the manifest's `id` field. V4 #19 EXTENDS that existing
PluginSettings row with the new prefs fields rather than
introducing a parallel map.

**v0.1 scope decision**: identity = `plugin_identifier`
alone. Codex 149 #7 preferred `{sender_id}/{plugin_identifier}`
for the case where two senders ship plugins with the same
manifest id; in v0.1 we accept the (already-existing)
trade-off — manifest ids are unique-by-convention and the
two-senders-same-manifest collision is rare enough that
extending to a compound key for v0.1 would be churn for no
present benefit. The compound key is V0.2 if real collisions
materialize.

`plugin_identifier` survives: row UUID recreation on the
same sender + manifest, re-pair of the sender on a new
device, server-side migration. It DOES collide if two
distinct senders publish with the same manifest id —
documented as a v0.1 known limitation.

## Schema

V4 #19 EXTENDS the existing `plugin_settings` map (which
already held `granted_capabilities` + `dismiss_behavior_override`)
rather than adding a parallel top-level map. The key is the
manifest's `plugin_identifier` per the existing pattern (see
"Identity key" section).

```jsonc
{
  // existing user-state fields (paired_senders,
  // dismissed_messages, muted_senders, …) unchanged.
  "plugin_settings": {
    "<plugin_identifier>": {
      "granted_capabilities": ["network", "storage"],   // pre-V4
      "dismiss_behavior_override": null,                 // pre-V4
      "modified_at": "2026-05-26T22:00:00Z",             // pre-V4
      "label_override": "Optional Name",                 // V4 #19
      "notification_cadence": "realtime|batched_15m|batched_1h|digest_daily",
      "quiet_hours": {
        "enabled": true,
        "start_local_hour": 22,
        "end_local_hour": 7,
        "timezone": "America/New_York"
      },
      "muted": false
    }
  }
}
```

### Field semantics

- **`label_override`**: free-form string, 64-char cap. Falls
  back to the plugin manifest's `name` field when absent or
  empty. Trimmed of whitespace.
- **`notification_cadence`**: enum of 4 values.
  - `realtime` — every event fires a notification (default).
  - `batched_15m` — events accumulate; one notification per
    user per **wall-clock-aligned 15-minute boundary**
    (codex 149 #7: wall-clock-aligned, NOT rolling, so
    notifications arrive predictably).
  - `batched_1h` — same on wall-clock 1-hour boundary.
  - `digest_daily` — single notification per local-time day
    at a user-configurable hour (default 09:00).
- **`quiet_hours`**: optional struct.
  - `enabled` — false → no quiet hours.
  - `start_local_hour` / `end_local_hour` — 0-23 local hour.
    Window wraps midnight if start > end.
  - `timezone` — IANA tzdata string. Default = device tz at
    save time.
- **`muted`**: per-plugin mute. Independent of per-sender
  mute (which exists today). Both must be false for
  notifications to fire.

### Forward-compat parsing (codex 149 #10)

- Missing `plugin_prefs` key → default empty dict.
- Unknown cadence value → fall back to `realtime` (the
  safest default — user sees everything).
- Unknown future fields → **preserved** on read; **echoed**
  on writeback. The device that introduced them is
  authoritative.
- Don't delete `plugin_prefs` entries when the plugin is
  temporarily unavailable — the prefs sleep with the row.

## Quiet-hours implementation

**Device-side intercept in the notification adapter**
(codex 149 #8). NOT server-side (server can't see
encrypted prefs anyway); NOT OS DND (too coarse,
doesn't respect per-plugin policy).

**Critical distinction**: quiet hours suppress
**notifications**, not **data ingestion**. The
`InboxRepository.refresh()` + SSE event stream keep
running; only the OS-level notification
(`NotificationCompat`) is gated. The user opens the inbox
on next foreground and sees all the missed updates.

## Cadence batching

Wall-clock-aligned windows:
- `batched_15m` → :00, :15, :30, :45.
- `batched_1h` → :00 every hour.
- `digest_daily` → 09:00 (default; user-settable).

A pending notification queue keyed by `plugin_identity`
buffers events received during the window; at the boundary
boundary a single notification fires with a count and the
most recent event's summary.

WorkManager or AlarmManager schedules the boundary wake-up.
Cancelled cleanly when the cadence is changed to `realtime`
or the plugin is muted.

## Discoverability

**Top-level `Plugins` tab in Settings** (codex 149 #9).
Sender settings sheet stays focused on the trust
relationship (pairing revoke, sender-wide mute). Plugins
are not the same thing as senders — a sender publishes
multiple plugins.

Settings → Plugins shows a list of all paired plugins
across all senders, grouped by sender. Tapping a row opens
the per-plugin sheet.

## Cross-device CAS behavior

The user-state blob already uses **M7 CAS state** (compare-
and-set with state_version). prefs changes ride this
existing path:

1. Device A reads state at version=N.
2. Device A modifies `plugin_prefs[<key>]`.
3. Device A POSTs the encrypted blob with `state_version=N+1`.
4. If server's current is N+1 (Device B beat them to it),
   server returns 409 stale; Device A re-fetches, re-applies
   its delta on top of the new state, retries.

**Codex 149 bigger picture**: cross-device merge regression
risk is the main thing to test. The retry-on-409 path must
apply Device A's delta on a freshly-fetched state, NOT on
Device A's cached (stale) state. Otherwise concurrent
edits silently overwrite.

Implementation: in `UserStateRepository.applyDelta`, the
retry helper re-reads the blob and re-applies the
caller's mutation lambda on the fresh value.

## Implementation order

1. **Spec digest** (this file).
2. **Android: extend user-state schema.** Add
   `plugin_prefs` map to the `UserState` data class +
   Moshi adapter. Forward-compat parsing rules.
3. **Android: prefs repository.** `PluginPrefsRepository`
   reads from / writes through `UserStateRepository`
   using the existing CAS retry helper.
4. **Android: settings UI.**
   - `PluginsSettingsScreen` — list of all paired plugins.
   - `PluginPrefsSheet` — sheet with controls per field.
   - Time-range picker for quiet hours.
5. **Android: notification adapter integration.** Read
   prefs before showing each notification; gate on mute
   + quiet hours; enqueue for batching when cadence !=
   realtime.
6. **Android: WorkManager scheduling.** Periodic
   alignment-boundary work; cancel on cadence change.
7. **Tests.**
   - Schema round-trip with `plugin_prefs` present /
     absent / with unknown fields.
   - Quiet-hours interval check (in-window vs out, midnight
     wrap, timezone).
   - Cadence batching boundary alignment.
   - CAS merge: Device A's prefs change survives Device B's
     concurrent dismissed-id append (the canonical
     concurrent-edit case).
8. **Post-work triad.**

## Privacy + safety invariants

- Plugin code MUST NOT have access to user prefs (would
  let a plugin learn that the user labeled it differently
  or has quiet hours — leak vector).
- Server MUST NOT see plain prefs (encrypted in user-state
  blob).
- Per-plugin mute MUST NOT block data ingestion — only the
  user-visible notification.
- Cross-device prefs sync MUST use the existing CAS
  retry path; no separate sync endpoint.

## Non-goals

- Federated "shared plugin defaults" across users.
- Plugin-author-suggested default prefs in the manifest.
- Per-plugin notification SOUND / vibration override
  (would need OS-level NotificationChannel-per-plugin;
  V0.2).
- Cross-plugin batching ("digest of all plugins
  combined") — V0.2.
- DND integration via `NotificationManager.is*Mode()` —
  device-side quiet hours is the v0.1 answer.
