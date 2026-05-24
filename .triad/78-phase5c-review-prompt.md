# Consultation 78 — Phase 5c review (`npm create @syncler/plugin`)

Phase 5c — npm create scaffold — is implemented per the agreement at
`.triad/70-phase5-agreement.md`. Single review pass (vs 3-cycle for
substantive design) since the scope is locked in the agreement and
this is a self-contained UX wrapper.

## What landed (new `create-plugin/` directory)

### `create-plugin/package.json`
- Package name `@syncler/create-plugin`. `npm create @syncler/plugin <name>`
  invokes `npx @syncler/create-plugin <name>` per npm's `create-` convention.
- `bin` → `./index.js`, `files: ["index.js", "templates/"]`,
  `engines.node: ">=18"`. Stdlib-only — no inquirer, chalk, etc.

### `create-plugin/index.js`
- Plain Node stdlib + ESM. Interactive prompts driven by `readline`
  via a `makeAsker(rl)` helper that uses the `line` event so prompts
  work under both TTY and piped stdin (the `rl.question` API silently
  breaks on the 2nd prompt under piped stdin — surfaced during local
  smoke testing).
- Prompts:
  - **Project directory name** (positional arg or interactive).
    Validated against `^[a-z][a-z0-9_-]*$` so PascalCase always
    produces a valid TS class identifier.
  - **Plugin id** — reverse-DNS regex (mirrors server + SDK).
  - **Display name** — defaults to dir name.
  - **Sender id** — UUID regex.
  - **Renderer** — `script|template` (default `script`).
  - **Card type** — `event|live` (default `event`).
  - **Capabilities** — multi-select from
    `network/storage/camera/gallery/file/location/background-exec`.
    (`showNotification` is NOT a capability — it's an
    always-available platform method, so the agreement's wording
    was slightly off and we follow the actual enum.)
  - **`card_key_path`** — only when `cardType: 'live'`, must
    start with `$`.
- Capability identifier mapping handles hyphens:
  `background-exec` → `Capability.BACKGROUND_EXEC`.

### Generated scaffold output
```
<name>/
  src/plugin.ts        # BasePlugin subclass with hostPreview + render() + Acknowledge button
  manifest.json        # UNSIGNED placeholders, fields filled
  build.sh             # esbuild --alias to monorepo sdk-plugin/src/index.ts
  package.json         # devDeps: esbuild + tsx + typescript (no @syncler/plugin-sdk)
  .gitignore           # dist/, manifest.signed.json, node_modules/, *.pem
  README.md            # V0.1 prerequisite section + build/sign/publish walkthrough
```

### V0.1 SDK distribution decision

`@syncler/plugin-sdk` is not yet on npm. Two paths considered:

1. **Defer the scaffold until SDK is published.** Rejected — the
   agreement called for the scaffold in Phase 5c, and authors can
   meaningfully use it for in-monorepo dev today.
2. **Ship the scaffold with a monorepo-aware build.** Chosen.
   `build.sh` walks up parent directories from `$HERE` looking for
   `sdk-plugin/src/index.ts`, then uses esbuild's `--alias` to
   resolve `@syncler/plugin-sdk` imports to that source. Overridable
   via `SYNCLER_SDK_SRC=/abs/path`. Fails loudly with a clear
   actionable message when the SDK source isn't found.

This matches what `examples/trading-bot/plugin/build.sh` does (also
uses `--alias`), so the scaffold's pattern is already validated by
Phase 5b. The README's "V0.1 prerequisite" section calls out the
limitation and shows the one-line fix once the SDK is published
(remove the alias, `npm install @syncler/plugin-sdk`).

## Smoke tests run

1. **Pipe input test**:
   ```sh
   printf 'com.example.weather\nWeather\n<uuid>\nscript\nevent\nnetwork,storage\n' \
     | node create-plugin/index.js testpkg-fake
   ```
   Produces a complete scaffold and exits 0. Earlier, before the
   `makeAsker` rewrite, this would silently exit after 2 prompts
   because of Node's `rl.question`-with-piped-stdin behavior.

2. **Live-card path**: same with `live` + `$.order_id` cardKeyPath
   produces `cardType: 'live'` + `cardKeyPath: '$.order_id'` in
   manifest.json AND plugin.ts.

3. **Capability identifier mapping**:
   `network,background-exec` produces
   `declaredCapabilities: [Capability.NETWORK, Capability.BACKGROUND_EXEC]`
   in plugin.ts (underscore-converted) and
   `["network","background-exec"]` in manifest.json (raw enum value).

4. **Manifest validates clean against the SDK**:
   `validatePluginManifest(scaffold.manifest, { allowUnsignedPlaceholders: true })`
   returns `{ valid: true }`. Strict mode still rejects (placeholders
   not 64/128 hex), forcing the sign step before publish. Verified
   via in-repo vitest.

5. **End-to-end build inside the monorepo**:
   - Scaffolded `examples/scaffoldtest/`
   - `bash build.sh` → wrote `dist/plugin.bundle.js` (9.7 kB IIFE
     bundle). Output confirmed: `Built dist/plugin.bundle.js
     (aliased @syncler/plugin-sdk -> /d/.../sdk-plugin/src/index.ts)`.
   - Bundle head matches `var SynclerPluginExports = (() => { ... })`
     IIFE shape.

## What I need from each reviewer

1. **Per-area verdict** on:
   - `create-plugin/index.js` — prompt UX, validation regexes, the
     `makeAsker` readline workaround, capability mapping.
   - The generated `plugin.ts` template — type signature, escape
     fn, registerPlugin call, manifest static.
   - The generated `manifest.json` — required fields present,
     placeholders intentional, declaredEndpoints sane.
   - The generated `build.sh` — walks-up logic, alias resolution,
     error message clarity.
   - The generated `README.md` — does it set expectations
     correctly for V0.1 + post-publish migration?
2. **SDK-not-on-npm decision** — is the walk-up + alias approach
   acceptable for V0.1, or should the scaffold demand the SDK
   first land on npm?
3. **Anything new** (security, footgun, doc accuracy) introduced.
4. **Overall**: ready to commit / specific blockers / hold.

## Output

Per reviewer:
1. Per-area: GREEN / YELLOW / RED.
2. Blockers if any.
3. Commit-readiness vote.

If both GREEN, commit Phase 5c and move to Phase 5d (validator
polish batch — 5 items).
