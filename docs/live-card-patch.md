# Live Card Patch — V3 #16 (field-level updates)

V3 #14 shipped the live channel; whole-card upserts already
work via `cards.upsert`. V3 #16 adds *field-level* updates on
live-rendered template cards so high-frequency state (scores,
typing indicators, presence dots) doesn't pay the per-update
full re-encryption cost.

Spec closed at: triad 145 (codex + gemini), both proceed with
the FIX list integrated below.

## Privacy contract (load-bearing)

**The outer wire frame never carries plaintext field paths or
values.** Every patch is a V2 envelope sealed for each active
device:

```json
{
  "type": "card.patch",
  "plugin_row_id": "...",
  "card_id": "...",
  "base_seq": 42,
  "patch_seq": 7,
  "recipient_envelopes": [
    {"device_id": "d1", "envelope": "<base64 HPKE-sealed>"},
    {"device_id": "d2", "envelope": "<base64 HPKE-sealed>"}
  ]
}
```

The HPKE-sealed payload (per recipient device) decrypts to:

```json
{
  "patches": [
    {"op": "replace", "path": "$.home_score", "value": "42"},
    {"op": "replace", "path": "$.away_score", "value": "17"}
  ]
}
```

What the server sees: `plugin_row_id` + `card_id` + `base_seq`
+ `patch_seq` (already-public V2 metadata).

What the server NEVER sees: which field changed, the new
value, op type, count of patches in the batch.

Recipient-set rules, sender Ed25519 signature, version /
revocation gates: identical to `cards.upsert`.

## Sequence model (codex + gemini agreed)

Each card has:
- `card_seq` — version of the latest full `cards.upsert`.
  Bumped per upsert. Sender-assigned, server-CAS-validated.
- `patch_seq` — monotonic-within-`card_seq`. Each patch
  carries `base_seq = card_seq_at_publish_time` plus its own
  `patch_seq`. Resets to 0 when a new upsert lands.

Device state per `(plugin_row_id, card_id)`:
- `current_card_seq` (Long)
- `last_patch_seq` (Long, 0 after upsert)

Device patch-apply logic:

| Incoming | Action |
|---|---|
| `base_seq < current_card_seq` | Reject (stale generation) |
| `base_seq > current_card_seq` | Buffer + trigger inbox pull (missing upsert) |
| `base_seq == current_card_seq && patch_seq <= last_patch_seq` | Reject (replay / late) |
| `base_seq == current_card_seq && patch_seq > last_patch_seq + 1` | Gap; buffer + trigger inbox pull |
| `base_seq == current_card_seq && patch_seq == last_patch_seq + 1` | Apply atomically; bump `last_patch_seq` |

Whole-card upsert resets the chain:
- `current_card_seq` ← new upsert's `card_seq`
- `last_patch_seq` ← 0
- Any buffered patches with `base_seq <` new value: discarded.

## Routing — dual-write

Patches ride TWO paths:

