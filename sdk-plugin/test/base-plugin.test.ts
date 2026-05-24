import { afterEach, describe, expect, it } from 'vitest';
import { BasePlugin, type DismissAction } from '../src/base-plugin';
import { clearRegisteredPlugin, dispatchPluginHook, registerPlugin } from '../src/bridge';
import { Capability, DismissBehavior } from '../src/enums';

class DefaultsPlugin extends BasePlugin {
  static manifest = manifestFor('com.example.defaults');

  render(): string {
    return '<div>defaults</div>';
  }
}

class OverridesPlugin extends BasePlugin {
  static manifest = manifestFor('com.example.overrides');

  render(data: { text: string }): string {
    return `<div>${data.text}</div>`;
  }

  async onMessage(payload: { text: string }) {
    return { title: 'Title', body: payload.text };
  }

  async onAction(actionName: string, payload: { id: string }): Promise<void> {
    await Promise.resolve(`${actionName}:${payload.id}`);
  }

  async onDismiss(deviceId: string): Promise<DismissAction> {
    return { behavior: DismissBehavior.DISMISS_LOCAL_ONLY, payload: { deviceId } };
  }
}

class ThrowingPlugin extends BasePlugin {
  static manifest = manifestFor('com.example.throwing');

  render(): string {
    return '<div>throwing</div>';
  }

  async onMessage(): Promise<void> {
    throw new Error('boom');
  }

  async onAction(): Promise<void> {
    throw new Error('action boom');
  }

  async onDismiss(): Promise<DismissAction> {
    throw new Error('dismiss boom');
  }
}

describe('BasePlugin', () => {
  afterEach(() => {
    clearRegisteredPlugin();
  });

  it('default hooks return undefined', async () => {
    const plugin = new DefaultsPlugin();
    await expect(plugin.onMessage({})).resolves.toBeUndefined();
    await expect(plugin.onAction('open', {})).resolves.toBeUndefined();
    await expect(plugin.onDismiss('device')).resolves.toBeUndefined();
  });

  it('subclass overrides are called', async () => {
    const plugin = new OverridesPlugin();
    await expect(plugin.onMessage({ text: 'hello' })).resolves.toEqual({ title: 'Title', body: 'hello' });
    await expect(plugin.onAction('open', { id: '1' })).resolves.toBeUndefined();
    await expect(plugin.onDismiss('device-1')).resolves.toEqual({
      behavior: DismissBehavior.DISMISS_LOCAL_ONLY,
      payload: { deviceId: 'device-1' },
    });
  });

  it('dispatcher routes to registered hooks', async () => {
    registerPlugin(new OverridesPlugin());

    await expect(dispatchPluginHook('onMessage', [{ text: 'hello' }])).resolves.toEqual({ title: 'Title', body: 'hello' });
    await expect(dispatchPluginHook('onAction', ['open', { id: '1' }])).resolves.toBeUndefined();
    await expect(dispatchPluginHook('onDismiss', ['device-1'])).resolves.toEqual({
      behavior: DismissBehavior.DISMISS_LOCAL_ONLY,
      payload: { deviceId: 'device-1' },
    });
  });

  it('dispatcher propagates hook errors as rejected promises', async () => {
    registerPlugin(new ThrowingPlugin());

    await expect(dispatchPluginHook('onMessage', [{}])).rejects.toThrow('boom');
    await expect(dispatchPluginHook('onAction', ['open', {}])).rejects.toThrow('action boom');
    await expect(dispatchPluginHook('onDismiss', ['device-1'])).rejects.toThrow('dismiss boom');
  });

  it('registerPlugin accepts the unsigned placeholder hash/signature (Phase 5b)', () => {
    class PlaceholderPlugin extends BasePlugin {
      static manifest = {
        id: 'com.example.placeholder',
        name: 'Placeholder',
        version: '1.0.0',
        senderId: 'com.example.sender',
        // The in-bundle source manifest can't know the bundle's own hash /
        // signature. sign-bundle.ts produces them post-build into
        // manifest.signed.json on disk; the JS source keeps the placeholders.
        bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
        signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
        declaredCapabilities: [Capability.STORAGE],
        declaredEndpoints: [],
        dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
        minPlatformVersion: '1.0.0',
      };

      render(): string {
        return '<div>placeholder</div>';
      }
    }

    expect(() => registerPlugin(new PlaceholderPlugin())).not.toThrow();
  });
});

function manifestFor(id: string) {
  return {
    id,
    name: 'Test',
    version: '1.0.0',
    senderId: 'com.example.sender',
    // Phase 4.1: validator requires SHA-256 length bundleHash (64 hex)
    // and Ed25519 signature length (128 hex). Use deterministic test
    // values rather than real signatures since the validator only checks
    // shape, not cryptographic validity.
    bundleHash: 'a'.repeat(64),
    signature: 'b'.repeat(128),
    declaredCapabilities: [Capability.STORAGE],
    declaredEndpoints: [],
    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  };
}
