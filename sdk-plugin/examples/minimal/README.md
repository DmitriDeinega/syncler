# minimal

Reference WebView (script-renderer) plugin. The smallest possible end-to-end shape — a `BasePlugin` subclass with a `render()` that returns HTML, plus the `registerPlugin(...)` call the host expects.

## Files

- `src/plugin.ts` — the plugin source (one class, one render).
- `manifest.json` — the **unsigned** manifest. The `bundleHash` and `signature` fields are placeholders; `tools/sign-bundle.ts` fills them in.
- `build.sh` — esbuild invocation that produces `dist/plugin.bundle.js`.
- `dist/` — build output (gitignored if your global ignore covers `dist/`; tracked here so the example round-trips even without `npm install`).

## Build → sign → publish

```sh
# 1. Build the IIFE bundle (NOT ESM — the host loads it in a <script> tag).
./build.sh

# 2. Sign it. The signer reads manifest.json (placeholders), computes
#    SHA-256 over the bundle bytes, signs `(canonical manifest || bundleHash)`
#    with your Ed25519 sender key, writes manifest.signed.json with the
#    real bundleHash and signature.
npx tsx ../../tools/sign-bundle.ts \
  dist/plugin.bundle.js \
  ~/.syncler/keys/sender.pem \
  manifest.json \
  manifest.signed.json

# 3. Publish via the Python SDK (your backend, one time per version).
#    See ../../README.md and docs/integration-guide.md §5.
```

## What this example does NOT do (yet)

This is a script-renderer example with no backend round-trip. A full plugin + backend pair is on the V1.5 DX track (see `docs/ROADMAP.md`). For a sender-side example, look at `../../../examples/trading-bot/`.

## Why the placeholders look weird

`bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE'` (and the same for `signature`) is deliberate: the SDK's `validatePluginManifest` rejects this as-written (requires 64-hex `bundleHash` + 128-hex `signature`). That's by design — an unsigned manifest can't be published. The placeholder is loud rather than `'00'` so the failure mode is obvious if you forget step 2.
