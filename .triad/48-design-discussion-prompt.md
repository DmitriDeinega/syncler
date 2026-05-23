# Consultation 48 — design discussion, full agreement before any plan

Syncler is in active local development. After consultations 46 (four
expansion ideas) and 47 (synced pairing + lost-device threat model), I
worked through every piece with the project owner in detail. The owner
has explicitly asked that this consultation be a full open design
discussion across both reviewers, not a validation of a pre-written
plan. The implementation plan should be the *output* of triad agreement,
not the input.

So treat this as a working session. Each of the topics below has a
tentative position from the previous discussions. For each one:

1. **Agree, disagree, or propose an alternative.** Be specific about
   what's right or wrong in the framing.
2. **Surface anything I missed.** Hidden costs, latent bugs, contradictions
   with consultations 35 / 36 / 46 / 47.
3. **Propose the smallest correct implementation** for what the consensus
   should be — but don't write the full plan yet. We'll consolidate into
   a plan after agreement is reached.

Both reviewers, please answer the same set of topics independently. After
you both respond, I'll either iterate (if you disagree) or write up the
consensus implementation plan (if you align).

---

## Topic 1 — Pairing model

**Tentative position:** Switch from per-device pairing to *synced
pairing* via the M7 encrypted user state blob. One QR scan on any of
the user's devices propagates the 32-byte pairing key + sender identity
to all the user's enrolled devices, encrypted under the user's master
key, CAS-merged the same way read-marks already are. Tombstone-on-revoke
semantics (`removedAt` field) to prevent resurrection from offline
devices.

**Why we moved here:** today's per-device model forces the user to
scan the QR separately on every device, and the sender has to maintain
N pairings per user. UX cost is real; cryptographic containment is
"naturally per-device" but only as a side effect of crypto, not as
explicit access control.

**Open questions:**
- Schema location for `pairedSenders` (the blob already holds
  `readMessages` / `archivedMessages` / `deletedMessages`).
- StateMerger semantics: per-pairingId merge with "later removedAt
  wins"; "oldest firstPairedAt wins" on add conflict. Right?
- Migration: do we push existing local pairings up on first launch
  after the schema bump, or wait for next pairing event?
- Same-`senderId` multiple-active-pairings transition state (devices
  paired separately before sync). How does
  `InboxRepository.refresh()` handle it — try all active keys for a
  given senderId until one decrypts? Tombstoned ones skipped?

## Topic 2 — Lost-device threat model

**Tentative position:** Synced pairing makes one device's loss
potentially leak all paired senders' keys. We accept this tradeoff
because the alternative (per-device pairing) has its own UX cost and
its containment was already weak. The user-facing story is:

- **Immediate:** revoke device on Settings → server stops delivering
  state / inbox / SSE / FCM to it.
- **Full forward-secrecy:** user manually re-pairs each sensitive
  sender on a trusted device. Re-pair generates a new pairing key,
  tombstones the old, sender starts encrypting with the new key.
- **Past cached data on the lost device is unrecoverable.** Documented
  honestly.
- A guided one-tap rotation flow + master-key rotation + envelope
  encryption are explicitly *future roadmap*, not this milestone.

**Open questions:**
- This threat model relies on revoke-device actually closing access at
  the network layer. Today's server doesn't enforce `revoked_at` on
  `/v1/state` or `/v1/messages/inbox` — they only check the user's
  JWT. Both reviewers flagged this in consultation 47 ("JWT zombie").
  How should we fix this? Embed `device_id` in the JWT and reject
  revoked devices in `current_user`-equivalent? Force re-login on
  first deploy after the change? Anything subtler?
