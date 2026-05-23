# Consultations 48 + 49 — final agreement and implementation plan

This document is the output of full triad agreement after consultations
46 (four expansion ideas), 47 (synced pairing + lost-device gap), 48
(open design discussion), and 49 (second pass on disagreements).

Both reviewers (Codex with internal Reviewer A/B perspectives, and
Gemini) signed off. The plan below is what will be built, phase by
phase, with a triad code review after each phase.

---

## Pre-flight (before any new state fields land)

**P0a — Fix CAS dirty-flag bug in `UserStateRepository.push()`.**
Codex Reviewer B in consultation 47 flagged that the conflict-retry
failure path logs but does not throw, so `push()` can clear the dirty
flag after a failed retry, losing pending pushes. Fix: ensure the
dirty flag is only cleared after successful CAS upload, not after
the retry-then-give-up path. Add regression test that simulates a
permanent 409 conflict and verifies `dirty` remains true.

**P0b — Architecture: expose state-mutation interface from `:core:storage`.**
`UserStateRepository.mutateLocal` is currently private in
`:feature:inbox`. Synced pairing needs it from `:core:storage`
(where `PairedSenderStore` lives). Expose a small interface in
`:core:storage` that `:feature:inbox` implements. No new module.

---

## Phase 0 — Device-bound JWT + revocation enforcement

**Why first:** the lost-device threat model relies on `revoke_device`
actually closing network access. Today, `/v1/state`,
`/v1/messages/inbox`, and message detail only check the user JWT,
not `device.revoked_at`. Synced pairing's value collapses without
this.

### Server changes

- **JWT claims now include `device_id` (`did`).** Issued at
  `/v1/auth/login` after device enrollment, and refreshed at
  `/v1/auth/devices/enroll` so newly-enrolled devices get a
  device-bound token.
- **New auth dependency `current_auth_context` in `app/auth.py`.**
  Resolves user + active device. Rejects with 401 if:
  - JWT missing `device_id` claim.
  - Device with that ID doesn't exist.
  - Device's `revoked_at IS NOT NULL`.
- **Apply `current_auth_context` to all sensitive routes**:
  `GET /v1/state`, `PUT /v1/state`, `GET /v1/messages/inbox`,
  `GET /v1/messages/{id}`, `POST /v1/messages/{id}/dismiss`,
  `GET /v1/pairing`, `POST /v1/pairing/{id}/revoke`,
  `GET /v1/plugins/by-id/{id}` only when user-bound paths are
  added.
- **Login flow:** `/v1/auth/login` no longer returns a JWT directly;
  it returns a short-lived "session bootstrap" token. The client
  uses that to enroll the device, and the enroll response is the
  device-bound JWT. (Or: enroll happens as part of login and the
  login response includes the device-bound JWT in a single call.
  Whichever has fewer moving parts; specifics in build phase.)
- **Pre-existing JWTs without `device_id` are rejected.** Clients
  intercept 401 with `WWW-Authenticate: device_required` and force
  re-login. One-time pain.

### Android changes

- **Session stores `deviceId`** parsed from JWT claims at login.
- **`AuthRepository.login()` performs enroll-and-bind** so the
  returned session token already includes `device_id`.
- **401 with `device_required` triggers re-login flow** (clear
  session, navigate to AuthScreen).
- **Existing API call sites unaffected** — they continue to pass
  the JWT in `Authorization: Bearer`; the server just enforces more.

### Tests

- Revoking a device immediately stops it pulling state, inbox,
  message detail.
- A device's JWT issued before its revocation is rejected after
  revocation (same JWT, different `revoked_at` state).
- Pre-deploy migration: clients with pre-Phase-0 JWTs get a
  `device_required` 401, triggering re-login.
- Login + enroll return a device-bound JWT in a single round-trip.
- The CAS dirty-flag bug regression test from P0a.

### Success criteria

- All triad-flagged endpoints reject revoked devices in integration
  tests.
- Existing read-mark / archive / delete sync flows continue to work
  end-to-end with the new auth.
- CAS dirty-flag regression test passes.

### Out of scope

- Synced pairing field (Phase 1).
- SSE endpoint (Phase 2, also uses `current_auth_context`).
- Native runtime, capability bridge expansion, etc.

