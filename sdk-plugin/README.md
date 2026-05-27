# Syncler Plugin SDK

TypeScript SDK for Syncler WebView plugins. Native bridge implementation is provided by the host application.

> **Closed beta.** API surface is unstable and may change without
> notice between `0.x` releases. Plugins built against this SDK
> are signed and published to a Syncler server you must have access
> to — `npm install @syncler/plugin-sdk` works, but it's only
> useful in combination with a provisioned Syncler sender + server
> base URL.

## Install

```sh
npm install @syncler/plugin-sdk
```

## Build the SDK locally (only needed if developing the SDK itself)

```sh
npm install
npm run build
```

## Build

```sh
npm run build
```

## Test

```sh
npm test
```

## Minimal Plugin

A runnable reference plugin lives at `examples/minimal/`. Source, build script, and unsigned manifest are all there — the README's snippet is the same code in shorter form:

```ts
import { BasePlugin, DismissBehavior, registerPlugin } from '@syncler/plugin-sdk';

export class MinimalPlugin extends BasePlugin {
  static manifest = {
    id: 'com.example.minimal',
    name: 'Minimal',
    version: '1.0.0',
    senderId: 'com.example.sender',
    // The in-bundle source manifest can't know its own bundle hash or
    // signature — those are produced by sign-bundle.ts post-build into
    // manifest.signed.json, not back into this JS source. registerPlugin
    // accepts placeholders at runtime; the publish-side validator (server,
    // sign-bundle, client.publish_plugin) enforces 64/128-hex strict mode.
    bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    declaredCapabilities: [],
    declaredEndpoints: [],
    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  };

  render(data: { text: string }) {
    return `<div>${data.text}</div>`;
  }
}

registerPlugin(new MinimalPlugin());
```

Build the example bundle, then sign it, then validate:

```sh
npm run build:example                                             # writes examples/minimal/dist/plugin.bundle.js
npx tsx tools/sign-bundle.ts \
  examples/minimal/dist/plugin.bundle.js \
  ~/.syncler/keys/sender.pem \
  examples/minimal/manifest.json \
  examples/minimal/manifest.signed.json
```

The signer reads the unsigned `manifest.json`, computes SHA-256 over the bundle, signs `(canonical manifest || bundleHash)` with Ed25519, and writes a NEW file `manifest.signed.json` with `bundleHash` + `signature` populated. The original `manifest.json` is the human-edited source-of-truth and is left unchanged. `manifest.signed.json` is what passes strict `validatePluginManifest` and what your backend ships to `client.publish_plugin(...)`. The runtime `registerPlugin` path uses lenient `validatePluginManifest(..., { allowUnsignedPlaceholders: true })`, since the host validates the loaded bundle against the server-stored signed values rather than the bundle's own embedded copy.

The native host calls `__syncler_internal_dispatch(hook, args)` after loading the plugin bundle. Plugin bundles should register exactly one plugin instance with `registerPlugin`.

## Bridge Wrappers

- `storage.get/set/delete` forwards to `platform.storage`.
- `network.fetch` checks `manifest.declaredEndpoints` before calling `platform.network.fetch`.
- `showNotification` and `notifications.show` forward to `platform.showNotification`.

Endpoint patterns support `*` as a single path or host segment wildcard. For example, `https://api.example.com/v1/*` matches one path segment under `/v1/`, and `https://*.example.com/feed` matches one host segment.

## Signing

`tools/sign-bundle.ts` computes a SHA-256 bundle hash and Ed25519 signature over the JavaScript bundle:

```sh
npx tsx tools/sign-bundle.ts examples/minimal/dist/plugin.bundle.js private_key.pem examples/minimal/manifest.json
```
