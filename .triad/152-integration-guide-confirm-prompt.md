=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 152 — integration guide confirm pass

Codex flagged six FIXes and a polish list on the
integration guide at consultation 151. I applied them at
commit 565cfc5. This is a confirm pass: re-read
`docs/integration-guide.md` end-to-end with the original
goal in mind — a partner project should be able to read
this single file and build a working Syncler integration
without touching the rest of the repo.

## Changes since 151

- **§12 live channel TS sample** — replaced the made-up
  `channel.onMessage / channel.send` callback shape with
  the actual SDK surface (`platform.live.connect(name) /
  send(name, b64) / close(name)` + plugin-side
  `onLiveMessage` hook).
- **§12 sender push** — removed the imaginary
  `client.live_push(...)` snippet; documented the raw
  signed POST contract with body schema.
- **§12 webhook forwarder** — added the actual webhook
  body shape + signature header + retry policy.
  Documented that Python `Client.publish_plugin` does
  not yet expose `live_inbound_url`.
- **§12 operator note** — single-line callout on
  LIVE_BACKPLANE=redis for multi-worker deploys.
- **§13** — added explicit "When to use which" criteria
  for upsert_card vs patch_card.
- **§14** — softened the cadence batching claim to
  match what shipped (gate suppression only; wall-clock
  batched delivery is on the roadmap).
- **§15 (new)** — flagged `native_kotlin` /
  `script_fast` as not-yet-partner-ready.
- **§11** — corrected rotation endpoint URLs
  (`/rotate-master-key/challenge` + `/rotate-master-key`,
  no `/commit`). Softened the "pairing keys rewrap
  transparently" claim with the "if only on lost
  device, re-pair" caveat.
- **Internal labels scrubbed** — V3 #14 / V4 #18 / V4
  #19 / V0.2 / V1.5 tags removed from the "host does
  for you" bullets and section titles. Replaced with
  "on the roadmap" / "today only X" timeless phrasing.

## What I want from you

1. **Are the original 151 FIXes fully addressed?** Spot
   anything I missed, half-fixed, or introduced new
   inconsistencies for.
2. **Voice consistency** — does §12-§15 now read like
   §1-§10 (tutorial / external) or are there still
   bolt-on giveaways?
3. **Self-sufficiency** — is the doc shippable to a
   partner project as the single integration artifact?
   Anything still missing?
4. **Any NEW issues** introduced by the FIX pass that
   weren't there before?

Goal: a clear "yes, ship" or a short list of last
polishes. Cap at 400 words.