---

## Phase 1 — Synced pairing via M7 encrypted state blob

### Schema + merger

- `EncryptedUserState` gains
  `pairedSenders: List<PairedSenderEntry>`. Fields:
  - `pairingId` (stable per-pairing UUID)
  - `senderId`, `senderName`, `senderPublicKey` (b64), `fingerprint`,
    `nameHash` (b64)
  - `pairingKey` (b64, 32 bytes AES-256)
  - `firstPairedAt` (ISO-8601 UTC)
  - `removedAt` (nullable; tombstone)
  - `source` (string; `"manual"` for new pairings, `"migration"` for
    bootstrapped entries from first launch)
- Bump `SCHEMA_CURRENT` to V4. Forward-migration handles V0..V3
  emitting empty `pairedSenders`.
- `StateMerger` per-`pairingId` merge:
  - **Tombstone wins** — if either side has `removedAt` set, the
    later `removedAt` survives (or, if only one side has it, that
    side wins).
  - **Add conflict:** oldest `firstPairedAt` wins (deterministic;
    near-impossible in practice since pairingIds are fresh UUIDs).

### Local store integration

- **`PairedSenderStore` becomes a projection of
  `UserStateRepository.state`.** Observer pattern (mirrors
  read-mark / archive / delete model).
- **Adds:** `PairingRepository.confirm(...)` writes BOTH to local
  encrypted prefs AND calls `userStateRepository.mutateLocal { it.add(...) }`.
  Same `markDirtyAndPush` pattern as read marks.
- **Removes:** `PairingRepository.revoke(...)` writes tombstone to
  synced state AND wipes local key bytes (zero out the byte array
  before removing from prefs).

### First-run migration (one-time bootstrap)

- On first unlocked launch after `SCHEMA_CURRENT` bumps to V4:
  1. Read all `PairedSender` entries from local
     `EncryptedSharedPreferences`.
  2. For each entry not already in synced state, push via
     `mutateLocal`, marked `source: "migration"`.
  3. Guard with a `pairing_sync_migration_done` flag in local prefs
     so this only happens once per device.
- **Non-blocking review banner:** post-migration, inbox shows a
  one-time banner: *"Your paired senders are now synced across your
  devices. Review them in the Senders tab."* Dismissable.
- The banner is local-only (not synced). Each device shows it once
  on its own migration. No mandatory review gate.

### Decryption tolerates multiple active keys per sender

- `InboxRepository.refresh()` already iterates pairings by
  `senderId`. Extend: if multiple non-tombstoned pairings exist for
  the same `senderId` (legacy transition state), try each pairing
  key in turn until one decrypts cleanly. Tombstoned entries
  skipped entirely.

### Re-pair semantics

- On a new pairing for an existing `senderId`, tombstone the old
  pairing in the same `mutateLocal` write atomically. After commit,
  `bySenderId()` returns the newest active pairing.

### Lost-device guidance in integration guide

- Add a "Lost device" section with the three-step user procedure:
  1. Revoke the device from Settings → Devices.
  2. For each sender that handled sensitive data, re-pair on a
     trusted device (this rotates that sender's key).
  3. Past cached data on the lost device is unrecoverable.
- Note that the guided rotation flow is future roadmap.

### Tests

- Schema migration V0..V3 → V4 preserves existing fields, emits
  empty `pairedSenders`.
- Two devices migrating local pairings concurrently → CAS merge
  result is the union (with deduplication by `pairingId`).
- Re-pair tombstones old, new pairing is active, `bySenderId`
  returns newest.
- Multi-key decryption: messages encrypted with either of two
  active pairing keys decrypt; with a tombstoned key, fails.
- Tombstone resurrection prevented: offline device with stale local
  state including an active pairing that's been tombstoned post-sync
  does not resurrect the pairing on its next state push.
- Migration: bootstrapped pairings land in synced state with
  `source: "migration"`; subsequent launches don't re-push.
- Migration banner: shown once, dismissable, not synced.
- Lifecycle: revoked device's `PUT /v1/state` rejected (Phase 0
  hardening).

### Out of scope

- SSE delivery of state changes (Phase 2).
- Templates / live cards / settings sheet (Phase 3).
- Guided lost-device rotation flow (future roadmap).

