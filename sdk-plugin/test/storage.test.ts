import { afterEach, describe, expect, it, vi } from 'vitest';
import { storage } from '../src/storage';
import type { PlatformBridge } from '../src/platform';

describe('storage wrapper', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    delete globalThis.platform;
  });

  it('forwards scope on get', async () => {
    const get = vi.fn<PlatformBridge['storage']['get']>().mockResolvedValue('value');
    globalThis.platform = createPlatform({ get });

    await expect(storage.get('key', { scope: 'user' })).resolves.toBe('value');
    expect(get).toHaveBeenCalledWith('key', { scope: 'user' });
  });

  it('forwards scope on set and delete', async () => {
    const set = vi.fn<PlatformBridge['storage']['set']>().mockResolvedValue(undefined);
    const del = vi.fn<PlatformBridge['storage']['delete']>().mockResolvedValue(undefined);
    globalThis.platform = createPlatform({ set, delete: del });

    await storage.set('key', 'value', { scope: 'device' });
    await storage.delete('key', { scope: 'device' });

    expect(set).toHaveBeenCalledWith('key', 'value', { scope: 'device' });
    expect(del).toHaveBeenCalledWith('key', { scope: 'device' });
  });
});

function createPlatform(storageOverrides: Partial<PlatformBridge['storage']>): PlatformBridge {
  return {
    showNotification: vi.fn(),
    storage: {
      get: vi.fn(),
      set: vi.fn(),
      delete: vi.fn(),
      ...storageOverrides,
    },
    network: { fetch: vi.fn() },
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
