# Consultation 46 — four expansion ideas, pre-design discussion

Syncler is in a stable post-M11 V1 state: encrypted senders push cards,
the Android host renders inbox rows from `hostPreview` natively and
runs the plugin's `render()` in a WebView for detail. Background:

- Plugins ship as Ed25519-signed JS bundles, loaded by the host
  WebView per detail-tap. Bundle resolution is by `plugin_row_id`
  (historical row) via `/v1/plugins/by-id/{row_uuid}`.
- Capability bridge: `platform.network.fetch` is the only wired
  primitive in V1 inbox mode; storage / camera / notifications /
  message.respond all reject pending the multi-process plugin host.
- Device enrollment is global: one device row per phone, shared
  across senders. Each sender pairs separately (encrypted via the
  user's pairing key) but all paired senders deliver to all the
  user's active devices.
- Revocation classified into superseded / compromised / sender_disabled
  / unspecified, with monotone severity promotion and per-reason UX.
- Send is HTTPS POST today, polling pull on the device every ~15s,
  optional FCM wake-up. There is no live/streaming channel for
  per-card state (e.g. live odds, in-progress draws).

We have four expansion ideas on the table. I want to pressure-test
each one before investing in any of them. Please walk through each,
independently — what's right about it, what's broken or naive, what
the hidden costs are, what the smallest version that ships value
looks like, and whether you'd prioritize it ahead of others.

## Idea 1 — MQTT-over-WebSocket for live state

Currently the only delivery path is HTTPS POST + pull polling /
FCM wake. For "live" cards (a lottery card showing live odds,
or "draw results just arrived for batch #5" as a host-side
notification adjacent to the card), the 15s poll feels wrong.

Proposal: stand up an MQTT broker behind WebSocket. The plugin's
WebView can subscribe natively (MQTT.js client) to topics scoped
to its `(user_id, plugin_row_id, item_id)` triple. The same channel
could carry host-side "ambient" notifications keyed off the same
plugin, surfaced as a small banner or as a row update.

Open questions:
- Topic ACL: the broker has to be content-blind like the rest of
  Syncler. How do we authenticate the subscriber when the WebView
  is sandboxed and the device's keypair isn't directly exposed to
  the bundle?
- Battery: persistent WebSocket vs. existing pull/FCM. Worth it?
- Is per-card live state actually within V1.5 scope, or is this
  V2?

## Idea 2 — Kotlin native runtime

Today every plugin is a WebView-rendered JS bundle. Some plugin
authors might want a faster / more native experience and don't
need DOM at all. Proposal: manifest declares
`runtime: "webview" | "native"`. A native bundle ships as an AAR
or compiled DEX (same signing + hash flow), is loaded into a
sandboxed `ClassLoader`, and uses the same `platform.*` capability
primitives exposed as a Kotlin API instead of a JS bridge.

Open questions:
- Class-loader sandboxing on Android — what's the actual isolation
  model? `PathClassLoader` + a restricted classloader parent isn't
  the same security boundary as a WebView's process isolation.
  Can a native bundle reach into the host process's heap?
- iOS story is dead — Android-only runtime. Acceptable?
- What's the smallest API surface that's actually useful? Just
  `platform.network.fetch` parity in Kotlin, or the full
  storage/camera bridge?
- Native bundles are arch-specific. AAB / multi-arch distribution
  on a content-blind transport is awkward.

## Idea 3 — Per-plugin connected devices

User feedback: "why do I have so many devices?" + "is lottery
authorized on which of my phones?" Today device enrollment is
global — one row per phone, every paired sender delivers to all
active devices.

Proposal: keep the global device list but add a per-plugin
authorization matrix on top: "Lottery is authorized on: Pixel 7,
iPad". The plugin's settings panel (inside the plugin's detail
chrome) shows the matrix and lets the user revoke per-plugin per
device. Revoking a device from one plugin doesn't touch others.

Open questions:
- This conflicts with our current "the user is one identity, paired
  senders deliver to all their devices" model. Is the value worth
  the complexity?
- Where does the matrix live? Server-side (new table
  `plugin_device_authorization(plugin_row_id, device_id, authorized_at)`)
  or client-side (device-local filter on inbound messages)?
- If it's server-side, the server needs to know which plugin a
  message is for, which it already does, but routing decisions get
  more complex.
- If it's client-side, every device receives every message and
  filters locally — wastes encrypted-blob bandwidth.
- "Plugin chrome with its own settings" was explicitly deferred
  from V1 (consultation 35's "plugin-owned chrome" V1.5 item). Is
  this the right re-entry for that, or do we need a different
  surface?

## Idea 4 — Constrained template DSL for non-interactive plugins

Today every plugin is a signed executable JS bundle. For
non-interactive cards ("a notification arrived: here's the title,
subtitle, body, a couple buttons"), shipping executable JS is
overkill — the publish flow has key management, hash pinning, etc.

Proposal: manifest declares `renderer: "template" | "script"`.
Template renderer is a sandboxed Mustache-like DSL evaluated by
the host. No signing of executable code — the template lives in
the manifest itself (which is still signed) and references payload
fields via `{{field}}` syntax. Buttons map to declared endpoints
the same way. `renderer: "script"` stays for interactive cards
that need real logic.

Open questions:
- What's the actual DSL surface? Mustache is famously
  underpowered — no conditionals beyond truthiness, no
  computation. Is that strict enough to be safe, or too strict to
  be useful?
- The host evaluates the template, so it has to keep its
  evaluator small and well-tested. Risk of expanding the DSL one
  feature at a time until it's a buggy interpreter (Greenspun's
  10th).
- Does this displace `script` for most senders, or stay a
  side-channel for the simplest cases? If most senders move to
  template, the JS bridge becomes long-tail code.

## Output

For each idea, independently:
1. **Is the framing right?** What about the user's pitch is correct,
   what's naive?
2. **What would the MVP actually look like?** Concrete: what code
   gets written, what contracts change, what existing tests break.
3. **Hidden costs / risks** — security, complexity, deferred work
   that becomes urgent.
4. **Priority recommendation** — V1.5, V2, or "don't do this".
   Why.
5. **What dependencies between the four exist** — e.g., does idea 4
   shrink the surface area idea 2 needs to support?

Then rank all four overall. Short justifications, not essays.
