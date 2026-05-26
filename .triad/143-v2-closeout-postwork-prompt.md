=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 143 — V2 closeout post-work review

Triad 142 was the pre-work design pass for V2 closeout
(items 1-4 from triad 140's deferred list). All four items
implemented and shipped as four focused commits per the
agreed plan. This is the post-work review.

v0.1 dev posture preserved.

## Commits in scope

| Commit  | Item                                | Content |
|---------|-------------------------------------|---------|
| 740e6ce | A: AuditLogger rename               | `denied(...)` → `record(...)`; log field `reason=` → `outcome=`; ~10 call sites |
| acb95e3 | B: inbox plugin.onAction routing    | `PluginRegistry.dispatchAction` returns `ActionDispatchOutcome` enum; InboxViewModel routes through plugin first, falls back to fire-and-forget POST on `PLUGIN_NOT_LOADED` |
| eef9572 | C: Settings UI revoke               | `PluginPermissionsCard` Composable in `:feature:settings`; lists all stored grants per-plugin with revoke + last-used hint + privacy tip on last-plugin OS-permission revoke; `allGrants()` DAO added |
| df0d0b0 | D: Compose UI tests                 | 6 instrumented tests in `feature:inbox/src/androidTest/.../TemplateCardTest.kt`; covers all 4 V2 #12 layouts + unsupported-layout fallback |

## Files in primary review scope

### A — AuditLogger rename
- `android/feature/plugin-host/src/main/kotlin/.../AuditLogger.kt`
- ~10 caller files in `:feature:plugin-host` (all renamed via
  `sed -i 's/auditLogger\.denied(/auditLogger.record(/g'`).

### B — Inbox action routing
- `android/feature/plugin-host/src/main/kotlin/.../PluginRegistry.kt`
  — `dispatchAction` returns `ActionDispatchOutcome`; new enum.
- `android/feature/inbox/src/main/kotlin/.../InboxScreen.kt`
  — `runTemplateAction(pluginId, actionId, endpoint,
  payloadJson)` signature changed; new dispatch-then-fallback
  flow.
- `android/feature/inbox/build.gradle.kts` — `:feature:plugin-host`
  added as `implementation`.

### C — Settings UI revoke
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/PluginCapabilityDb.kt`
  — `PluginCapabilityGrantDao.all()` added.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityGrantStore.kt`
  — `allGrants()` method added.
- `android/feature/plugin-host/src/main/kotlin/.../capabilities/CapabilityGrantDialog.kt`
  — wording updated to "Settings → Plugin permissions".
- `android/feature/settings/src/main/kotlin/.../PluginPermissionsCard.kt`
  — NEW Composable + `PluginPermissionsViewModel`.
- `android/feature/settings/build.gradle.kts` — `:feature:plugin-host`
  added as `implementation`.
- `android/app/src/main/kotlin/.../ui/SettingsScreen.kt`
  — `PluginPermissionsCard` embedded.

### D — Compose UI tests
- `android/feature/inbox/src/androidTest/kotlin/.../TemplateCardTest.kt`
  — NEW (6 tests).
- `android/feature/inbox/build.gradle.kts` — `ui-test-junit4`
  androidTest dep + `ui-test-manifest` debug dep.

## What I'm worried about

1. **A (rename) sed-based migration.** Used a bare
   `sed -i 's/auditLogger\.denied(/auditLogger.record(/g'`
   across the module. Could it have hit a string literal
   somewhere it shouldn't have? I think not — `auditLogger.`
   prefix is specific — but worth eyeballing.

2. **B (action routing) bypass-when-not-loaded.** When the
   plugin isn't loaded the fallback fires
   `TemplateActionRunner.post` directly, identical to V1
   behavior. Codex 142 #2 wanted "load-or-dispatch first,
   fallback only after explicit failure" — meaning we
   should try LOADING the plugin before falling back. I
   deferred that to a NIT follow-up (the "plugin usually
   loaded" reality). Is the deferral OK or does the fallback
   weaken V2 #11's promise too much?

3. **B argument plumbing.** `runTemplateAction` now requires
   `pluginId` in addition to the existing `actionId, endpoint,
   payloadJson`. The caller (`InboxScreen`) passes
   `selectedItem.pluginId`. Is that the right field — i.e.
   does `InboxItem.pluginId` equal the plugin_row_id the
   PluginRegistry uses as its key? (See InboxRepository line
   642.)

4. **C (Settings UI) PluginPermissionsViewModel construction.**
   The VM creates a fresh `CapabilityGrantStore(context)` on
   first use. The grant store is process-singleton-shaped via
   `PluginCapabilityDb.open(context)` and the SecurePrefs
   passphrase. Multiple VM instances across config-change
   recreations should hit the same underlying DB but
   construct multiple wrapper instances with separate
   negative-caches. Is that a real problem (cache
   inconsistency where one instance's revoke isn't visible
   to another) or a non-issue?

5. **C OS-permission interaction text.** "You can also
   disable Syncler's camera access in App Info if you don't
   need it." Just text — doesn't link to App Info, doesn't
   query OS permission state. Adequate v0.1, or do we owe
   the user a tap-to-launch link?

6. **C empty-state.** "No plugin capability grants stored."
   shown when `allGrants()` returns empty. Both before any
   plugin grants AND after all are revoked. Worth
   differentiating, or is the unified message fine?

7. **D (Compose UI tests) androidTest deferral.** Tests run
   only with `connectedDebugAndroidTest` (needs device/
   emulator). No CI coverage until V0.2 Robolectric work.
   Both reviewers' v0.1 verdict was "Option B (semantics) is
   acceptable"; codex pushed back wanting geometry. I shipped
   a hybrid: semantics + visibility-of-all-children. Honest
   about the gap?

8. **D stat_grid weight regression detection.** The most
   likely layout regression class — a typo on `weight(1f)` —
   isn't caught by visibility semantics. Codex 142 explicitly
   said this. Worth a follow-up triad on whether Robolectric
   should land before V3, or accept the gap?

9. **Cross-commit consistency.** All four commits compile
   independently? I ran `:app:assembleDebug` after each but
   didn't verify that the commits, applied in sequence on a
   fresh checkout, each pass tests at the time of the commit
   (this matters for `git bisect` health). Risk?

10. **Missing closeout items.** Anything else from the V2
    deferred backlog I overlooked? Per triad 140 the list
    was: items 1-4 in this triad + script_fast (deferred to
    V0.2). Confirm.

## Test status

```
:feature:plugin-host:testDebugUnitTest      — all green (unchanged)
:feature:plugin-native-sandbox:testDebugUnitTest — all green
:feature:plugin-sandbox:testDebugUnitTest    — all green
:feature:inbox:testDebugUnitTest             — all green
:app:assembleDebug                           — green
:feature:inbox:compileDebugAndroidTestKotlin — green
:feature:inbox:connectedDebugAndroidTest     — NOT RUN (no emulator
                                                in dev env)
server pytest tests/                         — 196/196 (unchanged from
                                                pre-V2-closeout)
```

## What I'm asking for

Per-item verdict (OK / NIT / FIX / DESIGN) for each numbered
concern. Plus anything you spot in the commits' diffs that
warrants a fix before V3 implementation begins.

Focus on:
- Argument plumbing correctness in B (does
  `InboxItem.pluginId` == PluginRegistry key?).
- VM cache-coherence in C (multiple CapabilityGrantStore
  instances).
- Test coverage adequacy in D (codex 142 already pushed back
  on pure semantics; did the hybrid satisfy that?).

Skip cosmetics; flag substance. Goal: greenlight to begin V3
#14 implementation, or a clear FIX list to apply first.
