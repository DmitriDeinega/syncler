=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 149 — V4 design pre-work

V3 closed (V3 #14 live channel, V3 #15 SDK sugar, V3 #16
card.patch, V3 #17 Redis backplane). The roadmap's last
tier is V4 — UX polish — with two items:

> 18. **Guided lost-device rotation flow.** Click-through UX
>     for: revoke lost device → re-pair on trusted devices →
>     rotate master key (depends on V1.5 #3). Today the user
>     does steps 1–2 by hand; this automates the sequence.
>
> 19. **Per-plugin user preferences.** Notification cadence,
>     label overrides, delivery-window scheduling, do-not-
>     disturb integration — all surfaced from the host's
>     settings sheet without plugin-side code.

Both are device-side UX work primarily. Server-side touch
is light: #18 reuses the existing rotation endpoints, #19
adds a per-plugin prefs blob (most likely inside the
already-encrypted user-state).

v0.1 dev posture preserved.

## What's already in place

**Master-key rotation (V1.5 #3) shipped Phase 8d.**
- `server/app/routers/rotation.py` — POST /v1/account/rotate-
  master-key/challenge → POST .../commit
- `server/app/models.py`: RotationChallenge table (single-
  use, ~5 min TTL)
- `docs/crypto-spec.md §10` — full protocol spec
- Android: the rotation UX exists but is currently a
  technical settings screen; #18 is the wrapping flow that
  starts from "I lost a device".

**Device revocation already works.**
- POST /v1/auth/devices/{device_id}/revoke (admin-side)
- Settings UI has a "Sign out other devices" path.
- Triad 144 #7 closes WS sockets on revoke via the
  control-lane revocation event.

**Sender mute + pairing revoke also exist.**
- Per-sender mute lives in the host UI (settings sheet per
  paired sender). Persists in the device-local SQLCipher
  inbox DB.
- Pairing revoke is in the same sheet.

**Encrypted user-state blob is the per-user sync substrate.**
- `users.encrypted_state` (Postgres) + the V1.5 #3 lockstep
  master-key-rotation protocol.
- Already carries paired sender records (`paired_senders`)
  and recently-dismissed message IDs. Adding plugin prefs
  here means they sync cross-device automatically.

**No plugin-level prefs surface today.**
- `Plugin` model has no per-user-prefs column. Per-(user,
  plugin_row_id) prefs would be new state.

## V4 #18 — Guided lost-device rotation

Goal: today a user who loses a phone does:
1. Sign in on another trusted device.
2. Go to Settings → Devices → tap "Revoke" on the lost row.
3. Separately, navigate to "Rotate master key" because the
   lost device might have leaked key material.
4. Re-pair the lost device's senders on a replacement.

We want a single guided flow that orchestrates 1-3 (and
flags 4 as a follow-up).

### Open design questions

**1. Entry point.** Settings → Security → "I lost a device".
Or a top-level "Lost a device?" banner that appears after
N days of one device being inactive? V0.1: explicit
Settings entry. Confirm?

**2. State machine.** Proposed states:
- `pick_lost_device` — list of enrolled devices except this
  one; user taps the lost row.
- `confirm_revoke` — full-screen "This will revoke device
  X. Continue?" with the device label + last-seen.
- `revoking` — calling /v1/auth/devices/{device_id}/revoke;
  short.
- `rotation_recommended` — explain why rotation is the
  right move (the lost device had key material); two
  buttons: "Rotate now" / "Skip for now".
- `rotating` — invokes the existing rotation UX; user
  re-enters their master password (the wrap key derivation
  needs it).
- `done` — summary card listing what was done + a
  "Re-pair senders" CTA pointing into the existing pairing
  flow.

Is there anything missing? Skip-for-now should be discoverable but
discouraged.

**3. Interruption + resume.** If the user kills the app
between revoke and rotate, do we resume the flow on next
launch? V0.1: the revoke is atomic at the server, so the
rotation can be a separate re-entry point. Banner on home
screen "You revoked a device but didn't rotate; rotate
now?" — accept or dismiss-forever.

**4. Concurrent flows on multiple trusted devices.** Two
remaining devices race the "I lost a device" flow at the
same time. The server-side revoke is idempotent
(second call returns 200 with already-revoked).
Rotation is single-shot via challenge nonce — the second
device gets a stale-challenge 409. Surface "Rotation already
in progress / completed on device X" cleanly.

**5. Re-pair handoff.** After rotation, the user's senders
that lived on the lost device need to be re-paired on a
new device. The flow surfaces this as a follow-up but
doesn't actually initiate it (the user opens each sender
individually). Acceptable, or worth more guidance?

### Risks
- The rotation flow already requires the user's master
  password. We MUST NOT prompt for it any other way (no
  biometric shortcut); shoulder-surfing risk during a
  high-stakes "I lost my phone" moment is real.
- We MUST NOT auto-revoke based on device "inactivity" —
  that's a different policy decision that the user might
  not want.
- The "skip rotation" banner must time out at some point
  (after N days, hide automatically) so a user who really
  doesn't want to rotate doesn't see it forever.

## V4 #19 — Per-plugin user preferences

Goal: surface a per-plugin settings sheet (notification
cadence, label override, quiet hours) the user controls.
**Plugin code is not involved** — pure host policy.

### Open design questions

**6. Storage location.**

Options:
- (a) Inside the encrypted user-state blob alongside
  paired_senders → syncs cross-device automatically via
  the existing M7 CAS state.
- (b) New Postgres `plugin_prefs` table (user_id,
  plugin_row_id, prefs_json) — needs a sync endpoint.
- (c) Device-local SQLCipher only — no cross-device sync.

Recommendation: (a). The encrypted state blob is already
designed for low-frequency per-user settings, and we want
cross-device sync.

Trade-off: blob size grows with active plugin count.
Currently small (paired_senders + dismissed_ids). For
v0.1 acceptable. Long-term: split into shards if it
balloons.

**7. Prefs schema.**

```json
{
  "plugin_prefs": {
    "<plugin_row_id>": {
      "label_override": "Optional Name",
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

Per-field decisions:
- `label_override`: free-form, 64 char cap. Falls back to
  the plugin manifest's `name` field.
- `notification_cadence`: enum of 4 values for v0.1.
- `quiet_hours`: optional. When set, the host's SSE
  consumer (`InboxRepository`) drops/queues
  `inbox.changed` hints during the window; the inbox row
  still updates on next foreground pull.
- `muted`: already exists today as a per-sender setting;
  per-plugin mute is similar but per-row.

Is the cadence enum too coarse? Should batched windows
align to wall-clock 15m/1h boundaries or rolling from
each receipt? Rolling is what most users expect
intuitively.

**8. Quiet-hours implementation.**

Three places it can intercept:
- Server: the SSE / live-channel publish path checks
  prefs before fan-out. NO — server doesn't see user
  prefs in plaintext, the encrypted-state blob is
  device-side.
- Device, between SSE and notification: the
  `EventStreamManager` / `InboxRepository` discards
  notifications during quiet hours but still updates the
  inbox row. ✓
- Device, between notification and OS: rely on Android's
  Do Not Disturb. ✗ — that's user-OS-level, doesn't
  reflect per-plugin policy.

V0.1: device-side intercept in the Android notification
adapter. Confirm?

**9. Discoverability.**

Settings sheet per plugin: today the host UI has a
"Settings" sheet for each PAIRED SENDER (with mute,
revoke pairing). Plugins are not the same thing as
senders — a sender publishes multiple plugins. Do we
add a "Plugins" subsection inside the sender sheet?
Or a separate "Plugins" tab in Settings? Recommendation:
new "Plugins" tab top-level — keeps the sender sheet
focused on the trust relationship.

**10. Migration / first-launch.**

Existing users have no prefs blob. The plugin_prefs key
is absent from their encrypted state on first launch
of v0.1. Default = empty dict; settings sheet shows
"use defaults" until the user changes anything. Trivial
forward-compat.

### Anti-requirements

- **NOT** giving the plugin code access to user prefs. A
  plugin must not learn that the user prefers a different
  label or has quiet hours — that's a leak vector.
- **NOT** introducing a new server-side encrypted blob
  for prefs. The user-state blob is the established
  pattern.
- **NOT** federating prefs across users (no "shared
  plugin defaults"). Each user owns their own.

## Implementation order — proposed slicing

For #18:
1. Spec digest at `docs/lost-device-flow.md`.
2. Android: settings entry point + state machine model.
3. Android: Compose screens for each state.
4. Android: revoke + rotation orchestration.
5. Android: home-screen banner for "rotation skipped".
6. Tests: state-machine unit tests, mocked rotation
   integration test.
7. Post-work triad.

For #19:
1. Spec digest at `docs/plugin-prefs.md`.
2. Android: extend user-state schema with `plugin_prefs`
   map.
3. Android: Compose screens — plugins list + per-plugin
   sheet.
4. Android: quiet-hours intercept in the notification
   adapter.
5. Android: cadence batching logic.
6. Tests: schema round-trip, quiet-hours interval check,
   cadence batching.
7. Post-work triad.

Both items can ship independently (#19 has no #18
dependency).

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus anything you'd reshape entirely (e.g. "store prefs
per-device, not in the synced blob", "don't bundle
cadence + quiet hours in one sheet", "do
re-pair-handoff inside the lost-device flow not as a
follow-up").

Format: numbered verdicts + a bigger-picture take at the
end. Goal: a focused spec digest I can write for each.
