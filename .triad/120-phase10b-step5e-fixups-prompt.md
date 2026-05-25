=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 120 — Phase 10b step 5e fixups (post-119)

Triad 119: Gemini GREEN. Codex RED with two blockers + two nits.
All addressed at `2e95b7f`.

## v2 deltas (vs `e2b82ba`)

1. **`shouldInterceptRequest` ported from `PluginWebViewClient`** (Codex 119 RED blocker).
   `RealPluginWebViewHost.ReadinessWebViewClient` now mirrors the
   legacy in-process loader byte-for-byte:
   - `shouldInterceptRequest` allows the synthetic
     `https://plugin.local/` self-load on the FIRST hit only
     (tracked via `initialSelfLoadSeen`).
   - Every other URL (including a second hit to
     `plugin.local`) returns a 403 `WebResourceResponse` with
     `Cache-Control: no-store`. This closes the
     `<img>`/`<script>`/`fetch()` subresource bypass of the
     audited `platform.network.fetch` capability path.

2. **`onRenderProcessGone` ported** (Codex 119 strong rec).
   Renderer crash or memory-pressure kill now surfaces as
   `reportError("renderer_crash" | "renderer_killed", "")` so
   the host gets a structured `onWebViewError` callback
   instead of waiting on `:plugin` service-disconnected
   death. Returns `true` so the host process survives.

3. **`onPageStarted` short-circuit** (legacy parity).
   Non-initial main-frame nav stops the load AND reports
   `webview_navigation_blocked` via `reportError`. Belt and
   suspenders with `shouldOverrideUrlLoading` which now also
   returns `true` unconditionally.

4. **Stale comments fixed** (Codex 119 nits):
   - `PluginSandboxServiceTest.kt` header no longer claims
     step 5d will "close" the ready-signal gap (5d already
     shipped). Replaced with the actual scope statement.
   - `PluginTokenCoordinator.unload()` no longer says the
     pump is drained; clarified channel-close races the
     destroy.

## Files

- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/RealPluginWebViewHost.kt` (ReadinessWebViewClient expanded)
- `android/feature/plugin-sandbox/src/main/kotlin/app/syncler/feature/pluginsandbox/PluginTokenCoordinator.kt` (comment)
- `android/feature/plugin-sandbox/src/androidTest/kotlin/app/syncler/feature/pluginsandbox/PluginSandboxServiceTest.kt` (header comment)

## Test status

- `:feature:plugin-sandbox:compileDebugKotlin` succeeds.
- `:feature:plugin-sandbox:compileDebugAndroidTestKotlin` succeeds.
- `:feature:plugin-sandbox:testDebugUnitTest` — 6 tests pass.

## Output

Per reviewer, terse:

1. Verdict on the fixups: GREEN / YELLOW / RED + items.
2. Anything still missing before step 6 (strip in-process WebView).
3. Anything new.

If dual-GREEN, step 6 begins.

**Reply text only. Do NOT call any write/mutation tool. Do not commit, edit, or stage anything.**
