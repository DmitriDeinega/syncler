import { describe, expect, it } from 'vitest';
import { Capability, DismissBehavior } from '../src/enums';
import { validatePluginManifest } from '../src/manifest';

// 64 lowercase hex chars = SHA-256 length the validator requires for bundleHash.
const SAMPLE_BUNDLE_HASH = 'a'.repeat(64);
// 128 hex chars = Ed25519 signature length.
const SAMPLE_SIGNATURE = 'b'.repeat(128);

const validManifest = {
  id: 'com.example.plugin',
  name: 'Example',
  version: '1.0.0',
  senderId: 'com.example.sender',
  bundleHash: SAMPLE_BUNDLE_HASH,
  signature: SAMPLE_SIGNATURE,
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

  it('rejects short bundleHash (Phase 4.1: must be 64 hex chars / SHA-256)', () => {
    const result = validatePluginManifest({ ...validManifest, bundleHash: 'abcdef' });
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues.some((i) => i.includes('64 hex chars'))).toBe(true);
    }
  });

  it('rejects short signature (Phase 4.1: must be 128 hex chars / Ed25519)', () => {
    const result = validatePluginManifest({ ...validManifest, signature: 'abcdef' });
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues.some((i) => i.includes('128 hex chars'))).toBe(true);
    }
  });

  it('rejects the unsigned placeholder string (Phase 4.1: a published manifest must be signed)', () => {
    const result = validatePluginManifest({
      ...validManifest,
      bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    });
    expect(result.valid).toBe(false);
  });

  it('accepts placeholder bundleHash/signature when allowUnsignedPlaceholders is true (Phase 5b: runtime path)', () => {
    const result = validatePluginManifest(
      {
        ...validManifest,
        bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
        signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
      },
      { allowUnsignedPlaceholders: true },
    );
    expect(result.valid).toBe(true);
  });

  it('still rejects empty bundleHash when allowUnsignedPlaceholders is true', () => {
    const result = validatePluginManifest(
      { ...validManifest, bundleHash: '' },
      { allowUnsignedPlaceholders: true },
    );
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('bundleHash must be a non-empty string');
    }
  });

  it('still rejects unknown capabilities when allowUnsignedPlaceholders is true', () => {
    const result = validatePluginManifest(
      { ...validManifest, declaredCapabilities: ['telepathy'] },
      { allowUnsignedPlaceholders: true },
    );
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('unknown capability: telepathy');
    }
  });
});
