/**
 * HostPreview — structured metadata the Syncler host renders natively as an
 * inbox row, without invoking the plugin's `render()`. The plugin still owns
 * the detail-view render (full-screen WebView) when the user taps a card.
 *
 * Senders embed this under the reserved `hostPreview` key of the encrypted
 * message payload:
 *
 * ```ts
 * client.send_to({
 *   payload: {
 *     hostPreview: {
 *       title: 'Lottery ticket entered',
 *       subtitle: 'Mega Millions — Ticket 8KQ-193',
 *       summary: '5 lines for the May 22 drawing.',
 *       searchText: ['mega millions', 'ticket 8KQ-193'],
 *     },
 *     // plugin-defined fields below
 *     draw_id: '8823-abc',
 *     numbers: [4, 12, 29, 33, 41],
 *   },
 * });
 * ```
 *
 * Missing or invalid `hostPreview` causes the host to render a fallback
 * "New message from {sender_name}" row. Plugins should always include it.
 */
export interface HostPreview {
  /** Required. Primary inbox row text. Max 80 UTF-8 bytes after trimming. */
  title: string;
  /** Optional. Secondary row text. Max 120 UTF-8 bytes. */
  subtitle?: string;
  /** Optional. One compact sentence shown when the row has room. Max 240 UTF-8 bytes. */
  summary?: string;
  /**
   * Optional. Extra terms folded into the host's global search index, in
   * addition to title/subtitle/summary. Max 16 entries × 64 UTF-8 bytes each.
   */
  searchText?: string[];
}

export const HOST_PREVIEW_KEY = 'hostPreview' as const;

export const HOST_PREVIEW_LIMITS = {
  titleMaxBytes: 80,
  subtitleMaxBytes: 120,
  summaryMaxBytes: 240,
  searchTextMaxEntries: 16,
  searchTextEntryMaxBytes: 64,
  totalMaxBytes: 2048,
} as const;

/**
 * Error thrown by `validateHostPreview` when the block violates the contract.
 */
export class HostPreviewValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'HostPreviewValidationError';
  }
}

/**
 * Validates a candidate hostPreview block. Throws on the first violation with
 * an explanatory message; returns silently when ``value`` is null/undefined.
 * Plugin authors building messages client-side can call this before passing
 * the payload into their sender backend.
 */
export function validateHostPreview(value: unknown): void {
  if (value === null || value === undefined) return;
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw new HostPreviewValidationError('hostPreview must be a JSON object');
  }

  const record = value as Record<string, unknown>;

  if (typeof record.title !== 'string' || record.title.trim().length === 0) {
    throw new HostPreviewValidationError(
      'hostPreview.title is required and must be a non-empty string'
    );
  }
  checkBytes('title', record.title, HOST_PREVIEW_LIMITS.titleMaxBytes);

  if ('subtitle' in record) {
    if (typeof record.subtitle !== 'string') {
      throw new HostPreviewValidationError(
        'hostPreview.subtitle must be a string'
      );
    }
    checkBytes(
      'subtitle',
      record.subtitle,
      HOST_PREVIEW_LIMITS.subtitleMaxBytes
    );
  }

  if ('summary' in record) {
    if (typeof record.summary !== 'string') {
      throw new HostPreviewValidationError(
        'hostPreview.summary must be a string'
      );
    }
    checkBytes('summary', record.summary, HOST_PREVIEW_LIMITS.summaryMaxBytes);
  }

  if ('searchText' in record) {
    const tokens = record.searchText;
    if (!Array.isArray(tokens)) {
      throw new HostPreviewValidationError(
        'hostPreview.searchText must be an array of strings'
      );
    }
    if (tokens.length > HOST_PREVIEW_LIMITS.searchTextMaxEntries) {
      throw new HostPreviewValidationError(
        `hostPreview.searchText has ${tokens.length} entries; max is ${HOST_PREVIEW_LIMITS.searchTextMaxEntries}`
      );
    }
    tokens.forEach((token, i) => {
      if (typeof token !== 'string') {
        throw new HostPreviewValidationError(
          `hostPreview.searchText[${i}] must be a string`
        );
      }
      checkBytes(
        `searchText[${i}]`,
        token,
        HOST_PREVIEW_LIMITS.searchTextEntryMaxBytes
      );
    });
  }

  const serialized = textEncoder.encode(JSON.stringify(record));
  if (serialized.byteLength > HOST_PREVIEW_LIMITS.totalMaxBytes) {
    throw new HostPreviewValidationError(
      `hostPreview serialized size ${serialized.byteLength} bytes exceeds ${HOST_PREVIEW_LIMITS.totalMaxBytes} byte cap`
    );
  }
}

const textEncoder = new TextEncoder();

function checkBytes(field: string, value: string, maxBytes: number): void {
  const size = textEncoder.encode(value).byteLength;
  if (size > maxBytes) {
    throw new HostPreviewValidationError(
      `hostPreview.${field} is ${size} UTF-8 bytes; max is ${maxBytes}`
    );
  }
}
