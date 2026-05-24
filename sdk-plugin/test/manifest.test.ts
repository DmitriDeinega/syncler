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
    expect(validatePluginManifest(validManifest)).toEqual({
      valid: true,
      manifest: validManifest,
    });
  });

  it('rejects missing fields', () => {
    const result = validatePluginManifest({
      ...validManifest,
      name: undefined,
    });
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
    const result = validatePluginManifest({
      ...validManifest,
      bundleHash: 'abcdef',
    });
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues.some((i) => i.includes('64 hex chars'))).toBe(true);
    }
  });

  it('rejects short signature (Phase 4.1: must be 128 hex chars / Ed25519)', () => {
    const result = validatePluginManifest({
      ...validManifest,
      signature: 'abcdef',
    });
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
      { allowUnsignedPlaceholders: true }
    );
    expect(result.valid).toBe(true);
  });

  it('still rejects empty bundleHash when allowUnsignedPlaceholders is true', () => {
    const result = validatePluginManifest(
      { ...validManifest, bundleHash: '' },
      { allowUnsignedPlaceholders: true }
    );
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('bundleHash must be a non-empty string');
    }
  });

  it('still rejects unknown capabilities when allowUnsignedPlaceholders is true', () => {
    const result = validatePluginManifest(
      { ...validManifest, declaredCapabilities: ['telepathy'] },
      { allowUnsignedPlaceholders: true }
    );
    expect(result.valid).toBe(false);
    if (!result.valid) {
      expect(result.issues).toContain('unknown capability: telepathy');
    }
  });

  // Phase 5d: validator polish batch.
  describe('Phase 5d polish', () => {
    it('rejects minPlatformVersion components longer than 6 digits', () => {
      const result = validatePluginManifest({
        ...validManifest,
        minPlatformVersion: '1.0.1234567',
      });
      expect(result.valid).toBe(false);
      if (!result.valid)
        expect(result.issues).toContain('minPlatformVersion must be semver');
    });

    it('accepts minPlatformVersion at the 6-digit cap', () => {
      const result = validatePluginManifest({
        ...validManifest,
        minPlatformVersion: '999999.999999.999999',
      });
      expect(result.valid).toBe(true);
    });

    it('rejects cardKeyPath with array indexing (must be $.field(.subfield)*)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        cardType: 'live',
        cardKeyPath: '$.items[0].id',
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(
          result.issues.some((i) => i.includes('$.field(.subfield)'))
        ).toBe(true);
      }
    });

    it('accepts cardKeyPath with nested dotted access', () => {
      const result = validatePluginManifest({
        ...validManifest,
        cardType: 'live',
        cardKeyPath: '$.order.id',
      });
      expect(result.valid).toBe(true);
    });

    it('rejects template.fields key not in the layout allowed set', () => {
      const result = validatePluginManifest({
        ...validManifest,
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: {
            title: { path: '$.title' },
            // `headline` is not in standard_card's allowed set
            // ({title, subtitle, body}) — server would 422; SDK
            // catches locally.
            headline: { path: '$.headline' },
          },
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(
          result.issues.some((i) => i.includes('template.fields.headline'))
        ).toBe(true);
      }
    });

    it('accepts a fully valid standard_card template block', () => {
      const result = validatePluginManifest({
        ...validManifest,
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: {
            title: { path: '$.title' },
            subtitle: { path: '$.subtitle' },
            body: { path: '$.body' },
          },
        },
      });
      expect(result.valid).toBe(true);
    });

    it('rejects template action with cleartext public-host endpoint', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: [
          'http://api.example.com/*',
          'https://api.example.com/*',
        ],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            { id: 'ack', label: 'Ack', endpoint: 'http://api.example.com/ack' },
          ],
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.issues.some((i) => i.includes('must be HTTPS'))).toBe(
          true
        );
      }
    });

    it('accepts template action with LAN HTTP endpoint', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://192.168.1.10:8001/api/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'http://192.168.1.10:8001/api/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(true);
    });

    // Codex consultation 80 RED #2a: HTTPS with userinfo accepted by
    // the previous string-slicing parser.
    it('rejects template action with HTTPS userinfo (user:pass@host)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'https://user:pass@api.example.com/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.issues.some((i) => i.includes('must be HTTPS'))).toBe(true);
      }
    });

    // Codex consultation 80 RED #2a: HTTP with userinfo also rejected
    // (previously had a guard but the URL parser approach unifies both).
    it('rejects template action with HTTP userinfo (user:pass@host)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://10.0.0.1/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'http://user:pass@10.0.0.1/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 82 RED: WHATWG treats empty userinfo as
    // no userinfo; urlparse doesn't. WHATWG normalizes backslash
    // schemes; urlparse doesn't. Strict raw-URL regex eliminates
    // both mismatches.
    it('rejects empty userinfo (http://@10.0.0.1/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*.*.*.*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'http://@10.0.0.1/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    it('rejects empty :@ userinfo (http://:@10.0.0.1/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*.*.*.*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'http://:@10.0.0.1/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    it('rejects HTTPS with empty userinfo (https://@api.example.com/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https://@api.example.com/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 84 RED: Python `re.IGNORECASE` does Unicode
    // case folding; JS `/i` is ASCII-only without `/u`. Server now
    // uses `re.IGNORECASE | re.ASCII` to align.
    it('rejects URL with Unicode-fold scheme (httpſ://localhost/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://localhost/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'httpſ://localhost/x' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 84 RED: Python `\d` matches Unicode digits;
    // JS `\d` is ASCII-only. Server now uses `[0-9]` explicitly.
    it('rejects URL with Unicode digit port (https://api.example:١٢/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https://api.example:١٢/x' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 84 RED: Python `.` matches `\r`; JS `.`
    // doesn't (treats `\r` as a line terminator). Both sides now
    // restrict path/query/fragment to printable ASCII [0x21-0x7E].
    it('rejects URL with trailing carriage return (https://api.example/x\\r)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https://api.example/x\r' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 83 RED: Python `$` in `re.match` matches
    // before a trailing newline; JS regex `$` is end-of-string. Server
    // uses `re.fullmatch` to align. Test both sides reject.
    it('rejects URL with trailing newline (Python re.match vs JS regex parity)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https://api.example.com/ack\n' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 83 host-class tightening: tab/space/IDN
    // would have been accepted by the previous broad host class
    // `[^\\@/?#:]+`. The tightened ASCII-alnum class rejects them.
    it('rejects URL with tab in host (https://api\\texample.com/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https://api\texample.com/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    it('rejects backslash-scheme URL (https:\\api.example.com/x)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://*.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'https:\\api.example.com/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 81 RED: WHATWG URL normalizes legacy IPv4
    // forms; Python urlparse doesn't. We extract from raw URL + reject
    // leading-zero octets so SDK and server agree on canonical IPv4.
    it('rejects legacy IPv4 octal form (0177.0.0.1)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*.*.*.*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'http://0177.0.0.1/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    it('rejects legacy IPv4 compressed form (127.1)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'http://127.1/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    it('rejects leading-zero IPv4 octet (010.0.0.1) which urlparse accepts but URL normalizes', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*.*.*.*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [{ id: 'ack', label: 'Ack', endpoint: 'http://010.0.0.1/ack' }],
        },
      });
      expect(result.valid).toBe(false);
    });

    // Codex consultation 80 RED #2b: previously the IPv4 LAN check only
    // validated first two octets, so `10.999.999.999` passed.
    it('rejects template action with invalid IPv4 octets (10.999.999.999)', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['http://*.*.*.*/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'http://10.999.999.999/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.issues.some((i) => i.includes('must be HTTPS'))).toBe(true);
      }
    });

    it('accepts template action with HTTPS endpoint', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://api.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'https://api.example.com/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(true);
    });

    it('rejects duplicate template action ids', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://api.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'https://api.example.com/ack',
            },
            {
              id: 'ack',
              label: 'Ack 2',
              endpoint: 'https://api.example.com/ack2',
            },
          ],
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(result.issues.some((i) => i.includes('duplicated'))).toBe(true);
      }
    });

    it('rejects template action endpoint not covered by declaredEndpoints', () => {
      const result = validatePluginManifest({
        ...validManifest,
        declaredEndpoints: ['https://api.example.com/*'],
        renderer: 'template',
        template: {
          layout: 'standard_card',
          fields: { title: { path: '$.title' } },
          actions: [
            {
              id: 'ack',
              label: 'Ack',
              endpoint: 'https://other.example.com/ack',
            },
          ],
        },
      });
      expect(result.valid).toBe(false);
      if (!result.valid) {
        expect(
          result.issues.some((i) =>
            i.includes('not covered by declaredEndpoints')
          )
        ).toBe(true);
      }
    });
  });
});
