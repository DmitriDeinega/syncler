import { afterEach, describe, expect, it, vi } from 'vitest';
import { BasePlugin } from '../src/base-plugin';
import { registerPlugin, clearRegisteredPlugin } from '../src/bridge';
import { Capability, DismissBehavior } from '../src/enums';
import { EndpointNotDeclaredError, isEndpointDeclared, network } from '../src/network';
import type { PlatformBridge } from '../src/platform';

class NetworkPlugin extends BasePlugin {
  static manifest = {
    id: 'com.example.network',
    name: 'Network',
    version: '1.0.0',
    senderId: 'com.example.sender',
    bundleHash: 'abc123',
    signature: 'def456',
    declaredCapabilities: [Capability.NETWORK],
    declaredEndpoints: ['https://api.example.com/v1/item', 'https://api.example.com/v1/*', 'https://*.example.net/feed'],
    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  };

  render(): string {
    return '<div></div>';
  }
}

describe('network endpoint allowlist', () => {
  afterEach(() => {
    clearRegisteredPlugin();
    vi.restoreAllMocks();
    delete globalThis.platform;
  });

  it('matches exact endpoints', () => {
    expect(isEndpointDeclared('https://api.example.com/v1/item', ['https://api.example.com/v1/item'])).toBe(true);
    expect(isEndpointDeclared('https://api.example.com/v1/other', ['https://api.example.com/v1/item'])).toBe(false);
  });

  it('matches wildcard paths', () => {
    expect(isEndpointDeclared('https://api.example.com/v1/users', ['https://api.example.com/v1/*'])).toBe(true);
    expect(isEndpointDeclared('https://api.example.com/v1/users/1', ['https://api.example.com/v1/*'])).toBe(false);
  });

  it('matches wildcard host segments', () => {
    expect(isEndpointDeclared('https://img.example.net/feed', ['https://*.example.net/feed'])).toBe(true);
    expect(isEndpointDeclared('https://a.b.example.net/feed', ['https://*.example.net/feed'])).toBe(false);
  });

  it('throws before bridge fetch when endpoint is undeclared', async () => {
    const fetch = vi.fn<PlatformBridge['network']['fetch']>();
    globalThis.platform = createPlatform(fetch);
    registerPlugin(new NetworkPlugin());

    await expect(network.fetch('https://evil.example.com/')).rejects.toBeInstanceOf(EndpointNotDeclaredError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('calls bridge fetch for declared endpoints', async () => {
    const response = new Response('ok');
    const fetch = vi.fn<PlatformBridge['network']['fetch']>().mockResolvedValue(response);
    globalThis.platform = createPlatform(fetch);
    registerPlugin(new NetworkPlugin());

    await expect(network.fetch('https://api.example.com/v1/users')).resolves.toBe(response);
    expect(fetch).toHaveBeenCalledWith('https://api.example.com/v1/users', undefined);
  });
});

function createPlatform(fetch: PlatformBridge['network']['fetch']): PlatformBridge {
  return {
    showNotification: vi.fn(),
    storage: {
      get: vi.fn(),
      set: vi.fn(),
      delete: vi.fn(),
    },
    network: { fetch },
    camera: { capture: vi.fn() },
    gallery: { pick: vi.fn() },
    file: { pick: vi.fn() },
    location: { current: vi.fn() },
    message: {
      respond: vi.fn(),
      dismissBehavior: vi.fn(),
    },
    __version__: '1.0.0',
  };
}
