import { BasePlugin, DismissBehavior, Capability, registerPlugin } from '@syncler/plugin-sdk';

// Reference plugin demonstrating the script renderer path end-to-end.
//
// `bundleHash` and `signature` below are placeholders. The in-bundle
// source manifest can't know its own bundle hash or signature: those are
// produced by `tools/sign-bundle.ts` post-build and written to
// `manifest.signed.json` on disk (NOT back into this JS source). The
// runtime `registerPlugin` call accepts placeholders because the host
// already validated the bundle against the authoritative server-stored
// signed manifest at publish time. Publish-side tooling
// (sign-bundle.ts, server, SDK `publish_plugin`) keeps the strict
// 64/128-hex check. See `sdk-plugin/README.md` and
// `docs/integration-guide.md §4`.
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
