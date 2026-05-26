=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 146 — V3 #16 post-work review

Triad 145 was the pre-work design pass for V3 #16
(`docs/live-card-patch.md`). Both reviewers converged on
proceed-with-FIX-list-integrated. All 11 implementation
steps have shipped across 6 commits; step 11 of the spec
IS this post-work review.

v0.1 dev posture preserved.

## Commits in scope

| Commit  | Steps   | Content |
|---------|---------|---------|
| 657b6f8 | 1       | docs/live-card-patch.md spec (triad 145 closeout) |
| 553afb2 | 2,3,5   | alembic 0014 card_patches + POST /v1/cards/patch + purge-on-upsert |
| 4216dc9 | 4       | inbox catch-up — `patches: List<CardPatchEntryDto>` on live items |
| 12d7d1d | 6       | Python SDK `client.patch_card` + crypto helpers |
| 759b2a0 | 7,8,9   | Android applyLivePatch + catch-up consumer + LiveBridge routing |
| fee7190 | 10      | Android applyReplaceOps unit tests |

Server e2e tests for /v1/cards/patch deferred until the
PG dev box returns (same constraint V3 #14 had at triad 144).

## Files in primary review scope

### Server

- `server/app/services/envelopes_v2.py` — new
  `build_card_patch_envelope_bytes()` — canonical Ed25519
  signing input for `envelope_kind="card_patch"`. Wire
  signing fields are: base_seq, card_id, envelope_kind,
  patch_seq, payload_ciphertext, payload_nonce, plugin_id,
  protocol_version, recipient_directory_version,
  recipient_envelopes, sender_id, user_id. No
  expires_at / min_plugin_version — patches inherit the
  parent card's lifetime.
- `server/app/routers/cards.py` — POST /v1/cards/patch
  validates: sender signature → rate limit (card_upsert
  bucket) → pairing → LiveCard.sequence_number == base_seq
  (409 stale_base_seq) → CardPatch.patch_seq > last (409
  patch_seq_regression) → recipient classifier (same 8-row
  matrix as upsert). On success: INSERT then publish on
  `plugin_topic(user_id, plugin_row_id)` (V3 #14
  ephemeral lane). Returns 202.
- `server/app/services/cards.py` — `upsert_live_card_v2`
  now `sql_delete`s every CardPatch row for the
  (plugin_id, card_id) BEFORE committing the upsert
  (gemini 145 FIX — purge-on-next-upsert).
- `server/app/models.py` — CardPatch ORM model.
- `server/alembic/versions/0014_card_patches.py` —
  composite PK (plugin_row_id, card_id, base_seq,
  patch_seq) + opaque envelope_json TEXT + created_at +
  two indices.
- `server/app/schemas.py` — LiveCardPatchRequestV2
  (V2-envelope-shape with base_seq + patch_seq instead of
  sequence_number, no expires_at). +
  LiveCardPatchInboxItem inlined into LiveCardInboxItemV2
  via `patches: list[...] = []`.
- `server/app/routers/messages.py` — inbox handler bulk-
  queries CardPatch where base_seq == card.sequence_number
  with ORDER BY (card_id, patch_seq) and projects them
  onto each live-card item.

### SDK (Python)

- `sdk-python/syncler/crypto.py` —
  `assemble_card_patch_envelope_v2()` mirrors server
  signing bytes; `build_v2_payload_aad`, `build_v2_hpke_info`,
  `seal_v2_envelopes` extended with optional
  card_id/base_seq/patch_seq parameters and a `card_patch`
  envelope_kind branch.
- `sdk-python/syncler/client.py` — `Client.patch_card()`
  with typed-field-name `patches=[("home_score","42")]`
  + `field_paths={"home_score": "$.home_score"}` sugar, or
  `raw_patches=[{...}]` escape hatch. Same fetch-seal-sign-
  retry pattern as `upsert_card` (one directory refetch on
  409 stale_recipient_set).

### Android

- `core/crypto/.../Aad.kt` — V2Aad gained
  `cardPatchPayloadAad`, `cardPatchHpkeInfo`, and
  `cardPatchSignedEnvelopeBytes`. The first two bind to
  (card_id, base_seq, patch_seq) instead of expires_at /
  min_plugin_version. The third mirrors the server canonical
  signing bytes for client-side signature verification.
- `core/network/.../SynclerApi.kt` — `CardPatchEntryDto`
  (catch-up shape: base_seq + patch_seq + opaque
  envelope_json) and `CardPatchEnvelopeDto` (the full
  V2-envelope wire shape). `InboxFeedItemDto` gained
  `patches: List<CardPatchEntryDto>?`.
- `core/network/.../LivePatchSink.kt` — new
  `fun interface LivePatchSink { suspend fun
  acceptCardPatch(envelopeJson: String) }` with a NoOp
  default. Decouples LiveBridge (feature/plugin-host)
  from InboxRepository (feature/inbox) without cross-
  feature module deps.
- `feature/inbox/.../InboxRepository.kt` —
  `class InboxRepository : LivePatchSink`. Adds:
  - `acceptCardPatch(envelopeJson)` — parse + sequence-
    check + decrypt + apply, all under pollMutex on
    `_items.value`.
  - `applyCatchUpPatches(card, patches)` — used by
    refresh() to replay every persisted patch onto the
    just-decrypted live-card BEFORE the item lands in
    `_items.value`. Halts the chain on any gap/sig/decrypt
    failure (spec "Catch-up surface" gap handling).
  - `applyPatchToCardOrNull(card, dto)` — pure-function
    sibling used by the catch-up path before the card is
    in `_items.value`.
  - Top-level `internal fun applyReplaceOps(payloadJson,
    patchPlaintext)` — deep-clone-and-mutate; throws on
    any unknown path/op (atomic apply per codex invariant).
  - InboxItem gained `lastPatchSequence: Long?`.
- `feature/plugin-host/.../capabilities/LiveBridge.kt` —
  new constructor param `livePatchSink: LivePatchSink =
  NoOp`. `wireIncoming()` peeks the JSON's `envelope_kind`
  on each incoming Message; `"card_patch"` → sink; any
  other kind → existing `plugin.dispatchHook("onLiveMessage",
  ...)` route (unchanged behavior). On peek-failure,
  fall through to the plugin route so a malformed frame
  isn't lost.
- `feature/plugin-host/.../PluginLoader.kt` — the
  `.android(context, scope, livePatchSink = NoOp)` factory
  threads the sink through to LiveBridge. App composition
  root will wire `inboxRepository::acceptCardPatch` here
  when the WS auth integration lands (not in scope for V3
  #16 — separate todo).

## Privacy invariants the spec demanded — claim list

1. Outer wire frame contains NO field path, NO value, NO
   op type, NO patch-count digest. Outer frame schema is
   pure routing/sequence metadata (LiveCardPatchRequestV2).
2. Server canonical signing input MIRRORS the outer frame
   — see build_card_patch_envelope_bytes; no patch ops,
   no field paths.
3. Per-recipient HPKE info + AES-GCM AAD bind to
   (card_id, base_seq, patch_seq) so a server replaying
   the same ciphertext under a different (card, seq) tuple
   fails the device's decrypt. Verified byte-identical
   across server (envelopes_v2.py canonical) ↔ SDK
   (crypto.py build_v2_payload_aad / build_v2_hpke_info)
   ↔ Android (V2Aad.cardPatchPayloadAad /
   cardPatchHpkeInfo).
4. Atomic apply: ANY unknown JSONPath, malformed op, or
   sequence gap discards the WHOLE batch. Local InboxItem
   state never sees a partial mutation. Enforced in
   `applyReplaceOps` (throws on first bad op) + the
   surrounding runCatching in acceptCardPatch /
   applyPatchToCardOrNull.
5. Recipient-set equivalence on /v1/cards/patch is
   identical to /v1/cards/upsert (same
   classify_recipient_set call, same 8-row matrix).
6. Whole-card upsert wins over older patch chains —
   upsert_live_card_v2 deletes every CardPatch row for
   the (plugin_id, card_id) before committing.

## Sequence model claim list

The Android side implements the spec table verbatim:

| Incoming | Action |
|---|---|
| `base_seq < current_card_seq` | drop (stale) |
| `base_seq > current_card_seq` | drop, log "refresh needed" |
| `base_seq == current && patch_seq <= last_patch_seq` | drop (replay) |
| `base_seq == current && patch_seq > last_patch_seq + 1` | drop (gap; refresh fills it) |
| `base_seq == current && patch_seq == last_patch_seq + 1` | apply + bump |

On whole-card upsert: lastPatchSequence resets to null
(treated as 0) at the InboxItem level when the new
upsert lands.

## Specific concerns I'd like flagged

1. **`applyCatchUpPatches` vs `acceptCardPatch` split.**
   Two near-identical decrypt/verify code paths exist —
   one for live-channel push (under `pollMutex` on
   `_items.value`), one for inbox catch-up (pure
   function, no mutex, runs while the card isn't yet in
   the state flow). Risk of drift if a future security
   fix lands on one and not the other. Acceptable for
   v0.1 or worth refactoring to a shared core?

2. **Live channel push race vs catch-up.** If a refresh
   is in flight applying patches 4/5/6, and a live-
   channel push delivers patch 7 between the refresh's
   decrypt-and-apply and the `_items.value.map { ... }`
   write, does the live push see the right
   lastPatchSequence? The refresh holds pollMutex during
   the merge; the live sink takes pollMutex; so the
   sequence is serialized. But: applyCatchUpPatches runs
   OUTSIDE the pollMutex (it builds the merged card list
   inside pollMutex but applies catch-up beforehand).
   Race window?

3. **48h GC for card_patches.** Spec said "Periodic GC
   walks rows older than 48h". Step 5 implemented the
   purge-on-upsert half (gemini FIX) but the time-based
   GC sweep is NOT yet wired. CardPatch rows for cards
   that never get a new upsert grow unbounded for 48h
   then linger. Should we ship V3 #16 without the
   periodic GC, or block on it?

4. **Patch lifetime vs LiveCard.expires_at.** LiveCard.
   expires_at is enforced server-side via prune_expired_
   cards. When the LiveCard row is pruned, orphaned
   CardPatch rows remain (no FK cascade). Inbox query
   for the deleted card returns nothing; catch-up
   patches for it are unreachable. Worth adding a FK
   ON DELETE CASCADE or letting them age out via the
   48h GC?

5. **applyReplaceOps re-parses the input twice.**
   `JSONObject(payloadJson)` then `JSONObject(root.
   toString())` for the deep clone. Functional but
   wasteful on hot-path patches. v0.1 acceptable?

6. **LiveBridge `peekEnvelopeKindOrNull` JSON parse on
   every frame.** Every incoming live frame gets parsed
   as JSON just to read `envelope_kind`. A plugin pushing
   a binary opaque blob through `onLiveMessage` would
   pay this parse cost too. Acceptable, or worth a
   leading-byte sniff fast path?

7. **`patch_card` `field_paths` lookup is a parallel
   contract to manifest declarations.** Spec said "the
   SDK maps each (field_name, value) to (JSONPath, value)
   using the plugin's manifest declarations". I shipped
   it as caller-passed `field_paths={name: jsonpath}`
   to avoid needing manifest fetch in the SDK. Plugin
   authors will type the JSONPath into field_paths
   directly. Gemini FIX intent honored, or does the SDK
   need to fetch the manifest itself to keep authors
   from ever typing `$.x`?

8. **`raw_patches` escape hatch is wide.** Senders can
   bypass the field_paths check entirely with a
   freeform list of `{op, path, value}` dicts; nothing
   validates that paths exist in the manifest's declared
   field set on the SDK side. Server doesn't see paths
   (privacy). Result: a buggy sender can ship patches
   that ALL devices reject locally (unknown path).
   Add SDK-side validation when manifest is available,
   or accept "trust the sender" for v0.1?

9. **Android catch-up uses Moshi.Builder() inline twice.**
   `acceptCardPatch` and `applyCatchUpPatches` each
   construct a fresh Moshi. Same pattern as upsertCard,
   so not a new sin. Worth a top-level singleton, or
   v0.1 leave alone?

10. **LivePatchSink not yet wired at composition root.**
    PluginLoader.android() takes the sink as an
    optional default-NoOp parameter; no caller passes a
    real impl yet (V3 #14 wiring is also still V0.1
    placeholder). Result: patches arriving on the live
    channel today are silently dropped (NoOp). Inbox
    catch-up on next /v1/messages/inbox pull still
    works. Acceptable as "ships behind the same V3 #14
    wiring gate" or worth flagging as a step-12 follow-on?

11. **No client-side base_seq pre-check on the SDK.**
    `patch_card` doesn't read the card's current
    sequence_number anywhere — caller passes base_seq
    they remember. Wrong base_seq round-trips to a 409
    stale_base_seq. Spec doesn't pin SDK responsibility
    here; just confirming the call shape is fine.

12. **AAD/HPKE binding choice — no expires_at.** I chose
    NOT to include `expires_at` in the card_patch
    payload AAD / HPKE info. Rationale: patches inherit
    the parent card's lifetime; there's no TTL on the
    envelope itself. Trade-off: a captured patch
    envelope could theoretically be replayed at any
    point, BUT the (card_id, base_seq, patch_seq)
    binding + the server's CardPatch PK CAS already
    block replays at every meaningful layer. Worth
    including expires_at anyway, or is the (card,
    base, patch) binding sufficient?

## Test status

```
:app:assembleDebug                              — green
:feature:plugin-host:testDebugUnitTest          — all green
:feature:inbox:testDebugUnitTest                — all green (incl. ApplyReplaceOpsTest)
:core:network/crypto/etc. compileDebugKotlin    — green
sdk-python py_compile syncler/client.py crypto  — green
server pytest                                    — BLOCKED on PG dev-env
```

## What I'm asking for

Per-numbered-concern verdict (OK / NIT / FIX / DESIGN).
Plus anything you spot in the commits' diffs that's worth
fixing before V3 #17 (transport swap to Redis Streams).

Focus on:
- Privacy invariants (server never sees field paths;
  AAD/HPKE info chain across server/SDK/Android stays
  byte-identical).
- Sequence model correctness (no replay window, no gap
  silent-apply, upsert correctly resets).
- Atomic apply (no partial mutation on any failure mode).
- The two-code-path issue (acceptCardPatch vs
  applyPatchToCardOrNull) — drift risk.
- Anything in the wire schema that leaks information
  about field paths or values.

Skip cosmetics; flag substance. Goal: greenlight V3 #17
(or a clear FIX list to apply first).
