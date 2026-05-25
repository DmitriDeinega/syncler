import { describe, expect, it } from 'vitest';
import {
  HOST_PREVIEW_KEY,
  HOST_PREVIEW_LIMITS,
  HostPreviewValidationError,
  validateHostPreview,
} from '../src/preview';

const validPreview = {
  title: 'Lottery ticket entered',
  subtitle: 'Mega Millions — Ticket 8KQ-193',
  summary: '5 lines for the May 22 drawing.',
  searchText: ['mega millions', 'ticket 8KQ-193'],
};

describe('validateHostPreview', () => {
  it('accepts a valid block', () => {
    expect(() => validateHostPreview(validPreview)).not.toThrow();
  });

  it('is a no-op on null / undefined (host falls back)', () => {
    expect(() => validateHostPreview(null)).not.toThrow();
    expect(() => validateHostPreview(undefined)).not.toThrow();
  });

  it('rejects non-object payloads', () => {
    expect(() => validateHostPreview('hi')).toThrow(HostPreviewValidationError);
    expect(() => validateHostPreview(['hi'])).toThrow(
      HostPreviewValidationError
    );
  });

  it('requires title', () => {
    expect(() => validateHostPreview({ subtitle: 'x' })).toThrow(
      /title is required/
    );
    expect(() => validateHostPreview({ title: '' })).toThrow(
      /title is required/
    );
    expect(() => validateHostPreview({ title: '   ' })).toThrow(
      /title is required/
    );
  });

  it('enforces title byte cap', () => {
    const over = 'a'.repeat(HOST_PREVIEW_LIMITS.titleMaxBytes + 1);
    expect(() => validateHostPreview({ title: over })).toThrow(/title is/);
  });

  it('counts UTF-8 bytes, not characters (emoji bug check)', () => {
    // 21 emoji glyphs × 4 bytes each = 84 bytes; under char limit but over
    // byte limit at 80. This is the case that "chars" would miss.
    const emoji = '🎰'.repeat(21);
    expect(() => validateHostPreview({ title: emoji })).toThrow(/title is/);
  });

  it('enforces subtitle / summary byte caps', () => {
    expect(() =>
      validateHostPreview({
        title: 'ok',
        subtitle: 'a'.repeat(HOST_PREVIEW_LIMITS.subtitleMaxBytes + 1),
      })
    ).toThrow(/subtitle/);
    expect(() =>
      validateHostPreview({
        title: 'ok',
        summary: 'a'.repeat(HOST_PREVIEW_LIMITS.summaryMaxBytes + 1),
      })
    ).toThrow(/summary/);
  });

  it('rejects searchText that is not an array', () => {
    expect(() =>
      validateHostPreview({
        title: 'ok',
        searchText: 'a comma, separated, list',
      })
    ).toThrow(/searchText must be an array/);
  });

  it('enforces searchText entry count', () => {
    const tokens = Array.from(
      { length: HOST_PREVIEW_LIMITS.searchTextMaxEntries + 1 },
      (_, i) => `t${i}`
    );
    expect(() =>
      validateHostPreview({ title: 'ok', searchText: tokens })
    ).toThrow(/searchText has/);
  });

  it('enforces searchText entry byte cap', () => {
    const oneFatToken = 'a'.repeat(
      HOST_PREVIEW_LIMITS.searchTextEntryMaxBytes + 1
    );
    expect(() =>
      validateHostPreview({ title: 'ok', searchText: [oneFatToken] })
    ).toThrow(/searchText\[0\]/);
  });

  it('enforces total serialized size against bloat in unknown fields', () => {
    // The field caps alone sum to ~1.5KB plus JSON overhead, well under the
    // 2KB total. The total cap exists to catch unknown extension fields
    // that bypass the per-field validators — e.g. a sender adding
    // `"extra": "<10KB of debug info>"` to the block.
    const block = {
      title: 'ok',
      extra: 'x'.repeat(HOST_PREVIEW_LIMITS.totalMaxBytes + 100),
    };
    expect(() => validateHostPreview(block)).toThrow(/exceeds 2048 byte cap/);
  });
});

describe('HOST_PREVIEW_KEY', () => {
  it('is the reserved payload key', () => {
    expect(HOST_PREVIEW_KEY).toBe('hostPreview');
  });
});