---

## Phase 2 — SSE event stream + 15s polling removal

### Server

- **New endpoint `GET /v1/events`** (Server-Sent Events).
- **Auth:** `current_auth_context` from Phase 0. Handshake rejects
  revoked devices.
- **Event types** (initial set):
  - `inbox.changed { since: ISO-8601 }`
  - `state.changed { version: int }`
  - `dismiss { message_id, source_device_id }`
  - `card.upsert { card_id, kind: "live" }` (Phase 3b)
- **In-process event bus** for fanout:
  `dict[user_id, set[asyncio.Queue]]`. Bounded queues (e.g., 64
  events per subscriber) to prevent runaway memory if a client
  stops reading.
- **Heartbeats:** server sends `: keep-alive` comment lines every
  ~25 seconds so OkHttp / proxies don't kill the connection on
  idle.
- **Server-initiated disconnect on device revocation.** When
  `POST /v1/auth/devices/{id}/revoke` runs, server iterates active
  SSE subscribers for that device and closes their streams before
  returning 200 OK.
- **`Last-Event-ID` resume:** server emits sequence IDs; on
  reconnect, client passes the last seen ID, server replays from
  there (bounded — only events still in the queue).

### Android

- **New `EventStreamManager`** in `:core:network` (uses
  `okhttp-sse`).
- **Lifecycle-driven:** opens stream on
  `Lifecycle.Event.ON_RESUME` (or equivalent), closes on
  `ON_PAUSE`.
- **Event handlers:**
  - `inbox.changed` → `InboxRepository.refresh()`.
  - `state.changed` → `UserStateRepository.pull()`.
  - `dismiss` → mark dismissed locally + remove from active list.
  - `card.upsert` → live-card update path (Phase 3b).
- **Remove polling loop** from `InboxScreen.kt`. Replace with:
  - One-shot `refresh()` on `ON_RESUME` for catchup.
  - SSE event handlers drive subsequent updates.
- **Manual refresh button** stays. Pull-to-refresh on the inbox
  list is a nice-to-have, not required.
- **401 / handshake rejection** triggers re-login flow (same as
  Phase 0 REST 401 handling).

### Tests

- Server: SSE subscriber receives `inbox.changed` after a card is
  sent.
- Server: revoked device's SSE handshake rejected.
- Server: server-initiated disconnect when a device is revoked
  mid-stream (test fires a revoke while a stream is open;
  verifies the stream closes within ~1s).
- Server: heartbeat lines sent every ~25s.
- Server: bounded queue: a non-reading subscriber doesn't grow
  unbounded.
- Android: SSE event triggers inbox refresh.
- Android: app foreground/background opens/closes SSE.
- Android: app foreground always pulls inbox once on resume
  (regardless of SSE event timing) for catchup.
- Android: SSE reconnect uses `Last-Event-ID`.

### Out of scope

- WebSocket / two-way live channel (future roadmap).
- `platform.live.subscribe(...)` API for script plugins (future).
- Field-level live updates on templates (future).
- Redis pub/sub for multi-worker scaling (future).

---

## Phase 3a — Template renderer

### Manifest schema

- New optional field `renderer: "script" | "template"` (default
  `"script"` for backwards compat).
- Template manifests include a `template` object:

  ```json
  {
    "renderer": "template",
    "template": {
      "layout": "standard_card",
      "fields": {
        "title":    { "path": "$.title" },
        "subtitle": { "path": "$.subtitle" },
        "body":     { "path": "$.body" }
      },
      "actions": [
        { "id": "ack", "label": "Acknowledge", "endpoint": "https://example.com/api/ack" }
      ]
    }
  }
  ```

- Field syntax: JSONPath-style (`$.foo`, `$.foo.bar`). Escaped text
  output only (no HTML interpolation, no markdown).
- Field length caps enforced (same caps as `hostPreview`
  equivalents).
- Action `endpoint` must match one of the plugin's
  `declaredEndpoints` globs.

### Layouts (V1)

- **`standard_card`** only. Title, subtitle, body, action buttons.
- Adding `compact_row`, `score_card`, etc. is incremental and
  outside this milestone — agreed start lean.

### Host renderer

