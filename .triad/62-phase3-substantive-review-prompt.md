# Consultation 62 — Phase 3 substantive review on the now-green tree

Phase 2 landed dual-green at commit `a2db9cb`. Consultation 61 was
the diagnosis pass on the broken Phase 3 bundle. Both reviewers
identified the same build-fix prescription but split on commit
strategy:

- **Codex**: land Phase 3a as a clean subset commit, then 3b, then 3c.
- **Gemini**: unified Phase 3 landing because the tree is too
  entangled to split cleanly without reverting user additions.

The shared build-fix prescription has been applied:

1. `EncryptedUserState` bumped to `SCHEMA_V5` with
   `mutedSenders: List<String> = emptyList()`; `fromJson` forward-
   migrates V0–V4 to V5; `toJson` emits `muted_senders`.
2. `StateMerger` adds a union merge for `mutedSenders`.
3. `InboxRepository.refresh()` branches on `dto.type` and calls
   `decryptEventMessage` / `decryptLiveCard`; both helpers are now
   implemented.
4. `:feature:pairing` build.gradle gets
   `androidx.compose.material:material-icons-extended` so the
   `NotificationsOff` icon resolves.
5. `TemplateCard` is stateless: `onAction(id, endpoint)`, no
   `hiltViewModel()` inside the composable. The dispatch site in
   `InboxScreen.kt` wires it through to
   `InboxViewModel.runTemplateAction(actionId, endpoint, payloadJson)`.

`./gradlew assembleDebug` is **green** (326 actionable tasks, all
executed). Server modules import cleanly. Pytest needs a running
Postgres so the test run from this shell hit
`ConnectionRefusedError` — please rely on static review for the
server portion and call out any test the user should run locally.

The diff to review is `git diff a2db9cb..HEAD` plus the untracked
files listed below. **Nothing is committed yet** since `a2db9cb`.

## Working-tree contents to review

### Phase 3a — Template renderer (per agreed plan lines 276–358)
- `server/app/schemas.py`: `TemplateField`, `TemplateAction`,
  `TemplateObject` (layout validator, required-field validator,
  unique action ids), `_endpoint_pattern_matches` glob helper,
  `PluginPublishRequest.validate_renderer_template_pairing` cross-
  field validator (action endpoints ∈ declared endpoints,
  renderer/template pairing), `renderer` + `template` on
  `PluginLatestResponse`.
- `server/app/models.py`: `Plugin.renderer` (NOT NULL, default
  `'script'`) + `Plugin.template` (JSONB nullable).
