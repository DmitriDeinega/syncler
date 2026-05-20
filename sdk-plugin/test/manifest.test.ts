import { describe, expect, it } from 'vitest';
import { Capability, DismissBehavior } from '../src/enums';
import { validatePluginManifest } from '../src/manifest';

const validManifest = {
  id: 'com.example.plugin',
  name: 'Example',
  version: '1.0.0',
  senderId: 'com.example.sender',
  bundleHash: 'abcdef123456',
  signature: '012345abcdef',
  declaredCapabilities: [Capability.NETWORK, Capability.STORAGE],
  declaredEndpoints: ['https://api.example.com/*'],
  dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
  minPlatformVersion: '1.0.0',
};

describe('validatePluginManifest', () => {
  it('accepts valid manifests', () => {
    expect(validatePluginManifest(validManifest)).toEqual({ valid: true, manifest: validManifest });
  });

  it('rejects missing fields', () => {
    const result = validatePluginManifest({ ...validManifest, name: undefined });
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('name must be a non-empty string');
    }
  });

  it('rejects unknown capabilities', () => {
    const result = validatePluginManifest({
      ...validManifest,
      declaredCapabilities: ['network', 'telepathy'],
    });
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('unknown capability: telepathy');
    }
  });
});
