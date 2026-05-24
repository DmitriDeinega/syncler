import { BasePlugin, DismissBehavior, Capability, registerPlugin } from '@syncler/plugin-sdk';

// Reference plugin demonstrating the script renderer path end-to-end.
//
// `bundleHash` and `signature` below are placeholders meant to be filled
// in by `tools/sign-bundle.ts` after `build.sh` produces `dist/plugin.bundle.js`.
// The two-step flow is intentional: you can't compute `bundleHash` until
// the bundle exists, and you can't sign without the bundle hash. See
// `sdk-plugin/README.md` and `docs/integration-guide.md §4` for the full
// build → sign → publish cycle.
//
// The SDK's `validatePluginManifest` rejects this manifest as-written
// (post Phase 4.1) because both fields fail the 64/128 hex length check —
// that's the whole point of validation: the unsigned manifest cannot be
// published. `sign-bundle.ts` reads this file, computes SHA-256 over the
// built bundle, signs `(canonical manifest || bundleHash)` with Ed25519,
// and writes a NEW file `manifest.signed.json` with `bundleHash` and
// `signature` populated. The signed file is what you ship to the server.
// `manifest.json` stays as the human-edited source-of-truth.
export class MinimalPlugin extends BasePlugin {
  static manifest = {
    id: 'com.example.minimal',
    name: 'Minimal',
    version: '1.0.0',
    senderId: 'com.example.sender',
    bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    declaredCapabilities: [Capability.STORAGE],
    declaredEndpoints: [],
    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  };

  render(data: { text: string }): string {
    return `<div>${escapeHtml(data.text)}</div>`;
  }

  async onMessage({ text }: { text: string }) {
    return { title: 'Minimal', body: text };
  }
}

registerPlugin(new MinimalPlugin());

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    switch (character) {
      case '&':
        return '&amp;';
      case '<':
        return '&lt;';
      case '>':
        return '&gt;';
      case '"':
        return '&quot;';
      case "'":
        return '&#39;';
      default:
        return character;
    }
  });
}
