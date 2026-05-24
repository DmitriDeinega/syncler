# Consultation 77 — Phase 5b fix-ups (post-76)

Consultation 76 voted: **Codex HOLD, Gemini COMMIT**. The protocol
requires dual-GREEN, so Codex's two RED blockers must be fixed before
commit. This consultation reviews the fix-ups.

## What Codex 76 caught

### RED #1 — `registerPlugin()` rejects the in-bundle placeholder manifest

> The bundle executes, `registerPlugin(new TradingPlugin())` calls
> `assertPluginManifest`, which requires 64-hex `bundleHash` and
> 128-hex `signature`, so the plugin never registers and `render()`
> never runs.

This is a **Phase 4.1 regression** — the strict 64/128-hex check
introduced by the lottery-claude DX review (commit `75eebe3`) also
breaks the existing `sdk-plugin/examples/minimal` runtime path. The
in-bundle source manifest cannot know its own bundle hash or
signature: those are produced by `tools/sign-bundle.ts` post-build
and written to `manifest.signed.json` on disk, not back into the
JS source. The host already validates the loaded bundle against
the server-stored signed manifest — the bundle's own embedded
copy is informational.

### RED #2 — hardcoded `localhost` is not reachable from Android

> On a physical phone, and normally on an emulator, `localhost` is
> the Android device/emulator, not the dev machine.

The plugin fetched a hardcoded `http://localhost:8001/api/ack` and
the bundle was published with `http://localhost:8001/plugin.bundle.js`.

## Fix-ups applied

### Fix #1 — `validatePluginManifest({ allowUnsignedPlaceholders })`

- `sdk-plugin/src/manifest.ts`: new `ValidatePluginManifestOptions`
  with `allowUnsignedPlaceholders?: boolean`. When `true`, the
  bundleHash + signature checks accept any non-empty string
  (placeholders, hex, or otherwise). When `false` (default), the
  strict 64/128-hex Phase 4.1 check still applies.
- `sdk-plugin/src/bridge.ts`: `registerPlugin` now calls
  `assertPluginManifest(manifest, { allowUnsignedPlaceholders: true })`.
  Runtime path is lenient; the host has the authoritative signed
  values from the server.
- `sdk-plugin/src/index.ts`: re-exports the new type.
- `sdk-plugin/test/manifest.test.ts`: 3 new tests — placeholder
  acceptance, empty-string still rejected, other validation still
  applies under lenient mode.
- `sdk-plugin/test/base-plugin.test.ts`: integration test —
  `registerPlugin` of a plugin with literal placeholder strings
  no longer throws.
- `sdk-plugin/README.md`, `sdk-plugin/examples/minimal/README.md`,
  `sdk-plugin/examples/minimal/src/plugin.ts`: documentation updated
  to explain the publish-vs-runtime asymmetry.

All 33 sdk-plugin tests pass.

### Fix #2 — sender-supplied `ack_url` + emulator-loopback default

The sender embeds `ack_url` into each report payload (per-message).
The plugin uses `payload.ack_url` instead of a hardcoded literal.
This way the same plugin bundle works across all three Android
connectivity modes without rebuilding/republishing.

- `examples/trading-bot/plugin/src/plugin.ts`:
  - `TradingReport` interface gains `ack_url: string`.
  - The button's onclick reads `payload.ack_url` and POSTs there.
  - `declaredEndpoints` expanded to:
    - `http://*.*.*.*:8001/api/*` — IPv4 (covers `10.0.2.2`
      emulator-loopback AND `192.168.x.x` LAN)
    - `http://localhost:8001/api/*` — covers `adb reverse
      tcp:8001 tcp:8001`
    - `https://*.example.com/api/*` — production placeholder
  - `manifest.json` updated to match.
- `examples/trading-bot/bot.py`:
  - New module-level constants `DEVICE_LAN_HOST` (default
    `10.0.2.2`), `ACK_PORT`, `ACK_URL`, `BUNDLE_URL` — derived
    from `SYNCLER_LAN_HOST` or set directly via `SYNCLER_ACK_URL`
    / `SYNCLER_BUNDLE_URL`.
  - `synthetic_report()` now includes `"ack_url": ACK_URL`.
  - `cmd_publish_plugin` uses `BUNDLE_URL` and the expanded
    `endpoints` list above.
  - `cmd_ack_server` prints the device-reachable URLs at startup.
  - Removed unused `subprocess` + `threading` imports.
- `examples/trading-bot/README.md`:
  - New "Connecting your Android device" section with a 3-row
    matrix (emulator / LAN / `adb reverse`) showing what to set
    `SYNCLER_LAN_HOST` to for each.
  - Prerequisite line revised — no longer claims the app can hit
    "localhost".
  - Env-var section expanded with `SYNCLER_LAN_HOST` and the
    derivation formulas for `SYNCLER_ACK_URL` / `SYNCLER_BUNDLE_URL`.

Smoke tests:
- `python -c "import bot; r = bot.synthetic_report(); assert 'ack_url' in r"` passes.
- `sdk-plugin/` vitest: 33/33 passing.

## What I need from each reviewer

1. **Per-area verdict** on:
   - `sdk-plugin/src/manifest.ts` + `bridge.ts` — is the
     publish-vs-runtime split correct? Anything the runtime path
     should still verify even leniently?
   - `examples/trading-bot/plugin/src/plugin.ts` — is the
     `ack_url`-from-payload approach acceptable? Anyone abusing
     this (server-side trust, injection)?
   - `examples/trading-bot/bot.py` — env var derivation chain
     sane? Default to `10.0.2.2` reasonable?
   - `examples/trading-bot/README.md` — does the connectivity
     matrix unblock both emulator and physical-device users?
2. **Anything missed** from Codex 76's REDs.
3. **Anything new** (security, DX, doc accuracy).
4. **Overall**: ready to commit / specific blockers / hold.

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, I commit Phase 5b and move to Phase 5c
(`npm create @syncler/plugin` scaffold).
