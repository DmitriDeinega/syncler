import { BasePlugin, DismissBehavior, Capability, registerPlugin } from '@syncler/plugin-sdk';

export class MinimalPlugin extends BasePlugin {
  static manifest = {
    id: 'com.example.minimal',
    name: 'Minimal',
    version: '1.0.0',
    senderId: 'com.example.sender',
    bundleHash: '00',
    signature: '00',
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
