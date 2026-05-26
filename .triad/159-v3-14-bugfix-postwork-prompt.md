=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 159 — V3 #14 bugfix post-work review

Triad 158 captured three V3 #14 inbound-path bugs +
codex added a fourth (onLiveError / onLiveClosed also
dead-end on the TS side). All four shipped at:

- ab41a5c — V3 #14 inbound: three bugs fixed (triad 158)
- d6b4d6a — docs: integration guide folds the bugfix surfaces

This is the post-work triad: was the implementation
correct, and is the V3 #14 inbound path now actually
usable end-to-end?

## Files in primary review scope

### Server (bug 1)
- `server/app/routers/live.py` —
  `LivePushRequest` gained `channel: str` (validated
  against the WS-side regex `^[a-zA-Z0-9._-]+$`, ≤64).
  `push_live` now wraps the envelope in a `{type:
  "message", channel, id: uuid.hex, payload: base64}`
  frame before publishing to the user-scoped hub topic.
  Inner-envelope cap reduced to `(MAX_FRAME_BYTES * 3 //
  4) - 64` so the base64-inflated outer frame stays
  under the wire ceiling.

### Android (bug 2)
- `android/feature/plugin-host/build.gradle.kts` —
  added `implementation(project(":core:auth"))`.
- `android/feature/plugin-host/.../PluginLoader.kt` —
  `android(...)` factory gained `session: Session? = null`.
  The `deviceJwtProvider` lambda now calls
  `session.currentToken()` (the existing `AuthTokenProvider`
  method); throws `LiveChannelException("no_session", ...)`
  honestly when locked / signed out, or when no Session
  was wired by the caller. Audit-log reason was updated
  to "no Session wired" so the placeholder path doesn't
  silently masquerade.

### SDK (bugs 3 + 4)
- `sdk-plugin/src/bridge.ts` — DispatchHook union
  extended with `onLiveMessage | onLiveError | onLiveClosed`.
  The dispatch switch parses the single JSON-string
  positional arg Android sends (`{channel, envelope}`
  for message, `{channel, code}` for error,
  `{channel}` for closed) and fans out as positional
  args to the registered plugin. Added a small
  `parseLivePayload` helper.
- `sdk-plugin/src/base-plugin.ts` — three new methods
  with no-op default bodies (`onLiveMessage`,
  `onLiveError`, `onLiveClosed`) so existing plugins
  don't need to override.
- `sdk-plugin/test/base-plugin.test.ts` — 3 new tests:
  default hooks return undefined; dispatcher parses
  JSON payload + fans out as positional args;
  malformed payloads (non-JSON, missing required
  field) reject with TypeError;
  onLiveError + onLiveClosed full routing.

### Docs
- `docs/integration-guide.md` — §12 "V0.1 wiring caveat"
  box removed; push body example updated with `channel`
  field; plugin snippet shows `onLiveMessage` /
  `onLiveError` / `onLiveClosed` overrides; top-of-file
  "host does for you" bullet updated.

## Privacy / contract claims

1. **Server stays content-blind.** The push handler
   wraps the envelope as `payload: base64(envelope_json)`
   inside a routing frame, but the envelope itself stays
   opaque — server doesn't decode or inspect. Devices
   base64-decode + open as before.
2. **Channel name regex enforced at both ends.** The push
   handler rejects bad channel names with 400, mirroring
   the WS frame parser's `_valid_channel_name`. No way
   for a server-side push to inject a frame on a channel
   shape devices wouldn't have opened.
3. **Auth pipeline unchanged.** `Session.currentToken()`
   was already the device JWT used by every authenticated
   request; the live channel now uses the same auth
   source, NOT a new one.
4. **TS dispatch parses + asserts string types.** The
   `parseLivePayload` helper rejects non-JSON or
   non-object payloads with TypeError; downstream
   `asString` calls reject when expected fields are
   missing or wrong-typed. Tests cover both paths.
5. **Plugin's existing hooks unchanged.** Adding cases
   to the DispatchHook union is additive; the four
   existing hooks (onMessage / onAction / onDismiss /
   render) have identical dispatch behavior. 62 SDK
   tests green including the 8 base-plugin tests.

## Specific concerns I'd like flagged

1. **Inner-envelope cap math.** `(MAX_FRAME_BYTES * 3 //
   4) - 64` reserves 64 bytes for the frame's structural
   overhead (`type`, `channel`, `id`, key names, quotes,
   commas). At MAX_FRAME_BYTES=65536 the inner cap is
   ~48944 bytes. Is that the right reservation? A long
   channel name eats into it but channels are capped at
   64 chars and the overhead is dominated by the field
   keys.

2. **Frame `id` is server-generated.** Every push frame
   gets `uuid.uuid4().hex`. Senders that want to
   correlate ack frames to their pushes can't today —
   they don't see the id. Acceptable v0.1 (the push
   endpoint already returns its own response synchronously
   so senders don't need ack correlation) or worth
   accepting an optional client-provided id?

3. **Session is nullable on the factory.** The factory
   `android(context, scope, livePatchSink = NoOp,
   session = null)` keeps the throwing placeholder when
   the caller didn't pass a session. Acceptable for tests
   + minimal builds but a production composition root
   that forgets the `session=` arg silently loses live
   channel. Worth making it required, or accept the
   warn-loud default?

4. **TS payload parsing rejects via TypeError.** A
   malformed frame from a misbehaving host bridge throws
   inside `dispatch`, which propagates up to the Android
   side as a Promise rejection. The Android `dispatchHook`
   call swallows the failure with a warn log
   (PluginInstance.dispatchHook line 60 — `runCatching`).
   Net: malformed frames are dropped silently from the
   plugin's perspective. Acceptable, or should the SDK
   side log too?

5. **No new server tests.** The bug 1 server change is
   logic-only (frame wrapping + cap reduction); the
   existing `test_v3_14_ws_endpoint.py` smoke tests
   don't cover `push_live` because they don't have a
   live socket to verify delivery against. A new test
   would need either a real WebSocket client or the
   hub's in-process pubsub. PG still blocks pytest end-
   to-end. Worth writing the unit test that asserts the
   frame shape produced by `push_live` against the hub
   even without PG?

6. **No Android unit tests for the JWT-provider plumbing.**
   The behavior is "wired Session returns the token /
   null Session keeps the throwing placeholder". A
   single-file factory unit test would be valuable but
   the existing PluginLoaderTest is integration-shaped.
   Worth adding now or accept "wiring is small enough
   to spot-check"?

## Test status

```
sdk-plugin: vitest run → 62/62 green
:feature:plugin-host:testDebugUnitTest → green
:core:storage:testDebugUnitTest → green (unchanged surface)
:core:push:testDebugUnitTest → green (unchanged surface)
:feature:inbox:testDebugUnitTest → green (unchanged surface)
server pytest → still PG-blocked (no new tests added)
```

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus: did I close the V3 #14 inbound gap fully, or is
there a 5th thing? Was the implementation correct vs
the spec?

Skip cosmetics; flag substance. Cap at 500 words.
