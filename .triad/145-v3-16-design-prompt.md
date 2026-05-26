=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 145 — V3 #16 design (card.patch privacy)

V3 #14 closed at `ecf0c63` (impl 1-12 + post-work triad 144
FIX list). V3 #15 (SDK `subscribe()` sugar) shipped at
`5f82f4b`. V3 #16 is the next item: field-level live updates
on template-rendered cards.

Both 141 reviewers + the 144 verdict echoed that #16 needs its
own design triad BEFORE implementation because the privacy
contract is non-trivial. v0.1 dev posture preserved.

## What V3 #16 is for

Today the live-card path is whole-card upsert:
- Sender pushes a `cards.upsert` envelope containing a fresh
  `recipient_envelopes[]` set sealing the FULL payload for
  every active device. Device opens its envelope, decrypts,
  re-renders the template card.
- Cost: every sender update re-encrypts the whole payload
  per device.

#16 wants finer-grained: "update just the `subtitle` field on
this card without re-running JSONPath on the whole payload."
Use cases: live sports scores updating just one number;
typing-indicator updating just a status line; presence dot
changing color.

Wire pattern from `docs/live-channel.md` "#16 hook":

```json
{
  "type": "card.patch",
  "plugin_row_id": "...",
  "card_id": "...",
  "field": "$.subtitle",
  "value": "Updated"
}
```

But that wire shape has `value` in plaintext — both reviewers
flagged that as a CRITICAL design bug. The server would see
the field update, and the V2 E2EE contract evaporates.

## Proposed design — V3 #16a

### Encryption contract

A card.patch is a V2-shape envelope just like cards.upsert,
not a special "field-level" payload. The plaintext "value" is
inside the encrypted payload, NOT in the outer wire frame.

```json
{
  "type": "card.patch",
  "plugin_row_id": "...",
  "card_id": "...",
  "card_seq": 42,
  "recipient_envelopes": [
    { "device_id": "d1", "envelope": "<base64 HPKE-sealed>" },
    { "device_id": "d2", "envelope": "<base64 HPKE-sealed>" }
  ]
}
```

The HPKE-sealed payload is `{field_path, new_value}` — the
plaintext field name + value. Devices open their envelope,
extract the patch, apply to local card state.

This means: the server STILL sees `plugin_row_id` + `card_id`
+ `card_seq` (already public metadata in V2 today). The
server NEVER sees the field path or the new value.

### Renderer integration

The Android `TemplateCard` Composable currently re-runs
`resolveJsonPath` on every recomposition driven by a fresh
payload JSON. For #16, the patch path:

1. Device receives `card.patch` envelope on its live channel.
2. Decrypts → `{field_path, new_value}`.
3. Local card state holds the *current* payload JSON (from
   the original upsert + accumulated patches).
4. Apply the patch: replace the value at `$.subtitle` (etc.)
   in a deep-cloned payload JSON.
5. Re-render the card with the patched payload.

No new "patch mode" in the renderer — the renderer just sees
a new payload string. The patching is host-side state
management.

### Sequence numbers

