# Consultation 79 — Phase 5c fix-ups (post-78)

Consultation 78 voted: **Codex HOLD, Gemini COMMIT**. Codex caught two
real REDs. This consultation reviews the fix-ups.

## What Codex 78 caught

### RED #1 — `renderer: template` scaffold emits an invalid manifest

> `renderer: template` is accepted while the scaffold does not
> generate a required `template` block.

The SDK's `validatePluginManifest` rejects `renderer: 'template'`
without an accompanying `template` block (and rejects
`renderer: 'script'` with one). The old scaffold ignored this
constraint and emitted a script-renderer body even when the user
chose `template`.

### RED #2 — generated TS string literals not safely escaped

> A display name ending in `\` produced `name: 'Weather\',` and
> breaks the scaffold. Use `JSON.stringify(...)` for generated TS
> string literals.

The previous `name.replace(/'/g, "\\'")` handled apostrophes but
not backslashes or double-quotes.

## Fix-ups applied

### Fix #1 — branched scaffold for `renderer === 'template'`

`create-plugin/index.js`:
- `renderManifestJson(ctx)` now emits a `template` block when
  `renderer === 'template'`:
  ```json
  "template": {
    "layout": "standard_card",
    "fields": {
      "title": { "path": "$.title" },
      "body":  { "path": "$.body" }
    }
  }
  ```
- `renderPluginTs(ctx)` branches on `ctx.renderer`:
  - **Template path**: ships a class with a no-op `render()` returning
    `''` (the host never invokes it for template renderer) and an
    interface that comments that the host pulls fields via
    `manifest.template.fields`. The class still exists because
    `BasePlugin.render` is `abstract`.
  - **Script path**: unchanged — the previous HTML render() body
    with the Acknowledge button.
- A shared `manifestStaticLines(...)` helper renders the
  `static manifest:` block consistently across both paths (so the
  template block is emitted in plugin.ts when needed).

### Fix #2 — `JSON.stringify` for every TS string literal

`renderPluginTs(ctx)` now uses `JSON.stringify(ctx.name)`,
`JSON.stringify(ctx.id)`, `JSON.stringify(ctx.senderId)`,
`JSON.stringify(ctx.renderer)`, `JSON.stringify(ctx.cardType)`,
`JSON.stringify(ctx.cardKeyPath)` for every author-supplied string
that lands in the generated TS source. Verified separately that
`JSON.stringify('Weather "Co"\\')` →
`"Weather \"Co\"\\\\"` and that the file round-trips through `import`
preserving the original bytes.

## Smoke tests run

1. **Template-renderer scaffold** with capability `network`:
   - Generated manifest.json: `renderer: "template"`, full
     `template` block with `title` + `body` fields.
   - Generated plugin.ts: class with `render()` returning `''`,
     manifest static carrying renderer + template block.
   - Validated against `validatePluginManifest({allowUnsignedPlaceholders: true})`:
     `valid: true`. (Without the template block fix, this would
     return `valid: false, issues: ["template required when renderer is \"template\""]`.)
2. **Script + live-card combo** with capability `network`,
   cardKeyPath `$.id`:
   - Manifest emits `cardType: "live"`, `cardKeyPath: "$.id"`,
     omits `renderer` (script is the default).
   - Validates clean.
3. **JSON.stringify round-trip** verified separately on a value
   containing both `"` and `\`:
   - Input: `Weather "Co"\\`
   - Generated TS literal: `name: "Weather \"Co\"\\\\",`
   - File parsed back: `mod.x.name === input` (true).
4. **Earlier smoke tests** still pass: pipe-input flow with
   `script`/`event`/`network,storage`, runtime validation, build.sh
   walk-up to monorepo source, dist/plugin.bundle.js produced.

## What I need from each reviewer

1. **Per-area verdict** on:
   - Template-renderer scaffold (manifest + plugin.ts) — is the
     no-op `render()` + minimal `template` block the right shape
     for V0.1? Anything missing for a published template plugin?
   - JSON.stringify escaping — used consistently? Anything still
     emitting unsafe template literals?
   - Per-area regressions on the script-renderer path?
2. **Anything missed** from Codex 78's REDs.
3. **Anything new** (security, footgun, doc accuracy).
4. **Overall**: ready to commit / specific blockers / hold.

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5c and move to Phase 5d.
