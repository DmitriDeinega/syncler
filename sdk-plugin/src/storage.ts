import { callPlatform, type StorageOptions } from './platform';

/**
 * Typed wrapper for `platform.storage`.
 */
export const storage = {
  /**
   * Reads a string value from host-managed storage.
   */
  get(key: string, opts?: StorageOptions): Promise<string | null> {
    return callPlatform('storage.get', (bridge) => bridge.storage.get(key, opts));
  },

  /**
   * Writes a string value to host-managed storage.
   */
  set(key: string, value: string, opts?: StorageOptions): Promise<void> {
    return callPlatform('storage.set', (bridge) => bridge.storage.set(key, value, opts));
  },

  /**
   * Deletes a value from host-managed storage.
   */
  delete(key: string, opts?: StorageOptions): Promise<void> {
    return callPlatform('storage.delete', (bridge) => bridge.storage.delete(key, opts));
  },
};