card.patch needs ordering to apply correctly. If patches
arrive out of order (live channel is best-effort + ephemeral
per V3 #14), the device might apply a stale patch over a
newer one.

Options:
- (a) Require `card_seq` to be strictly monotonic; reject
  patches with `card_seq <= last_applied_seq`. Skip + fall
  back to inbox pull on gap.
- (b) Use the ordered lane (V3 #17 work) for card.patch
  delivery. Plays nicely with the durable Redis Streams.
- (c) Each patch carries `base_seq` (the upsert it patches
  against) + a small int incrementing per-patch. Reject if
  base_seq doesn't match the device's current card_seq.

(c) is what live-card upserts already do (sequence-monotone
CAS). Extending it to patches is natural.

### Wire shape

Server-side: cards router gains a `card_patch` action via
the same `/v1/cards/upsert` endpoint, dispatched by the
envelope's `wire_kind` field. Or a new
`/v1/cards/patch` endpoint — probably cleaner.

The patch wire frame ALSO rides the live channel for low
latency. But it also writes to a small server-side `card_patches`
table so a device that wasn't connected at patch time can
catch up via inbox pull (the live channel is ephemeral; the
inbox is authoritative).

### Privacy invariants

The patch's payload must:
1. Use the same V2 envelope shape (per-device HPKE + outer
   Ed25519 sender signature) as cards.upsert. Same key
   schedule, same recipient set rules.
2. NEVER leak the field path or value in the outer frame.
3. Respect the existing `min_plugin_version` + revocation
   gates.

### Renderer security implications

Field-level patches operate on a payload object the renderer
already trusts. A patch can't escalate beyond what the
manifest's `template.fields` already permits — the renderer
ignores unknown field names.

But: a malicious patch could OMIT a field present in the
original payload. The renderer falls back to "no value" for
that field. Acceptable? Or does the patch need to be
explicitly typed (`update` / `unset` / `replace`)?

## Concerns I want a second opinion on

1. **Wire-shape choice.** Reuse `cards.upsert` shape with a
   `wire_kind: "patch"` discriminator vs. dedicated
   `/v1/cards/patch` endpoint. Both reviewers' instinct?

2. **Sequence model.** Option (a) strict monotonic seq, (b)
   defer to V3 #17 ordered lane, (c) base_seq + patch_seq.
   I lean (c) — minimal new state on the device + maximally
   compatible with the ephemeral live channel.

3. **Cross-channel routing.** Patches ride the live channel
   AND get persisted server-side for inbox catch-up. Is the
   dual-write the right answer, or should patches be
   ephemeral-only (accept that disconnected devices miss
   field-level updates between full upserts)?

4. **Renderer state model.** Today's TemplateCard is stateless
   — it computes from `payloadJson` each render. #16 needs
   a stateful layer (current_payload accumulating patches).
   Where does that state live? `InboxViewModel`? A new
   `LiveCardStore`? Per-card Compose `remember { }`?

5. **Patch ops.** Only `replace` for V0.1, or also `unset`
   / `array_push`? The spec doesn't pin this — replace-only
   is the simplest contract.

6. **Sender SDK ergonomics.** Sender code currently calls
   `client.upsert_card(card_id, payload, ...)`. Adding
   `client.patch_card(card_id, field_path, new_value, ...)`
   is straightforward but requires the sender to know the
   field path syntax (`$.subtitle`). Acceptable, or pre-
   require the sender to pass a "patch ops" array?

7. **Patch validation.** Server validates the patch
   envelope shape (recipient set classifier, sender
   signature, sequence) but CANNOT validate the field path
   matches the manifest's template fields (patch payload is
   encrypted). The device-side renderer is the last gate.
   Is that acceptable, or should the patch's field name
   travel UNENCRYPTED so the server can reject malformed
   patches at publish time?

8. **Coexistence with whole-card upserts.** A sender can
   alternate: upsert → patch → patch → upsert → patch.
   The upsert resets `card_seq` to a new value; the device
   should reject patches whose `base_seq` predates the
   current `card_seq`. Trivial monotone-reject logic, but
   worth pinning.

9. **Renderer flicker on patch.** Compose recomposes when
   `payloadJson` changes; a patch causes a full TemplateCard
   re-render even though only one field changed. For V0.1
   that's probably fine; Compose's diffing is efficient. But
   if a stat_grid is being patched at high frequency, every
   patch re-renders all 8 tiles. Worth a granular
   `payloadFlow: Map<String, State<String?>>` model where
   each field path is its own observable, or premature
   optimization?

10. **Patch storage retention.** Server-side patch table:
    how long to retain? Whole-card live updates use 48h TTL
    today. Patches between two upserts can be collapsed
    (apply in order, fold into a new "virtual upsert"). Is
    a separate patch table worth maintaining, or just rely
    on V0.1 in-process buffer (lost on restart, devices
    catch up via the next sender upsert)?

11. **Triad scope.** This is the V3 #16 design pre-work
    triad. The implementation will mirror V3 #14's 13-step
    structure with its own post-work triad. Is the scope
    above tight enough to ship in 4-6 commits, or am I
    underestimating?

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN). Plus
any privacy/correctness invariant I haven't surfaced.

Focus on:
- E2EE: nothing in the patch wire frame leaks plaintext field
  paths / values.
- Ordering: out-of-order patches don't corrupt card state.
- Coexistence: upserts + patches interact cleanly.
- Renderer state: where the patched-payload accumulator
  lives.

Skip cosmetics; flag substance. Goal: a clear go-list for the
V3 #16 implementation phase, or a v2 design pass before that.