- Native Compose component `TemplateCard`. Reads payload, resolves
  field paths, renders the layout, wires actions.
- Action tap → `platform.network.fetch` equivalent under the host
  (POST to the declared endpoint).

### Publish-time validation

- Manifest validator (Python SDK + server) rejects:
  - Unknown `layout`.
  - Malformed JSONPath.
  - Action endpoint not matching `declaredEndpoints`.
  - Missing required fields per layout (e.g., `standard_card`
    requires `title`).
  - Field length caps exceeded.

### Tests

- Manifest validator: golden manifests pass; malformed ones
  rejected with clear error.
- Renderer: payload + manifest → expected rendered output (golden
  Compose tree tests).
- Action tap routes to the declared endpoint.
- Backwards compat: `renderer` absent → treated as `"script"`.

### Out of scope

- Live field property on templates (whole-card live updates in
  Phase 3b cover the first use case; field-level deferred).
- Additional layouts.
- Script-fast (Javy/QuickJS) — roadmap.

---

## Phase 3b — Live cards

### Server

- **New table `live_cards`:**
  ```sql
  CREATE TABLE live_cards (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    plugin_id UUID NOT NULL,
    card_key TEXT NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    sequence_number BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sender_id, user_id, card_key)
  );
  ```
- **New endpoint `POST /v1/cards/upsert`:**
  - Sender-authenticated (same Ed25519 envelope signature pattern
    as `POST /v1/messages/send`).
  - Body includes `card_key`, encrypted payload, nonce,
    sequence_number, AAD-bound metadata.
  - Server upserts; sets `expires_at = now + 48h`.
  - **Rate limit:** 1 upsert per second per
    `(sender_id, user_id, card_key)`. Returns 429 on exceed.
  - Server fires `card.upsert` event on the SSE channel.
- **New endpoint `DELETE /v1/cards/{card_key}`** for explicit
  close. Sender-authenticated.
- **TTL job:** background task expires `live_cards` past
  `expires_at`. Daily.
- **Inbox endpoint includes live cards:** `/v1/messages/inbox`
  merges event messages + live cards in the response (with a
  type discriminator so clients can route).

### AAD for live-card upserts binds

- `card_key`, `card_type: "live"`, `sender_id`, `user_id`,
  `plugin_id`, `expires_at`, `nonce`, `sequence_number`.
- This prevents replay against a different card and prevents
  out-of-order delayed updates from overwriting newer state.

### Manifest

- New field `card_type: "event" | "live"` (default `"event"`).
- Live manifests must include `card_key_path` (JSONPath into
  the payload that yields the stable card_key). Validated at
  publish.

### Android

- **Inbox repository** stores live cards alongside event messages,
  keyed by `card_key`.
- **SSE `card.upsert` handler** pulls the latest payload (or
  applies inline if the event carries it) and patches the local
  store.
- **Client-side sequence_number gate:** updates with
  `sequence_number <= current_sequence_number` are dropped.
- **Drawer entry "Live"** filters to live cards only. Sorted by
  `updated_at` descending.
- **"All" view** pins live cards above event cards, both ordered
  by their respective time fields. (Visual separation via a thin
  divider.)
- **Templates render live-card payloads** the same as event
  payloads. Re-render on payload mutation; no field-level
  subscription.

### Tests

- Upsert creates / updates row.
- Rate limit returns 429 on exceeded frequency.
- Replay attack: AAD mismatch on tampered `card_key` rejected.
- Out-of-order: client drops updates with stale sequence_number.
- TTL expiry: cards past `expires_at` GC'd; expired cards not
  returned by inbox.
- Drawer "Live" entry filters correctly; "All" view pins live
  cards above event cards.
- Manifest validator: `card_type: "live"` requires `card_key_path`.

### Out of scope

- Field-level live subscriptions (future roadmap).
- High-frequency tickers (rate limit makes this explicitly
  unsupported; senders that need it use future WebSocket).

---

## Phase 3c — Host-owned settings sheet

### UI

- **Entry point:** detail-view top bar overflow icon (the thin
  host-drawn top bar above the WebView/Compose-rendered card).
- **No long-press entry** in this milestone (long-press stays
  multi-select).