1. **Live channel (V3 #14 ephemeral lane)** — low-latency
   delivery to currently-connected devices via the existing
   `plugin_topic(user_id, plugin_row_id)` fanout.
2. **Persistent patch table** — for inbox catch-up. Devices
   offline during the live broadcast pick up the patches on
   the next `/v1/messages/inbox` pull.

### Server-side state

New table:

```sql
CREATE TABLE card_patches (
    plugin_row_id  UUID NOT NULL,
    card_id        UUID NOT NULL,
    base_seq       BIGINT NOT NULL,
    patch_seq      BIGINT NOT NULL,
    envelope_json  TEXT NOT NULL,  -- the V2 envelope per spec
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_row_id, card_id, base_seq, patch_seq)
);
CREATE INDEX ix_card_patches_card_created
    ON card_patches (plugin_row_id, card_id, created_at DESC);
```

Retention:
- 48-hour TTL matching the existing live-card retention.
- **Purge immediately when a new `cards.upsert` arrives for
  the same `card_id`** (gemini FIX). The chain past the new
  upsert is by definition obsolete; never keep stale patches.
- **Purge when the parent `LiveCard` row is deleted** (V2
  delete) or pruned (expires_at sweep) — schema has no FK
  cascade, so the service layer + retention job sweep
  explicitly. Triad 146 codex FIX #4.
- Periodic GC walks rows older than 48h AND any rows whose
  `card_id` no longer maps to a `LiveCard` row (orphan
  sweep). Wired into `app/jobs/retention.py:prune_expired`.

### Catch-up surface

The existing `/v1/messages/inbox` (and live-cards equivalent)
response augments live-card entries with a `patches: [...]`
array containing every persisted patch with `base_seq ==
card_seq` and `patch_seq > device_last_patch_seq`. Devices
apply them in order before rendering.

If the patch chain has gaps (a 48h TTL ran out on `patch_seq
= 5` but `6/7/8` are still there), the inbox response omits
the broken chain — the device falls back to the whole-card
payload from the upsert and waits for the next upsert.

## Device-side state (LiveCardStore / InboxRepository)

The accumulator MUST live host-side, not in Compose `remember
{ }` (both reviewers FIX). Gemini's concrete answer:
`InboxRepository` (DB-backed). Codex's slightly more
abstract: a `LiveCardStore` keyed by `(plugin_row_id,
card_id)`. We go with gemini's — the existing
`InboxRepository` already owns live-card state in the
SQLCipher inbox DB.

Apply path:
1. Live channel WS receives a `card.patch` envelope.
2. `InboxRepository.applyLivePatch(...)` decrypts, runs the
   sequence-check, applies each `replace` op to the
   payload's JSON in a deep clone, writes the new payload
   to the inbox-card row, bumps `last_patch_seq`.
3. The existing `items: StateFlow<List<InboxItem>>` re-emits
   on the row update — the `TemplateCard` Composable
   recomposes naturally.

`TemplateCard` stays stateless — receives `payloadJson`, runs
the existing JSONPath resolver per field, renders. No new
patch-mode in the renderer.

## Ops (V0.1)

Only `replace`:

```json
{"op": "replace", "path": "$.home_score", "value": "42"}
```

- `path` must match the manifest's `template.fields[*].path`
  set. Device renderer ignores unknown paths (existing
  behavior); a patch targeting an unknown path is rejected
  locally without partial mutation (codex invariant).
- `value` is the raw string the JSONPath resolver would
  return. No support for `array_push` / `unset` /
  numeric-typed values in V0.1.

Multi-patch in a single envelope is supported (gemini FIX):

```json
{
  "patches": [
    {"op": "replace", "path": "$.home_score", "value": "42"},
    {"op": "replace", "path": "$.away_score", "value": "17"}
  ]
}
```

Applied atomically — if any op fails (unknown path,
malformed), the whole batch is rejected and the device
discards the patch. Local state never sees partial
mutation (codex invariant).

## Server endpoint

Dedicated route (both reviewers agree on the dedicated
endpoint vs. discriminator-in-upsert):

```
POST /v1/cards/patch
```

Request:

```json
{
  "sender_id": "...",
  "plugin_row_id": "...",
  "card_id": "...",
  "base_seq": 42,
  "patch_seq": 7,
  "recipient_envelopes": [
    {"device_id": "d1", "envelope": "..."},
    ...
  ],
  "sender_signature": "<Ed25519 over canonical envelope>"
}
```

Server validates:
1. Sender signature (Ed25519 over the canonical envelope).
2. Plugin row active + paired with the user(s) implied by
   `recipient_envelopes[*].device_id`.
3. Recipient-set classifier (same 8-row matrix as
   `cards.upsert` — every active device for the user must
   appear; rejected devices flagged; stale directory rejected).
4. `base_seq` matches the card's current upsert `card_seq`
   (server has authoritative card state from the previous
   `cards.upsert`). Mismatch → 409 stale_base_seq.
5. `patch_seq == last_persisted_patch_seq + 1` (contiguous
   CAS — triad 146 codex FIX #3). Equal/lower → 409
   patch_seq_regression; higher-than-next → 409
   patch_seq_gap. The server is the authority: a sender
   that skips a sequence number stalls until they catch up
   (or publish a new whole-card upsert), and devices never
   see a patch chain with holes.
6. Sequence CAS via the index PK — concurrent patches with
   the same `(card_id, base_seq, patch_seq)` collide on
   insert and the later one fails 409 patch_seq_collision
   (the precheck races; the PK is the ground truth).

On success:
- Insert the row.
- Publish the envelope on `plugin_topic(user_id,
  plugin_row_id)` for the live channel.
- Return 202 + delivery count (live-connected devices).

## SDK ergonomics (gemini FIX)

Sender SDK gains a typed helper that hides JSONPath:

```python
client.patch_card(
    card_id,
    patches=[
        ("home_score", "42"),
        ("away_score", "17"),
    ],
)
```

The SDK maps each `(field_name, value)` to `(JSONPath,
value)` using the plugin's manifest declarations
(`template.fields[name].path` → JSONPath string). Plugin
authors never type `$.home_score` manually.

Lower-level escape hatch:

```python
client.patch_card(
    card_id,
    raw_patches=[{"op": "replace", "path": "$.home_score", "value": "42"}],
)
```

## Implementation order (V3 #16)

1. Spec digest (this file).
2. Server: alembic migration for `card_patches` table.
3. Server: `POST /v1/cards/patch` endpoint with signature +
   recipient-set + sequence validation.
4. Server: inbox catch-up surface — augment live-card inbox
   response with a `patches: [...]` field.
5. Server: 48h GC + purge-on-next-upsert.
6. Python SDK: `client.patch_card(card_id, patches=[...])`
   with the typed-field-name helper + raw-patches escape.
7. Android: `InboxRepository.applyLivePatch(...)` —
   decrypt, sequence check, replace ops, write to inbox DB.
8. Android: inbox-pull catch-up consumes the `patches:[...]`
   field returned by `/v1/messages/inbox`.
9. Android: LiveBridge dispatches `card.patch` envelopes
   arriving on the live channel into `InboxRepository`.
10. Tests: privacy invariants (server sees no field path),
    sequence model edges (stale, gap, replay, upsert-reset),
    catch-up via inbox, multi-patch atomicity, unknown-path
    rejection.
11. Mid-track triad post-work review.

## Non-goals for V3 #16

- `unset` / `array_push` / numeric-typed ops — V0.2.
- Patches on event cards (only live cards). Event-mode
  inbox messages are immutable; a "patch on event" would
  break the existing TTL/dismiss contracts.
- Cross-card patches — a patch is scoped to one
  `(plugin_row_id, card_id)`.
- Out-of-order tolerance via reorder buffer — current model
  rejects gaps + falls back to inbox pull. A reorder
  buffer is V0.2.
- Per-field patch-rate limit — V0.1 inherits the WS
  bucket cap (16 KB/s per socket); fine for V0.1 use cases.

## Privacy invariants (codex must-haves)

- Outer frame never contains `field`, `path`, `value`, op-
  specific plaintext, or a hash/digest of the field path.
- Recipient set equivalence enforced with the same rules
  as `cards.upsert`.
- Patch payload authenticated as sender-origin (Ed25519
  signature over the canonical envelope).
- Atomic apply: decrypt → validate sequence → validate ops
  → deep-clone-and-mutate → publish new payload state.
- Gaps fail closed: never apply a partial chain.
- Whole-card upsert always wins over older patch chains.
- Unknown / malformed op must not partially mutate.
- Catch-up determinism: two devices receiving the same
  upsert + same valid patch chain end with byte-equivalent
  logical payloads.
