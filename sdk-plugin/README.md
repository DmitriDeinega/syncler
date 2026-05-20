# Syncler Plugin SDK

TypeScript SDK for Syncler WebView plugins. Native bridge implementation is provided by the host application.

## Install

```sh
npm install
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

```ts
import { BasePlugin, DismissBehavior, registerPlugin } from '@syncler/plugin-sdk';

export class MinimalPlugin extends BasePlugin {
  static manifest = {
    id: 'com.example.minimal',
    name: 'Minimal',
    version: '1.0.0',
    senderId: 'com.example.sender',
    bundleHash: '00',
    signature: '00',
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

Build the example bundle:

```sh
npm run build:example
```

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
