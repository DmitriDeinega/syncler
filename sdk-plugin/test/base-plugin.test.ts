import { afterEach, describe, expect, it } from 'vitest';
import { BasePlugin, type DismissAction } from '../src/base-plugin';
import {
  clearRegisteredPlugin,
  dispatchPluginHook,
  registerPlugin,
} from '../src/bridge';
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

  async getNotification(event: import('../src/base-plugin').NotificationEvent) {
    if (event.kind === 'message') {
      const payload = event.payload as { text: string };
      return { title: 'Title', body: payload.text };
    }
    return undefined;
  }

  async onUserAction(actionName: string, payload: { id: string }): Promise<void> {
    await Promise.resolve(`${actionName}:${payload.id}`);
  }

  async onDismiss(deviceId: string): Promise<DismissAction> {
    return {
      behavior: DismissBehavior.DISMISS_LOCAL_ONLY,
      payload: { deviceId },
    };
  }
}

class ThrowingPlugin extends BasePlugin {
  static manifest = manifestFor('com.example.throwing');

  render(): string {
    return '<div>throwing</div>';
  }

  async getNotification(): Promise<void> {
    throw new Error('boom');
  }

  async onUserAction(): Promise<void> {
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
    await expect(
      plugin.getNotification({
        kind: 'message',
        cardType: 'event',
        payload: {},
      })
    ).resolves.toBeUndefined();
    await expect(plugin.onUserAction('open', {})).resolves.toBeUndefined();
    await expect(plugin.onDismiss('device')).resolves.toBeUndefined();
    // Triad 158 — default live hooks are also no-ops so a plugin
    // that doesn't use the live channel doesn't need to override.
    await expect(plugin.onLiveMessage('ch', 'b64')).resolves.toBeUndefined();
    await expect(plugin.onLiveError('ch', 'rate_limit_exceeded')).resolves.toBeUndefined();
    await expect(plugin.onLiveClosed('ch')).resolves.toBeUndefined();
  });

  it('subclass overrides are called', async () => {
    const plugin = new OverridesPlugin();
    await expect(
      plugin.getNotification({
        kind: 'message',
        cardType: 'event',
        payload: { text: 'hello' },
      })
    ).resolves.toEqual({
      title: 'Title',
      body: 'hello',
    });
    await expect(plugin.onUserAction('open', { id: '1' })).resolves.toBeUndefined();
    await expect(plugin.onDismiss('device-1')).resolves.toEqual({
      behavior: DismissBehavior.DISMISS_LOCAL_ONLY,
      payload: { deviceId: 'device-1' },
    });
  });

  it('dispatcher routes to registered hooks', async () => {
    registerPlugin(new OverridesPlugin());

    await expect(
      dispatchPluginHook('getNotification', [
        { kind: 'message', cardType: 'event', payload: { text: 'hello' } },
      ])
    ).resolves.toEqual({ title: 'Title', body: 'hello' });
    await expect(
      dispatchPluginHook('onUserAction', ['open', { id: '1' }])
    ).resolves.toBeUndefined();
    await expect(
      dispatchPluginHook('onDismiss', ['device-1'])
    ).resolves.toEqual({
      behavior: DismissBehavior.DISMISS_LOCAL_ONLY,
      payload: { deviceId: 'device-1' },
    });
  });

  it('dispatcher propagates hook errors as rejected promises', async () => {
    registerPlugin(new ThrowingPlugin());

    await expect(
      dispatchPluginHook('getNotification', [
        { kind: 'message', cardType: 'event', payload: {} },
      ])
    ).rejects.toThrow('boom');
    await expect(dispatchPluginHook('onUserAction', ['open', {}])).rejects.toThrow(
      'action boom'
    );
    await expect(dispatchPluginHook('onDismiss', ['device-1'])).rejects.toThrow(
      'dismiss boom'
    );
  });

  it('dispatcher parses live-channel JSON payload into positional args', async () => {
    // Triad 158 bug 3 FIX — Android dispatches the live frame
    // as a single JSON-string positional arg containing
    // {channel, envelope}. The bridge parses + fans out as
    // (channel, envelope) to the plugin's hook.
    const received: Array<[string, string]> = [];
    class LiveSubscriberPlugin extends BasePlugin {
      static manifest = manifestFor('com.example.live-sub');
      render(): string {
        return '<div>live</div>';
      }
      async onLiveMessage(channel: string, envelope: string): Promise<void> {
        received.push([channel, envelope]);
      }
    }
    registerPlugin(new LiveSubscriberPlugin());

    await dispatchPluginHook('onLiveMessage', [
      JSON.stringify({ channel: 'ticker', envelope: 'b64-of-envelope' }),
    ]);
    expect(received).toEqual([['ticker', 'b64-of-envelope']]);
  });

  it('dispatcher rejects malformed live-channel payloads', async () => {
    class LivePlugin extends BasePlugin {
      static manifest = manifestFor('com.example.live-bad');
      render(): string {
        return '';
      }
    }
    registerPlugin(new LivePlugin());

    // Non-JSON string → TypeError.
    await expect(
      dispatchPluginHook('onLiveMessage', ['not-json'])
    ).rejects.toThrow(/not valid JSON/);

    // Valid JSON but missing the required string fields →
    // asString TypeError downstream.
    await expect(
      dispatchPluginHook('onLiveMessage', [JSON.stringify({ channel: 42 })])
    ).rejects.toThrow(/must be a string/);
  });

  it('dispatcher routes onLiveError + onLiveClosed', async () => {
    const events: Array<string> = [];
    class LiveLifecyclePlugin extends BasePlugin {
      static manifest = manifestFor('com.example.live-lc');
      render(): string {
        return '';
      }
      async onLiveError(channel: string, code: string): Promise<void> {
        events.push(`error:${channel}:${code}`);
      }
      async onLiveClosed(channel: string): Promise<void> {
        events.push(`closed:${channel}`);
      }
    }
    registerPlugin(new LiveLifecyclePlugin());

    await dispatchPluginHook('onLiveError', [
      JSON.stringify({ channel: 'ticker', code: 'rate_limit_exceeded' }),
    ]);
    await dispatchPluginHook('onLiveClosed', [
      JSON.stringify({ channel: 'ticker' }),
    ]);
    expect(events).toEqual([
      'error:ticker:rate_limit_exceeded',
      'closed:ticker',
    ]);
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
