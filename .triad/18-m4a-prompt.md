# M4a — Plugin SDK (JavaScript) + JS bridge contract

You completed M1–M3. M4a defines the JavaScript SDK that plugin authors inherit from. The matching native bridge (WebView host) is M4b.

Workspace-write granted to `d:\Projects\syncler\`. Touch only `sdk-plugin/`.

## Scope of M4a

A standalone TypeScript/JavaScript package at `sdk-plugin/`:

- `BasePlugin` class — plugins extend it
- Type definitions for the bridge API (`platform.showNotification`, `platform.storage.*`, `platform.network.fetch`, etc.)
- A small runtime shim that wraps the `window.platform` injected object with a type-safe API and error handling
- Manifest schema + validator
- Build outputs: a single signed bundle format
- Tests + a minimal example plugin

This is NOT the Android implementation of the bridge — that's M4b. M4a is the JS-side contract.

## Top-level structure

```
sdk-plugin/
  package.json
  tsconfig.json
  esbuild.config.mjs           # bundles to single ESM .js
  src/
    index.ts                   # public exports
    base-plugin.ts             # BasePlugin abstract class
    platform.ts                # type definitions for `platform.*` injected globals
    manifest.ts                # manifest schema + validator
    enums.ts                   # DismissBehavior + Capability + NotificationImportance
    storage.ts                 # platform.storage typed wrapper
    network.ts                 # platform.network.fetch typed wrapper (with declared-endpoint check)
    notifications.ts           # platform.showNotification typed wrapper
    bridge.ts                  # __syncler_internal_dispatch entrypoints the host calls
  test/
    base-plugin.test.ts
    manifest.test.ts
    storage.test.ts
    network.test.ts
  examples/
    minimal/
      manifest.json
      src/plugin.ts
      build.sh
  README.md
```

## API contract (locked, must match the host)

### `platform` global (injected by WebView host)

```ts
declare const platform: {
  showNotification(opts: { title: string; body: string; importance?: 'low'|'default'|'high'; groupId?: string }): Promise<void>;
  storage: {
    get(key: string, opts?: { scope?: 'device' | 'user' }): Promise<string | null>;
    set(key: string, value: string, opts?: { scope?: 'device' | 'user' }): Promise<void>;
    delete(key: string, opts?: { scope?: 'device' | 'user' }): Promise<void>;
  };
  network: {
    fetch(url: string, init?: RequestInit): Promise<Response>;
  };
  camera: {
    capture(opts?: { quality?: number }): Promise<{ blob: Blob; mimeType: string } | null>;
  };
  gallery: {
    pick(opts?: { mimeTypes?: string[]; multiple?: boolean }): Promise<Blob[]>;
  };
  file: {
    pick(opts?: { mimeTypes?: string[] }): Promise<Blob | null>;
  };
  location: {
    current(opts?: { accuracy?: 'low' | 'high' }): Promise<{ lat: number; lng: number; accuracy: number } | null>;
  };
  message: {
    respond(actionId: string, payload: unknown): Promise<void>;
    dismissBehavior(behavior: DismissBehavior): void;
  };
  __version__: string;          // host's bridge version, for compat checks
};
```

### `BasePlugin` (abstract)

```ts
export abstract class BasePlugin {
  static manifest: PluginManifest;             // must be defined by subclasses
  abstract render(data: unknown): string | Promise<string>;     // returns HTML string for card view
  async onMessage(decryptedPayload: unknown): Promise<NotificationDescriptor | void> { /* default no-op */ }
  async onAction(actionName: string, payload: unknown): Promise<void> { /* default no-op */ }
  async onDismiss(deviceId: string): Promise<DismissAction | void> { /* default no-op; only called if manifest.dismissBehavior === CUSTOM_CALLBACK */ }
}
```

### Manifest schema

```ts
export interface PluginManifest {
  id: string;                         // reverse-DNS, e.g. "com.lottery.app"
  name: string;
  version: string;                    // semver
  senderId: string;
  bundleHash: string;                 // hex
  signature: string;                  // hex (Ed25519)
  declaredCapabilities: Capability[];  // subset of: network, storage, camera, gallery, file, location, background-exec
  declaredEndpoints: string[];         // URL patterns; * allowed as path/segment wildcard
  dismissBehavior: DismissBehavior;
  minPlatformVersion: string;
}
```

### Enums

```ts
export enum DismissBehavior {
  DISMISS_ALL = 'dismiss_all',
  DISMISS_LOCAL_ONLY = 'dismiss_local_only',
  MARK_READ_ALL = 'mark_read_all',
  CUSTOM_CALLBACK = 'custom_callback',
}

