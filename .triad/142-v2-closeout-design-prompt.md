=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 142 — V2 closeout design (pre-work review)

Per user direction: finish V2 properly with full triad protocol
(design pre-work + closeout post-work) BEFORE moving to V3.
This is the pre-work design review.

Triad 140 (the V2 #11/#12/#13 retroactive closeout) identified
five deferred items that didn't ship in the original commits:

1. **Settings UI revoke for Phase 12 capability grants** —
   gemini 139 FIX + 140 FIX. The grant dialog promises "Revoke
   in Settings"; that path doesn't exist yet, so once a user
   grants `location.fine`, they cannot revoke it without
   uninstalling the plugin. This is a hard product-promise
   gap.

2. **Inbox UI plugin.onAction routing** — codex 140 #4 FIX
   (Gemini said OK / engine-first). Today the inbox card's
   action button calls `TemplateActionRunner.post(endpoint,
   payloadJson)` fire-and-forget. V2 #11 promised the action
   would route through `plugin.onAction(actionId, payload)`
   first so plugins can interpose. Without this, V2 #11 is
   half-shipped (bridge contract exists; UI flow doesn't use
   it).

3. **`AuditLogger.denied` → `record` rename** — both reviewers
   NIT in 140. Method is called for success + failure outcomes;
   the name is misleading.

4. **Compose golden tests for V2 #12 layouts** — both NIT/DESIGN
   in 140. Server-side validator + JSONPath-resolver tests
   exist; the Compose renderers themselves (standard_card,
   compact_row, score_card, stat_grid) have NO test coverage.
   A weight(1f) regression won't be caught.

5. **script_fast engine + SDK guidance** — both DESIGN in 140.
   The server now rejects `script_fast` until V0.2; this is
   the V0.2 design conversation, not V2 closeout. SKIP for
   this round — V0.2 work.

This prompt covers items 1-4. The V0.2 engine choice (item 5)
is a separate triad that fires when we start V0.2 proper.

## Item 1 — Settings UI revoke

### Proposed shape

New Settings sheet entry: "Plugin permissions" (or similar
label). Lists every plugin row that has at least one
capability grant in `plugin_capability_grants`, grouped by
`plugin_identifier`. Each row shows the granted capabilities
with a `Revoke` action per capability.

Implementation:
- A new `CapabilitySettingsScreen` Composable in
  `feature/settings`.
- `CapabilitySettingsViewModel` reads via
  `CapabilityGrantStore.forPlugin(pluginRowId)` for each
  loaded plugin (or all plugins with grants if a "history"
  view is wanted).
- Revoke calls `CapabilityGrantStore.revoke(pluginRowId,
  capability)` which deletes the row + invalidates the
  in-memory negative-cache (already implemented).
- The next bridge invocation that needs the capability will
  re-prompt via `CapabilityGrantPrompter` (for location.*) or
  re-launch the OS picker (for camera/gallery/file).

Open questions:
- **Discovery surface.** Where in the existing Settings
  screen tree does the new entry live? Today the Settings
  screen has: account info, devices list, logout. Probably a
  new "Plugin permissions" entry between devices and logout.
- **OS permission interaction.** Revoking the plugin grant
  doesn't revoke the OS permission. Camera permission stays
  granted at the OS level even after the user revokes
  `camera` for a specific plugin. Is that the right semantic?
  Alternative: when a plugin grant is revoked AND no other
  plugin holds the same OS permission grant, additionally
  prompt the user to revoke the OS permission via
  ACTION_APP_DETAILS_SETTINGS.
- **Per-capability vs per-plugin pivot.** Triad 138 gemini
  agreed per-plugin pivot is the v0.1 surface (more
  discoverable). Per-capability pivot ("who has camera?")
  is V2-closeout-deferred.
- **Last-used hint.** The audit table stores
  `lastInvokedAtMs` per grant. Show "Last used 2 hours ago"?
  Or omit (extra UI complexity for v0.1)?

## Item 2 — Inbox UI plugin.onAction routing

### Current flow

```
User taps action button
  → InboxScreen.onActionTap(actionId, endpoint)
  → InboxViewModel.runTemplateAction(actionId, endpoint, payloadJson)
  → TemplateActionRunner.post(endpoint, payloadJson)
  → fire-and-forget HTTP POST
```

The plugin's `onAction` hook is never called.

### Proposed new flow

```
User taps action button
  → InboxScreen.onActionTap(actionId)
  → InboxViewModel.dispatchPluginAction(itemId, actionId)
  → PluginRegistry.dispatchAction(pluginId, actionId, {messageId})
  → plugin's onAction(actionId, payload) runs
  → plugin calls ctx.messageRespond(actionId, payload) if it
    wants to POST to the sender
  → MessageBridge.respond looks up endpoint by actionId via
    plugin.actionEndpoints (already host-trusted post triad
    140 #1 fix)
  → returns {status, body} to the plugin
  → plugin can show a notification / update card state
```

### Trade-offs

- **Discoverability for plugin authors:** the action button's
  endpoint is no longer "the URL the user POSTs to" — it's
  metadata the host uses for messageRespond lookup. Worth
  surfacing in the SDK docs.
- **Backwards compat:** templates published before V2 #11
  declare actions without expecting the plugin to be loaded
  during action handling. The fire-and-forget path was
  always "best-effort" — if the plugin isn't loaded, the
  POST still happens. The new flow no-ops the action if the
  plugin isn't loaded. That's a behavioral change worth
  pinning.
- **Loading semantics:** does the host auto-load the plugin
  when a stale-inbox card is acted on? Today the plugin is
  loaded eagerly on card render. If a user keeps the app
  open long enough for the plugin to unload (memory
  pressure), then taps an action — should the action wait
  for plugin reload, or fall back to fire-and-forget POST?

### Proposed semantic

If the plugin is loaded → route through plugin.onAction.
If the plugin is not loaded → fall back to
fire-and-forget POST (existing behavior). Plugin author
opts into the request/response handshake by being a
long-running plugin (which most are).

Open question: is this fallback the right default, or
should it always go through plugin.onAction even if that
means "load the plugin first" delays?

## Item 3 — AuditLogger rename

### Current signatures

```kotlin
class AuditLogger {
    fun denied(pluginId: String, reason: String, detail: String) { ... }
}
```

Method is called for both denial outcomes (where the name
fits) and success outcomes (where it doesn't):

```kotlin
auditLogger.denied(plugin.manifest.id, "respond_ok", endpoint)
```

That's confusing log output.

### Proposed rename

```kotlin
class AuditLogger {
    fun record(pluginId: String, outcome: String, detail: String) { ... }

    @Deprecated("renamed to record", replaceWith = ReplaceWith("record"))
    fun denied(pluginId: String, reason: String, detail: String) =
        record(pluginId, reason, detail)
}
```

Keep `denied` as a thin alias for one release cycle so
existing callers don't break, then remove. Or just batch-
rename all 30+ call sites in one commit since v0.1.

Trivial item — including for triad completeness but
expecting NIT-or-OK from both reviewers.

## Item 4 — Compose golden tests for V2 #12 layouts

### Current state

`feature/inbox/.../TemplateCard.kt` has four `@Composable
private fun` renderers (StandardCard, CompactRow,
ScoreCard, StatGrid). No test surface — the project doesn't
have a Compose UI test runner wired.

### Proposed approach

Two options:

**Option A — Compose preview snapshot tests** via Paparazzi
(or Roborazzi). Tests render the composables to PNG and
diff against checked-in baseline images. Catches layout
regressions (weight changes, padding drifts) without
needing an emulator. Adds a new test dep.

**Option B — Compose semantics tests** via
`androidx.compose.ui.test` + ComposeTestRule. Tests assert
on `onNodeWithText("Score")`, `assertWidthIsAtLeast(...)`
etc. No baseline images; structural checks only. Lighter
dep footprint, easier to maintain, less coverage.

**Option C — defer**. Server validator + JSONPath resolver
tests stay as the safety net. Document the gap in
`docs/integration-guide.md`. V0.2 work.

I lean Option B (semantics tests) — the layouts are simple
enough that "are the right Texts present + in the right
hierarchy" is sufficient coverage. Paparazzi-style snapshot
diffing is more maintenance for what's gained.

Open question: do the triad reviewers agree that semantics-
test coverage is enough for v0.1, or is the lack of pixel
diff a real risk?

## Concerns I want a second opinion on

1. **Per-capability OS-permission interaction (item 1).**
   When the user revokes a plugin's camera grant in our
   Settings, do we ALSO offer to revoke the OS-level
   `android.permission.CAMERA` if no other plugin still
   holds the grant? More user-friendly + privacy-tighter,
   but adds a coordination point (we need to track all
   plugins' grant state to know if our OS perm is still
   needed).

2. **last-used hint in Settings (item 1).** Show or omit?
   Privacy-positive (user sees activity) but adds a slow
   DB read per row.

3. **Fallback semantic for plugin.onAction routing (item 2).**
   If plugin isn't loaded, fall back to fire-and-forget POST
   or block until plugin loads?

4. **AuditLogger deprecation window (item 3).** Batch-rename
   30+ call sites in one commit (v0.1 simplicity) or keep
   `denied` as a deprecated alias for one cycle?

5. **Compose test approach (item 4).** Option A
   (Paparazzi/snapshot), Option B (semantics), or Option C
   (defer)?

6. **Bundled vs. split commits.** Should items 1-4 ship as
   one "V2 closeout" mega-commit, or as four focused commits?
   Settings UI (item 1) is substantial; the rest are
   smaller. My lean: four focused commits, all under one
   triad post-work review (triad 143).

7. **Pre-work triad scope.** Am I missing any V2 deferred
   item? The five from triad 140 are accounted for here
   (item 5 = script_fast V0.2 work explicitly skipped).

## What I'm asking for

For each numbered concern, a verdict (OK / NIT / FIX / DESIGN)
+ recommendation. Plus:
- Any missing V2 deferred item I haven't surfaced.
- Any item where the proposed shape is wrong enough to need
  a v2 design pass (rather than going to implementation
  after this review).

Focus on:
- Settings UI UX (item 1) — is the per-plugin pivot + OS
  permission interaction right?
- Action-flow change (item 2) — does the fallback semantic
  preserve V1 behavior enough to ship?
- Test approach (item 4) — semantics vs snapshots vs defer.

Skip cosmetics; flag substance. Goal: a clear go-list for
the implementation phase, with anything else explicitly
deferred to V0.2.
