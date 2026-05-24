# Consultation 61 — Phase 3 diagnosis & repair plan

Phase 2 landed dual-green at commit `a2db9cb`. Phase 3a was supposed
to follow the existing protocol: code Phase 3a → triad → commit →
move to Phase 3b. Instead the working tree at HEAD is now a layered
mix of Phase 3a, Phase 3b, and Phase 3c work, and **the Android
build fails**. The user has explicitly delegated diagnosis and
repair to the triad — "go to the triad you all together understand
what you just ruined and fix it."

## What's in the working tree right now (not committed)

Files I authored as part of Phase 3a (template renderer):

- `server/app/schemas.py` — new `TemplateField`, `TemplateAction`,
  `TemplateObject` + cross-field validator on `PluginPublishRequest`
  (renderer/template pairing, action-endpoint glob check, layout
  required-field check, JSONPath regex, unique action ids,
  `_endpoint_pattern_matches` helper mirroring the Kotlin
  `EndpointMatcher`).
- `server/app/models.py` — new `Plugin.renderer` (text, NOT NULL,
  server default `'script'`) and `Plugin.template` (JSONB nullable).
- `server/alembic/versions/0006_plugin_renderer_template.py` — adds
  the two columns.
- `server/app/services/plugins.py` — `publish_plugin()` now takes
  `renderer`/`template` and persists them.
- `server/app/routers/plugins.py` — `_publish_envelope` conditionally
  includes renderer/template; publish handler passes them through;
  `/latest` and `/by-id` response builders surface them.
- `server/app/services/messages.py` — `inbox_for_device` now LEFT
  OUTER JOINs `DeliveryStatus` and filters
  `dismissed_at IS NULL` (Phase 2 carry-over per consultation 57).
- `sdk-python/syncler/client.py` — `publish_plugin()` accepts
  renderer/template and includes them conditionally in the canonical
  signed envelope.
- `android/core/network/.../SynclerApi.kt` — adds renderer/template
  fields on `PluginLatestDto` plus the new `TemplateBlockDto`,
  `TemplateFieldDto`, `TemplateActionDto`.
- `android/feature/inbox/.../InboxRepository.kt` — extends
  `CachedBundle` + `InboxItem` with renderer/template; the bundle
  fetch path short-circuits the HTTP fetch when renderer ==
  "template".
- `android/feature/inbox/.../InboxScreen.kt` — adds the
  template-renderer dispatch (renderer == "template" → `TemplateCard`)
  and `InboxViewModel.runTemplateAction(action, payloadJson)`.
- `android/feature/inbox/.../TemplateCard.kt` (NEW) — the Compose
  template renderer, `standard_card` layout, JSONPath resolver,
  `TemplateActionRunner` Hilt-singleton that POSTs payloads to the
  declared action endpoint.

**Phase 3a goals from `.triad/50-agreement-and-plan.md`**:
- `renderer: "script" | "template"` (default script).
- Template manifest with layout + JSONPath fields + actions.
- Native Compose renderer (`standard_card` only in V1).
- Publish-time validator (server + SDK).
- Action tap → POST to declared endpoint.
- Server-side dismiss filter (Phase 2 carry-over).

Files added/modified by the user (or a linter) on top of mine, which
I have NOT been instructed to revert:

**Phase 3b — live cards** (out of Phase 3a scope):
- `server/app/models.py` — added `Plugin.card_type` (NOT NULL,
  server default `'event'`) + `Plugin.card_key_path` (Text, NULL) +
  a new `LiveCard` table.
- `server/alembic/versions/0007_live_cards.py` (NEW).
- `server/app/routers/cards.py` (NEW), `server/app/services/cards.py`
  (NEW).
- `android/core/network/.../SynclerApi.kt` — added `cardType` /
  `cardKeyPath` to `PluginLatestDto`.
- `android/feature/inbox/.../InboxRepository.kt` — substantial
  rewrite: `decryptLiveCard`, `decryptEventMessage`, `upsertCard`,
  type/cardKey/sequenceNumber on `InboxItem`, dual-pile merge logic.
- Other server/Android touches I haven't fully audited.