- **Sheet content:**
  1. **Plugin info** (sender name, identifier, version, fingerprint,
     paired date).
  2. **Mute settings:**
     - "Mute everywhere" (synced; primary toggle).
     - Under an "Advanced" or "More" chevron: "Mute on this device"
       (local; override).
  3. **Revoke pairing** (with confirmation; same as Senders tab).

### Mute logic

- **Synced state:** `EncryptedUserState.mutedSenders: Set<String>`.
- **Local state:** `mute_overrides: Map<SenderId, Boolean>` in
  EncryptedSharedPreferences (NOT synced).
- **Effective mute** per device, per sender:
  ```
  if (local_override is not null) effective = local_override
  else effective = synced.contains(senderId)
  ```
- Muted senders are filtered out of the active inbox view (still
  received and decrypted; just hidden from inbox list).
- Muted senders are visible in the Senders tab with a "muted"
  badge so the user can find and unmute them.

### Schema

- `EncryptedUserState.mutedSenders` added to the synced state blob.
  No new schema version bump if Phase 1's V4 already lands first
  (V4 is the synced-pairing version); this is added alongside in
  Phase 1 OR in a V5 bump if Phase 3c lands after Phase 1 in
  separate commits. Decision deferred to implementation.
- `StateMerger`: union semantics for `mutedSenders` (a mute on
  any device merges into all devices). Unmute requires explicit
  removal (no tombstones for this simple field — last-write
  semantics; if you unmute on one device, the absence merges away
  on next pull).

### Tests

- Mute everywhere → sender hidden on all devices.
- Local override "muted" while not synced → muted on this device
  only.
- Local override "unmuted" while synced muted → not muted on this
  device.
- Revoke pairing from sheet → same as Senders tab revoke.
- Muted sender visible in Senders tab with "muted" badge.
- Plugin info displays correct fingerprint / paired date.

### Out of scope

- Long-press settings entry (deferred).
- Per-plugin user preferences beyond mute (future roadmap).

---

## Phase 4 — Documentation + roadmap publish

### Integration guide updates

- New `renderer` field documentation (`script` | `template`).
- Template schema and validation reference.
- New `card_type` field (`event` | `live`) + `card_key_path`.
- Live-card upsert API (`POST /v1/cards/upsert`) reference.
- AAD field list for live upserts (including sequence_number).
- Rate limits + TTL documented.
- Lost-device user procedure (revoke + re-pair).
- Mute semantics (synced vs local override).

### Roadmap publish

Add to integration guide (and/or `docs/ROADMAP.md`):

1. Multi-process plugin host via AIDL/Binder (foundational)
2. Native Kotlin plugin runtime on top
3. Expanded capability bridge (camera, storage, file, location,
   message.respond, showNotification)
4. WebSocket / two-way live channel
5. `platform.live.subscribe(...)` API for script plugins
6. Field-level live subscriptions for templates
7. Script-fast (Javy/QuickJS) runtime
8. Guided lost-device rotation flow
9. Master-key rotation
10. Per-device envelope encryption
11. Per-plugin user preferences (notification cadence, custom
    labels, schedule)
12. Redis pub/sub for SSE event-bus scaling
13. Durable nonce-replay protection

Order ratified by both triad reviewers; platform dependencies lead.

---

## Process per phase

For each phase:

1. I write the code per this plan.
2. Commit phase base (one commit per phase, conventionally named).
3. Fire a build-review prompt to the triad with the diff + phase
   scope from this document.
4. Apply any fix-ups from triad review.
5. Commit fix-ups.
6. Mark phase complete in this document.
7. Move to next phase.

If a phase reveals a problem with the plan, pause and re-consult
the triad before proceeding.

---

## What this milestone is NOT

Things in the future roadmap, NOT to be built in this milestone:

- Native Kotlin runtime / multi-process plugin host.
- Expanded capability bridge (camera, storage, etc.).
- WebSocket / two-way live channel.
- Script-fast (Javy/QuickJS).
- Field-level live subscriptions for templates.
- Guided lost-device rotation flow.
- Master-key rotation.
- Per-device envelope encryption.
- Per-plugin preferences beyond mute.
- Redis pub/sub.
- Durable nonce-replay protection.
- Long-press settings entry (deferred sub-feature).
- Additional template layouts beyond `standard_card`.
