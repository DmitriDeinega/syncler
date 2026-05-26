=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 158 — V3 #14 inbound-completeness bug fixes (design pre-work)

Three bugs flagged across triads 152 / 153 / 155 / 156 +
my own code-read. Together they break the V3 #14 inbound
path end-to-end: server frames don't wrap correctly,
device can't auth into the WS, and the TS SDK can't
receive the frame even if it arrived. Each bug is small
in isolation; they have to land together to make
`platform.live.*` actually usable for partners.

v0.1 dev posture preserved.

## Bug 1 — Server push doesn't wrap into a multiplex frame

**Symptom**: `POST /v1/live/plugin/{plugin_row_id}/push`
serializes `body.envelope` directly and publishes the raw
V2 envelope JSON to the user-scoped hub topic. The WS
endpoint's `pump_pushes` task forwards each topic message
to the device via `websocket.send_text(payload)` unchanged.

Device-side `LiveChannelClient` (Android) parses
`frame.optString("type")` to dispatch — the unwrapped
envelope has no `type` field, so the parsing treats it as
unknown/invalid.

**Spec** (`docs/live-channel.md §"Outer envelope (all frames)"`):
every server → device frame must be shaped as:

```json
{
  "channel": "...",
  "type": "message",
  "id": "...",
  "payload": "<base64 V2-style envelope bytes>"
}
```

**Proposed fix**:
1. `LivePushRequest` gains a `channel: str` field. Validation: matches the channel regex `^[a-zA-Z0-9._-]+$`, length ≤ 64.
2. The push handler wraps the envelope before publishing:
   ```python
   frame = {
       "type": "message",
       "channel": body.channel,
       "id": uuid.uuid4().hex,        # server-generated; ack-able if sender wants
       "payload": base64.b64encode(envelope_json.encode()).decode(),
   }
   await hub.publish_ephemeral(plugin_topic(...), json.dumps(frame, separators=(",", ":")))
   ```
3. Existing tests in `test_v3_14_ws_endpoint.py` + Android `LiveChannelClient` unit tests should now exercise the wrapped shape.

Q: should the body keep accepting `{"envelope": {...}}` and require channel at the URL/body level, OR should we promote the shape to `{"channel": ..., "envelope": {...}}`?

I lean toward the latter (`{"channel": ..., "envelope": {...}}`) — channel is routing metadata, not part of the encrypted payload.

## Bug 2 — Android LiveBridge JWT provider is a placeholder

**Symptom**: in `PluginLoader.android(...)` the factory's
`deviceJwtProvider` lambda throws `LiveChannelException("no_session", ...)`
unconditionally. Any plugin call to `platform.live.connect`
fails before reaching the network.

**Proposed fix**:
1. `PluginLoader.android(...)` takes a `Session` (from `:core:auth`) as a constructor param.
2. The factory's `deviceJwtProvider` reads
   `session.deviceAccessToken()` (or whatever the
   shipped method is — the function returns the
   current valid device JWT or null).
3. If the token is null (locked / not logged in), the
   provider throws `LiveChannelException("no_session", ...)`
   honestly — but as the actual locked-state condition,
   not a "wiring incomplete" placeholder.

Q: does the Session class already expose a synchronous-
or-suspending getter that returns a non-null JWT when the
session is unlocked + the user is signed in? If yes,
which one? Reading the auth surface is in scope before
the fix.

Cross-impact: `PluginLoader.android(...)` already takes a
`scope: CoroutineScope` parameter. Adding `session: Session`
matches the existing constructor shape. Callers of
`.android(context, scope)` need updating — there's only
one (the app composition root, currently unfound in grep
— V3 #14 wiring is itself not yet hooked into a real
caller, which is why this bug was easy to defer).

## Bug 3 — TS SDK has no `onLiveMessage` dispatch

**Symptom**: `sdk-plugin/src/bridge.ts:11` defines
`DispatchHook = 'onMessage' | 'onAction' | 'onDismiss' | 'render'`.
No `onLiveMessage`. The Android `LiveBridge.kt` calls
`plugin.dispatchHook("onLiveMessage", payload, channel.name)`
on every inbound live frame, but the TS bridge's
`dispatch` switch (lines ~53-80) has no `case 'onLiveMessage'`.
Frames silently dead-end at the dispatch step.

`BasePlugin` (`sdk-plugin/src/base-plugin.ts`) also
doesn't declare an `onLiveMessage` hook for subclasses to
override.

**Proposed fix**:
1. Extend `DispatchHook` with `'onLiveMessage'`.
2. Add a case in the bridge's dispatch switch:
   ```ts
   case 'onLiveMessage':
       return await registeredPlugin.onLiveMessage(
           args[0] as string, // channel name
           args[1] as string, // envelope base64
       );
   ```
3. Add an `onLiveMessage` method on `BasePlugin` with a
   no-op default body (so existing subclasses don't need
   to override it to keep working):
   ```ts
   async onLiveMessage(_channel: string, _envelopeBase64: string): Promise<void> {
       // override to receive live frames
   }
   ```
4. Re-enable the integration-guide §12 plugin-side snippet
   to show the `onLiveMessage` override (removed in
   triad 153 because the hook wasn't shipped).

Cross-impact: Android `LiveBridge.kt` line 192 already
passes `(payload, channel.name)` — that's (envelopeJson,
channelName). I'd flip the arg order in the bridge call
to `(channelName, envelopeBase64)` so it matches the
JS-side parameter order (channel first, payload second).
Need to check that the host call site can be changed
without breaking other consumers.

Actually — looking at this more carefully, the host
currently passes the envelope as raw JSON string, not
base64. The TS side would receive a JSON string. For
consistency with the outbound shape (`platform.live.send(channel, envelopeBase64)`),
should the inbound also be base64? I think yes — same
binary-safe encoding both directions.

That means the Android `LiveBridge` needs to base64-encode
the envelope bytes before dispatchHook (it currently
already does this:
`android.util.Base64.encodeToString(event.envelopeBytes, NO_WRAP)`).
So the inbound path already produces base64.

## What I'm asking for

For each of the three bugs:

1. Is the proposed fix correct against the shipped code
   you read? Anything I missed?
2. Are there hidden cross-impacts I should plan for?
   (Existing tests that need updating, other call sites
   I haven't found, migration concerns.)
3. Is the design choice in any Q above the right call,
   or would you take a different shape?

Plus: do these three together close the V3 #14 inbound
gap, or is there a fourth thing I'm missing?

Format: numbered verdicts (OK / NIT / FIX / DESIGN) per
bug + a "bigger picture" closer. Cap at 600 words.