export enum Capability {
  NETWORK = 'network',
  STORAGE = 'storage',
  CAMERA = 'camera',
  GALLERY = 'gallery',
  FILE = 'file',
  LOCATION = 'location',
  BACKGROUND_EXEC = 'background-exec',
}
```

### Bridge dispatch (called by the WebView host)

```ts
// global function that the host invokes via WebView.evaluateJavascript
function __syncler_internal_dispatch(
  hook: 'onMessage' | 'onAction' | 'onDismiss',
  args: unknown[]
): Promise<unknown>;
```

The shim registers the loaded plugin instance, then implements `__syncler_internal_dispatch` to route to the correct method. Errors are caught and serialized via JSON.stringify to satisfy the host's expectation that it can show error logs.

### Endpoint allowlist validator

`network.ts` enforces that `platform.network.fetch(url, ...)` URLs match one of the plugin's `declaredEndpoints` (`*` glob match). If not, throws `EndpointNotDeclaredError` BEFORE calling the bridge.

## Build artifacts

`esbuild` bundles `src/index.ts` + a plugin's own `src/plugin.ts` (in examples) to a single ESM file `plugin.bundle.js`. The signature is computed by an external tool (M4a includes a `tools/sign-bundle.ts` script that takes a `.js` + a `private_key.pem` → produces a signed manifest).

## Example plugin (`examples/minimal/`)

A trivial plugin showing the smallest possible thing:
- `manifest.json` — a sample manifest (without signature; built artifact has it)
- `src/plugin.ts`:
  ```ts
  import { BasePlugin, DismissBehavior } from '@syncler/plugin-sdk';
  
  export class MinimalPlugin extends BasePlugin {
    static manifest = { id: 'com.example.minimal', name: 'Minimal', version: '1.0.0', senderId: 'com.example.sender', dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY, /* ... */ };
    render(data: { text: string }) { return `<div>${data.text}</div>`; }
    async onMessage({ text }: { text: string }) { return { title: 'Minimal', body: text }; }
  }
  ```
- `build.sh` showing the esbuild invocation + sign step (host script)

## Tests

- `manifest.test.ts` — validator accepts valid manifests, rejects missing fields, rejects unknown capabilities
- `network.test.ts` — endpoint allowlist matching (exact, wildcard path, wildcard host segment)
- `storage.test.ts` — scope param is forwarded correctly
- `base-plugin.test.ts` — default `onMessage`/`onAction`/`onDismiss` return undefined; subclass overrides are called; dispatcher routes correctly; errors are caught and surfaced

Test framework: **Vitest** (lighter than Jest, native TS, fast).

## package.json

```json
{
  "name": "@syncler/plugin-sdk",
  "version": "0.1.0",
  "type": "module",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": { "import": "./dist/index.js", "types": "./dist/index.d.ts" }
  },
  "scripts": {
    "build": "tsc && esbuild src/index.ts --bundle --format=esm --platform=browser --outfile=dist/index.js",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "devDependencies": {
    "typescript": "^5.7.0",
    "esbuild": "^0.25.0",
    "vitest": "^3.0.0",
    "@types/node": "^22.0.0"
  }
}
```

## Constraints
- TypeScript strict mode
- ESM only (no CJS)
- No runtime deps (everything devDeps); the SDK code should run in a WebView with no Node polyfills
- No globals leaked beyond `__syncler_internal_dispatch` (everything else is module-scoped exports)
- All public APIs documented with JSDoc

## Print summary
- Files created
- Bundle size of `examples/minimal/dist/plugin.bundle.js` (run the build and report)
- How to run the test suite