- Should the integration guide's lost-device section be more
  prescriptive (e.g., "for high-sensitivity senders, here's the
  exact rotation procedure") or just explanatory?

## Topic 3 — Transport: SSE + FCM hybrid

**Tentative position:** Remove the 15-second inbox poll. Replace with:

- **SSE event stream** while app is in foreground. Carries
  `inbox.changed`, `state.changed`, `dismiss`, `live.update`,
  `card.upsert` events. Pull on event hint.
- **FCM** for background wakeup (already wired; unchanged).
- **Pull on lifecycle events** (cold start, app resume, user refresh).

**Why:** polling burns battery and server load. SSE gives sub-second
foreground freshness for the cost of one long-lived HTTP connection.
FCM still does background — no engineering trick gets around Doze /
App Standby on Android.

**Open questions:**
- One-way SSE vs WebSocket: the discussion landed on "SSE is enough
  for V1; two-way WebSocket reserved for plugins that declare
  bidirectional needs, deferred until a real plugin asks." Is this
  the right line, or should we just build WebSocket from the start?
- Server event-bus implementation: in-process pub/sub
  (`dict[user_id, set[Queue]]`) for now, Redis pub/sub when scale
  demands. Anything in this that breaks early?
- SSE handshake auth: device-bound JWT (from Topic 2) checked at
  handshake; server-initiated disconnect on device revocation so the
  stream closes immediately rather than waiting for JWT expiry. Are
  there auth-recheck patterns we should be using for the long-lived
  connection that aren't covered by handshake-only?
- Phones without Google Play Services don't get FCM. Acceptable for
  now (the app still works on app resume), or do we need a fallback
  signal path (long-poll endpoint? user-configurable
  self-hosted-push?) before claiming Android coverage?

## Topic 4 — Plugin runtimes

**Tentative position:** Three runtime options, all shipping in this
milestone:

- **Template** (declarative manifest, host-rendered Compose, zero
  plugin code) — the primary path for non-interactive cards.
- **Script** (JS in WebView, signed Ed25519, current behavior) —
  interactive cards.
- **Script-fast** (Javy / QuickJS-via-WebAssembly, no DOM) — opt-in
  perf path for compute-heavy JS that doesn't need DOM rendering.

Plus an explicit *future-roadmap* commitment:

- **Native Kotlin** — only ever via multi-process AIDL/Binder
  isolation, never in-process `ClassLoader`. Published as a roadmap
  item with the architecture pre-committed.

**Why:** templates absorb the bulk of "I don't want JS" demand,
script-fast covers the "JS is too slow" case, and pre-committing to
AIDL/Binder for native prevents the in-process shortcut from ever
being tempting.

**Open questions:**
- Template DSL surface area: how strict do we start? Field paths +
  escaped text + small fixed layout set + actions only? Is the layout
  set (`standard_card`, `compact_row`, `score_card`) right for V1, or
  should we start with one and expand?
- Script-fast: when QuickJS runs the plugin, what does the rendered
  output look like? The plugin returns HTML for a thin host WebView,
  or returns a structured "render model" (JSON-ish tree) that the
  host renders natively? The latter is safer but constrains plugin
  authors more.
- Manifest field: `renderer: "template" | "script" | "script-fast"`
  with `"script"` as default for backwards compat? Is "script-fast"
  the right name?
- Is there any case where the host should refuse to publish a
  template that does X (e.g., template referencing payload fields
  that aren't declared, missing required fields, malformed JSONPath)?
  Publish-time validation level?

## Topic 5 — Card model: event vs live

**Tentative position:** Two card types coexist:

- **Event card** (current behavior, `card_type: "event"`, default).
  Immutable once sent. Each event = one new row.
- **Live card** (`card_type: "live"`). Singleton per `(sender, user,
  card_key)`. Server stores latest payload only; subsequent upserts
  replace. Use cases: live sports scores, current weather, status
  dashboards. Rate-limited at the server (~1 Hz per card_key). TTL
  (default 48h from last update), then auto-expires.

**Why:** "current state" cards are a different cognitive model from
event cards. Soccer matches shouldn't generate one row per goal;
matches *are* a single thing whose state changes over time.

**Open questions:**
- Endpoint: new `POST /v1/cards/upsert` separate from
  `POST /v1/messages/send`? Or extend `send` with an optional
  `card_key`? Separate endpoint is cleaner; extending `send` is
  fewer concepts.
- Encryption / AAD: nonce per upsert (mandatory — never reuse), AAD
  includes `card_key` so a replay can't be redirected to a different
  card. Anything else nonce-related?
- TTL: 48h default seems reasonable for matches / daily statuses.
  Configurable per card via manifest or message? Or fixed for V1?
- Drawer UX: separate "Live" filter entry in the navigation drawer.
  Mixed view ("All") shows live cards pinned above events?
  Chronological merge? Triad: opinion on inbox ordering.
- Rate limit: 1 Hz per card_key the right shape? Sender can send
  faster locally and the server collapses; sender gets a 429 if it
  tries to push faster than the cap? Latter is harsher but cleaner.

## Topic 6 — Live updates as a property of templates

**Tentative position:** Template fields can declare a `live` property:

```json
{
  "fields": {
    "score": {
      "path": "$.score",
      "live": {
        "topic": "matches/{$.match_id}/score",
        "format": "raw"
      }
    }
  }
}
```

When a template card's detail view is open, the host subscribes to each
live field's topic on the existing SSE stream and patches the field in
place when an event arrives. Plugin author writes zero subscription
code.

**Why:** this composes templates and live into a near-free feature.
For the script-plugin equivalent (`platform.live.subscribe(topic,
callback)` capability), defer to future roadmap until a real script
plugin asks.

**Open questions:**
- Topic interpolation syntax: JSONPath inside `{...}`? Or a smaller
  DSL?
- Format options: `raw` / `number` / `currency` / `time` / `date`?
  What does `currency` even mean without locale info?
- When a live update arrives but the detail view isn't open: drop
  it? Cache last-seen and patch on next open? Affects whether the
  user opens a card after 5 minutes of background and sees stale
  values or fresh.
- Subscription scope: per-field-with-live, or per-card (one
  subscription multiplexed)? Latter is fewer sockets but requires
  topic filtering on the client.

## Topic 7 — Host-owned settings surface

**Tentative position:** Per-plugin settings live in a host-rendered
surface, never in plugin chrome.

- Long-press inbox row → bottom sheet with: plugin info / mute on this
  device / revoke pairing.
- Detail view: thin host-drawn top bar with overflow icon → same
  sheet.
- Senders tab continues to exist as the "all paired senders" overview.

**Why:** the user can always reach mute / revoke / info from the
host's UI, regardless of plugin runtime or renderer. Plugins can't
hide these affordances. Same UX across every plugin.

**Open questions:**
- "Mute on this device" lives in a new local store
  (`MutedSendersStore`, EncryptedSharedPreferences, NOT synced).
  Is non-sync the right default for mute? It feels like a per-device
  preference but I can imagine arguments for sync.
- The thin host top bar above the detail-view WebView steals ~48dp
  from script plugins. Acceptable, or do we accept inconsistency
  (host bar for template, no host bar for script)?
- Where do future per-plugin preferences go (notification cadence,
  custom labels, refresh schedule)? Same sheet, expanded over time?
  Separate "Plugin settings" full-page surface when there are >N
  options?

## Topic 8 — Phasing and the "fix latent bugs" question

**Tentative position:** This work breaks naturally into four phases.
After each phase: triad review → fix-ups → commit → next phase. No
sequencing across phases; each is independently shippable.

- **Phase 0:** Device-bound JWT + revocation enforcement on state /
  inbox / events. The lost-device threat model is half-fiction
  without this.
- **Phase 1:** Synced pairing.
- **Phase 2:** SSE event stream + polling removal.
- **Phase 3:** Plugin model — templates + live-as-field-property +
  live cards + host-owned settings surface + script-fast (Javy).
- **Phase 4:** Documentation + future-roadmap publish.

**Open questions:**
- Is Phase 0 actually a precondition for Phase 1 (synced pairing
  trusts that revocation works at the network layer), or can they
  ship in parallel?
- Codex Reviewer B in consultation 47 mentioned a possible existing
  CAS dirty-flag bug — `push()` may clear the dirty flag on
  conflict-retry failure today. Verify before Phase 1 or after?
- Architecture cleanup: `UserStateRepository.mutateLocal` is private
  in `:feature:inbox`; `PairedSenderStore` is in `:core:storage`. They
  can't reach each other directly. Triad: move ownership / expose
  interface / new module?
- Phase 3 is the biggest. Should it split internally (templates →
  live-field → live cards → settings → script-fast as sub-commits)?
  Or one large set?

## Topic 9 — Future roadmap published with this milestone

**Tentative position:** When this work ships, the integration guide
publishes an explicit roadmap of what's next:

- Multi-process plugin host via AIDL/Binder.
- Native Kotlin runtime on top.
- Full capability bridge (camera, storage, file, location,
  message.respond, showNotification).
- Two-way live channel (WebSocket) for plugins that declare
  bidirectional needs.
- `platform.live.subscribe(...)` API for script plugins.
- Guided one-tap lost-device rotation flow.
- Per-device envelope encryption.
- Per-plugin user preferences (notification cadence, custom labels,
  schedule).

**Why:** signals platform seriousness to plugin authors. Security model
of native is pre-committed (no in-process shortcut). Removes the "we'll
get to it eventually" vagueness.

**Open questions:**
- Is the roadmap order I've listed right? Anything that should move
  earlier or later relative to each other?
- Is there anything else that should be on the roadmap but isn't?
- Anything currently on the "future" list that should actually be
  this milestone?

---

## Output

For each topic, in this order:

1. Your independent position. Agree / disagree / alternative.
2. Specific concerns or hidden costs.
3. The smallest correct implementation you'd recommend if the
   tentative position holds (or your alternative if it doesn't).

Keep it focused. After both of you respond, I'll either iterate on
disagreements or roll the consensus into an implementation plan.

We've done the deep design work already across consultations 46 and 47;
this is the final-agreement pass before code touches the repo.