- `server/alembic/versions/0006_plugin_renderer_template.py`.
- `server/app/services/plugins.py`: `publish_plugin` accepts
  `renderer` + `template` (and `card_type` + `card_key_path` for
  Phase 3b — note: these are accepted in the function signature but
  the Phase 3b columns are **not** passed to the `Plugin()`
  constructor in the version I last read; please confirm whether
  that's fixed or still a gap).
- `server/app/routers/plugins.py`: `_publish_envelope` conditionally
  includes renderer/template/card_type/card_key_path so old SDK
  signatures keep verifying; `/latest` and `/by-id` surface renderer
  + template.
- `server/app/services/messages.py`: `inbox_for_device` LEFT JOINs
  `DeliveryStatus` and filters `dismissed_at IS NULL` (Phase 2
  carry-over per consultation 57).
- `sdk-python/syncler/client.py`: `publish_plugin` accepts
  renderer/template with conditional canonical-envelope inclusion.
- `android/core/network/.../SynclerApi.kt`: `PluginLatestDto` gets
  `renderer`, `template`, plus `TemplateBlockDto` /
  `TemplateFieldDto` / `TemplateActionDto`.
- `android/feature/inbox/.../InboxRepository.kt` (extends
  `CachedBundle` + `InboxItem` with renderer/template; bundle fetch
  short-circuits the HTTP fetch when renderer == "template").
- `android/feature/inbox/.../TemplateCard.kt` (NEW) — Compose
  renderer for `standard_card`, internal JSONPath resolver
  (`resolveJsonPath`), `TemplateActionRunner` @Singleton POST helper.
- `android/feature/inbox/.../InboxScreen.kt`: dispatch
  (renderer=="template" → `TemplateCard`),
  `InboxViewModel.runTemplateAction(actionId, endpoint, payloadJson)`.

### Phase 3b — Live cards (per agreed plan lines 349+)
- `server/app/models.py`: `Plugin.card_type` (default `'event'`),
  `Plugin.card_key_path` (text nullable), new `LiveCard` table.
- `server/alembic/versions/0007_live_cards.py`.
- `server/app/routers/cards.py`, `server/app/services/cards.py` (new).
- `server/app/main.py`: registers the cards router.
- `android/core/network/.../SynclerApi.kt`: `cardType` /
  `cardKeyPath` on `PluginLatestDto`.
- `android/feature/inbox/.../InboxRepository.kt`: type-branched
  refresh, `decryptEventMessage` + `decryptLiveCard` helpers, dual-
  pile merge (live cards pinned above events), `upsertCard`.

### Phase 3c — Settings sheet + sender mute (per plan lines 380+)
- `android/core/storage/.../EncryptedUserState.kt`: V5 schema bump
  adding `mutedSenders: List<String>`.
- `android/core/storage/.../StateMerger.kt`: union merge.
- `android/core/storage/.../MuteStore.kt` (NEW) — per-sender mute
  with local override layer + synced layer. Effective set =
  `synced + local_mutes - local_unmutes`.
- `android/feature/inbox/.../PluginSettingsSheet.kt` (NEW).
- `android/feature/pairing/.../PairingScreen.kt` (modified) — "Muted"
  indicator using `Icons.Filled.NotificationsOff`.
- `:feature:pairing/build.gradle.kts` — adds
  `material-icons-extended`.

### Other touches
- `android/core/network/.../EventStreamManager.kt`: added
  `CardUpsert` / `CardDelete` event variants for Phase 3b's SSE
  push path.
- `android/core/crypto/.../Aad.kt`, server middleware
  (`rate_limit.py`, `rate_limit_config.py`), `docs/ROADMAP.md`,
  `docs/integration-guide.md` — incidental supporting changes.

## What I need from each reviewer

### 1. Settle the commit-strategy disagreement
Given the tree is now green, do you still recommend splitting into
three commits (Codex's protocol-aligned path) or landing as one
"Phase 3" bundle (Gemini's pragmatic path)? Concrete tiebreaker
considerations:

- Can Phase 3a stand on its own without the Phase 3b `card_type`
  default of `'event'` on the Plugin row? (My read: yes — Phase 3a
  rendering never inspects `card_type`.)
- Does Phase 3b functionally require Phase 3c (mute toggle in the
  settings sheet)? (My read: no — 3b is the live-card data path; 3c
  is the user-facing toggle.)
- If we split, the Phase 3a commit would carry only the Phase 3a
  files. The Phase 3b commit carries the LiveCard model, cards
  router/service, alembic 0007, the InboxRepository type-branching,
  and the `card_type`/`card_key_path` columns. The Phase 3c commit
  carries `MuteStore`, `PluginSettingsSheet`, the `mutedSenders`
  field, schema V5 bump, StateMerger union, and the pairing-screen
  muted indicator.

### 2. Substantive Phase 3a review
- **Manifest validator**. `TemplateObject` rejects unknown layouts,
  missing required fields, unknown fields, malformed JSONPath
  (`^\$(?:\.[A-Za-z_][A-Za-z0-9_]*)+$`), duplicate action ids.
  `PluginPublishRequest.validate_renderer_template_pairing` rejects
  template/renderer mismatch and action endpoints that don't match
  any declared-endpoints glob. Anything I missed (length caps on
  rendered values? CSRF on the action POST? rate limit?).
- **Renderer**. `TemplateCard` parses `payloadJson` once via
  `remember(payloadJson)`, resolves title/subtitle/body through
  `resolveJsonPath`, renders the standard_card layout, fires
  actions through the `onAction(id, endpoint)` callback.
  `resolveJsonPath` returns null for missing paths, wrong leaf
  types, malformed paths. Concerns?
- **Action POST**. `TemplateActionRunner.post(endpoint, payloadJson)`
  fires the full decrypted payload as the body with no
  authentication header beyond what the OkHttp client carries by
  default. Plugin authors choose the endpoint (it's in their
  declared-endpoints set). Does this leak anything you'd flag, or
  is the manifest authorship boundary sufficient?
- **Dismiss filtering**. `inbox_for_device` LEFT JOINs
  `DeliveryStatus` and filters `dismissed_at IS NULL`. Confirm this
  closes the Phase 2 carry-over and doesn't accidentally hide
  freshly-delivered messages (no DeliveryStatus row → dismissed_at
  IS NULL → kept; that's correct).

### 3. Substantive Phase 3b review
- **`card_type` / `card_key_path` persistence**. Confirm
  `services/plugins.py:publish_plugin` actually passes these to the
  `Plugin(…)` constructor (I last saw them in the signature but
  not in the constructor call — please verify, this is a likely RED).
- **`/v1/cards/upsert`**. Sender-authenticated, ed25519 envelope,
  encrypted payload (AES-GCM), `card_key` uniqueness, 48h TTL
  server-set (not caller-set), refuses revoked plugins.
- **`/v1/messages/inbox` shape**. Codex consultation 61 flagged
  that the Android repository expects `items` from the inbox endpoint
  but the server returns `messages`. Has that been reconciled? If
  not, that's a Phase 3b RED.
- **Live decrypt + AAD + sequence_number monotonicity**. Confirm
  AAD binds card_key + sender_id + plugin_id + sequence_number so
  a replay of an older sequence can't render. Confirm client merge
  picks max(sequence_number) on conflict.
- **`card.upsert` / `card.delete` SSE events**. `EventStreamManager`
  has the variants; the publish path triggers them on upsert/delete.

### 4. Substantive Phase 3c review
- **MuteStore merge semantics**. Plan says `Set<String>` with union
  merge. Codex 61 flagged: union resurrects removed values under
  stale/offline merge. Acceptable for V1 or do we need a tombstone
  with timestamp? (My read: the local-override layer already lets a
  user unmute on their own device irrespective of the synced set;
  the "unmute everywhere" path is the one that resurrects under
  union. Tradeoff.)
- **InboxScreen filter integration**. `viewModel.mutedSenderIds` is
  collected; inbox filters out items from muted senders. Confirm
  the filter runs after the dual-pile merge so live cards from a
  muted sender are also hidden.
- **PluginSettingsSheet**. Surfaces sender mute toggle + revoke
  pairing + plugin info per plan. Audit for security regressions
  (does revoke from the sheet go through the same Hilt-injected
  `AuthRepository.revokePairing` path? Any data leakage?).

### 5. Tests to add before final commit
Per agreed plan:
- Server: template publish goldens, malformed manifests rejected,
  inbox dismiss filter, live-card upsert+delete, revoked-plugin
  upsert rejection.
- Android: `resolveJsonPath` unit test, `TemplateCard` action
  callback, dismiss event triggers refresh.
- Storage: `EncryptedUserState` V0–V5 round-trip, StateMerger
  union mute, MuteStore effective set.

### Output

Per reviewer:
1. Commit-strategy recommendation (split / unified) with one-sentence
   rationale.
2. Per-sub-phase verdict (3a, 3b, 3c) — green / yellow / red.
3. List of blocker items that MUST be fixed before commit, with
   file + line refs.
4. List of tests that should be added before commit.
5. Overall: ready to commit (one or three commits) / blockers.
