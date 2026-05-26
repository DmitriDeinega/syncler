# V4 #18 — Guided Lost-Device Rotation Flow

Today a user who loses a phone manually navigates:
revoke device → rotate master key → re-pair senders.
V4 #18 wraps this into a security-recovery wizard from a
single entry point.

Spec triad: codex pre-work at consultation 149 (gemini
was quota-blocked, codex-only design pass).

This is a **security-recovery wizard, not a convenience
wrapper** (codex 149 bigger picture). Copy + state
ordering matter because the revoke step is irreversible
and rotation completion is the security gate.

## Entry point

`Settings → Security → "I lost a device"`. Top-level
single entry — no inactivity-driven banners in v0.1
(codex 149 #1: inactivity is ambiguous and risks nudging
users toward destructive action).

## State machine

```
pick_lost_device
       │  (user selects a row)
       ▼
check_rotation_ready
       │  ok                            │  blocked
       ▼                                ▼
confirm_revoke                  rotation_blocked_explain
       │  confirm                       │  (back / try later)
       ▼                                ▼
revoking → rotation_recommended       (exit)
                 │  rotate            │  skip
                 ▼                    ▼
              rotating              done_with_pending_rotation
                 │  ok               (home-screen banner enabled)
                 ▼
              done
```

**Triad 149 codex #2 FIX**: added the explicit
`check_rotation_ready` preflight state. The user MUST
know before the irreversible revoke if rotation
prerequisites are unavailable (master password not
remembered, app version too old, etc.). Otherwise they
revoke the device and then can't complete the security
recovery.

### Per-state notes

- `pick_lost_device` — list of enrolled devices except
  the current one. Show device label + last-seen
  timestamp. NO inactivity highlight (avoid nudging).
- `check_rotation_ready` — quick check: master-key
  rotation endpoints reachable, no rotation already in
  progress for this user (challenge table empty).
  Failure → `rotation_blocked_explain` with a "try again
  later" CTA, NOT a "proceed without rotation" CTA.
- `confirm_revoke` — full-screen "This will revoke
  device X. After revoke we strongly recommend rotating
  your master key — the lost device may have its
  encryption key material." Two buttons: "Continue" /
  "Cancel".
- `revoking` — short spinner; POST
  `/v1/auth/devices/{device_id}/revoke`.
- `rotation_recommended` — copy is direct, not soft
  (codex #2): "The device you revoked had encryption
  keys for your data. Rotate your master key now to
  ensure that data is no longer readable from that
  device's key material." Buttons: "Rotate now" /
  "Skip for now".
- `rotating` — invokes the existing rotation UX. User
  enters master password.
- `done` — summary listing what was completed + a
  concrete CTA: "Review paired senders" deep-linking
  into the existing pairing UI.
  Codex 149 #5 FIX: don't say "Re-pair senders" without
  context. If the app can infer which senders were
  affected (via the device's recipient_envelopes on
  recent messages), show a checklist. Otherwise the
  generic "Review paired senders" CTA.
- `done_with_pending_rotation` — same summary minus the
  rotation tick; flips the home-screen banner ON.

## Interruption + resume

If the user kills the app between revoke and rotate,
the local marker `revoked_device_without_rotation_at`
(SharedPreferences, set on entering
`rotation_recommended`, cleared on completing rotation
or dismissing forever) drives the home-screen banner
on next launch:

> "You revoked a device but didn't rotate your master
> key. Rotate now to complete recovery."

Buttons: "Rotate now" / "Remind me later" /
"Dismiss forever".

**Codex 149 #3 FIX**: timeout is measured in DAYS, not
app launches. After 30 days the banner hides automatically
to avoid eternal nag. The marker persists until cleared
so a re-entry via Settings still works.

## Concurrent flows on multiple trusted devices

Server-side revoke is idempotent (second call returns
200 already-revoked). The rotation flow is single-shot
via the challenge nonce.

**Codex 149 #4 FIX** — UX copy distinguishes:

| Server response | UX copy |
|---|---|
| revoke returns 200 already-revoked | "This device was already revoked." (continue to rotation) |
| rotation 409 challenge already consumed | "Master key was already rotated on another device." (done) |
| rotation 409 stale local challenge (>5min) | "This rotation session expired. Start again." (back to `check_rotation_ready`) |
| rotation 409 in-progress on another device | "Rotation is already in progress on another device. Wait a moment, then refresh." |

Avoid "failure" language when the desired security state
was already reached on another device.

## Re-pair handoff

The flow doesn't initiate re-pairs — it lists what's
affected. Codex 149 #5 FIX:

- If we can infer affected senders (the revoked device's
  recipient_envelopes appeared on recent messages from
  certain senders), show a checklist of those senders
  with a per-row deep-link.
- If we can't infer (no recent traffic), generic
  "Review paired senders" deep-link.

Inferring from recipient_envelopes is a v0.1 best-effort:
the device's inbox already stores past messages with
their recipient_envelope list; cross-reference. Fall back
to generic on any uncertainty.

## Implementation order

1. **Spec digest** (this file).
2. **Android: settings entry point + state machine model.**
   - `LostDeviceFlowViewModel` with sealed-class State.
   - `revoked_device_without_rotation_at: Long?` in
     `SecurityPrefs` (new SharedPreferences-backed store).
3. **Android: Compose screens.** One per state, navigated
   via a flow-scoped NavController.
4. **Android: revoke + rotation orchestration.** Call
   existing endpoints; do NOT re-implement them.
   Map 409 responses to the UX copy table above.
5. **Android: home-screen banner.** Read the local
   marker on `InboxViewModel.onForeground`; show banner
   with three buttons.
6. **Android: re-pair handoff.** Infer affected senders
   from inbox history; fall back to generic deep-link.
7. **Tests.**
   - State-machine unit tests (transitions, terminal
     states).
   - Mocked rotation integration test (challenge → commit).
   - Marker timeout (30-day auto-hide).
   - Server-409-mapping test for each 409 variant.
8. **Post-work triad.**

## Privacy + safety invariants

- The flow MUST NOT prompt the user's master password
  outside the rotation step (no biometric shortcut for
  the password input — shoulder-surfing risk during a
  "I lost my phone" moment is real).
- The flow MUST NOT auto-revoke based on inactivity —
  that's a separate policy decision.
- The "skip rotation" banner MUST time out after 30 days.
- Affected-senders inference uses ONLY local inbox data;
  no server query that could leak which senders the user
  cares about.

## Non-goals

- Auto-rotation triggered by inactivity policy.
- Multi-user "shared lost-device recovery" — out of
  scope.
- Per-device key rotation without master-key rotation —
  V0.2 with per-device envelopes (V1.5 #4).