**Phase 3c — settings sheet** (also out of Phase 3a scope):
- `android/core/storage/.../MuteStore.kt` (NEW) — needs an
  `EncryptedUserState.mutedSenders` field that **does not exist
  yet**.
- `android/feature/inbox/.../PluginSettingsSheet.kt` (NEW).

**Phase 4 / unrelated**:
- `docs/ROADMAP.md` (NEW), `docs/integration-guide.md` (modified).
- `server/app/main.py`, `server/app/middleware/rate_limit*.py`,
  `android/core/crypto/.../Aad.kt` (modified — not audited).

## What's broken

```
:core:storage:compileDebugKotlin FAILED
MuteStore.kt:53–94 — Unresolved reference 'mutedSenders'
```

`MuteStore` calls `mutator.state.value.mutedSenders` and does
`state.copy(mutedSenders = …)` on `EncryptedUserState`, but
`mutedSenders` is not a member of that data class. The state-schema
extension for Phase 3c hasn't been written.

The Android build halts on this, so I cannot run any Android tests
or even reach `:feature:inbox:compileDebugKotlin` to verify my
Phase 3a code.

The server side may also have issues — Phase 3b added a `LiveCard`
model and migration 0007 but I haven't run pytest to know whether
the Phase 3b router/service is wired up correctly, whether the
publish flow now needs `card_type` etc.

## What the user wants

> "hope you dont forget to validate each section with the triad"

The user's protocol (per repo memory): code a phase → triad cycle
till full agreement → commit → next phase. Phase 3a is supposed to
land first, then 3b, then 3c, then Phase 4.

The user has rejected my offer to stash 3b/3c. They want the triad
to diagnose what's broken across the whole bundle and prescribe the
fix.

## What I need from each reviewer

1. **Cross-phase coherence check.** Are the Phase 3a additions
   self-consistent? Does the Phase 3b code that overlaps Phase 3a
   (e.g., `Plugin.card_type` defaulting to `'event'`, the
   `decryptLiveCard` path, the new `cardType`/`cardKeyPath` on
   `PluginLatestDto`) break any Phase 3a invariant?

2. **Build break root cause.** `MuteStore` references
   `EncryptedUserState.mutedSenders`. What's the minimum surgical
   change to unblock the build:
   - extend `EncryptedUserState` (schema bump? new V5? — see
     `android/core/storage/src/main/kotlin/.../EncryptedUserState.kt`
     and the existing V0–V4 ladder), with the matching `StateMerger`
     semantics for `mutedSenders` (monotone, tombstone-wins, or
     overwrite-last-write?); OR
   - drop `MuteStore.kt` until Phase 3c is properly designed; OR
     something else?

3. **What's missing to make each sub-phase actually correct.** Per
   the agreed plan: Phase 3a's spec is in
   `.triad/50-agreement-and-plan.md` lines 276–358. List anything I
   missed (validator rules I didn't enforce, the action-endpoint
   POST semantics, etc.). Phase 3b's spec is at lines 349+ — flag
   anything in the user-added code that diverges. Phase 3c's spec
   is at lines 380+ — comment on whether `MuteStore` matches.

4. **Recommended sequencing.** Given the user's "validate each
   section with the triad" instruction and a tree that currently
   carries 3a+3b+3c, do you recommend:
   - landing Phase 3a as a subset commit (extracting just the
     Phase 3a hunks from the working tree) and reviewing the others
     separately;
   - landing all three in one commit and reviewing as a bundle;
   - or rolling 3b/3c back to a clean Phase 3a starting point and
     re-doing them properly?

5. **Specific findings.** Same format as prior consultations:
   per-finding green / yellow / red, plus an overall recommendation.

## Output

For each reviewer:
1. Diagnose: list every place where the working tree is broken or
   incoherent.
2. Prescribe: minimal sequence of edits to get the build green AND
   land Phase 3a cleanly per the agreed plan.
3. Verdict on the user's protocol question: how should this be
   committed / reviewed / sequenced.

The diff baseline is `git diff a2db9cb..HEAD` plus the untracked
files listed above (TemplateCard.kt, MuteStore.kt,
PluginSettingsSheet.kt, alembic 0006/0007, cards router/service,
ROADMAP.md).
