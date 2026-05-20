import { getRegisteredManifest } from './manifest';
import { callPlatform } from './platform';

/**
 * Error raised when a URL does not match the plugin manifest endpoint allowlist.
 */
export class EndpointNotDeclaredError extends Error {
  /** URL rejected before reaching the native bridge. */
  readonly url: string;

  constructor(url: string) {
    super(`Endpoint is not declared by plugin manifest: ${url}`);
    this.name = 'EndpointNotDeclaredError';
    this.url = url;
  }
}

/**
 * Typed wrapper for `platform.network`.
 */
export const network = {
  /**
   * Fetches a URL through the host bridge after checking the manifest endpoint allowlist.
   */
  async fetch(url: string, init?: RequestInit): Promise<Response> {
    assertEndpointDeclared(url);
    return await callPlatform('network.fetch', (bridge) => bridge.network.fetch(url, init));
  },
};

/**
 * Returns true when a URL matches at least one declared endpoint pattern.
 */
export function isEndpointDeclared(url: string, patterns: readonly string[]): boolean {
  return patterns.some((pattern) => endpointPatternToRegExp(pattern).test(url));
}

/**
 * Throws `EndpointNotDeclaredError` when the active plugin manifest does not declare a URL.
 */
export function assertEndpointDeclared(url: string): void {
  const manifest = getRegisteredManifest();
  if (!manifest || !isEndpointDeclared(url, manifest.declaredEndpoints)) {
    throw new EndpointNotDeclaredError(url);
  }
}

function endpointPatternToRegExp(pattern: string): RegExp {
  const pathStart = findPathStart(pattern);
  let source = '';

  for (let index = 0; index < pattern.length; index += 1) {
    const character = pattern[index];
    if (character === '*') {
      source += index < pathStart ? '[^./]*' : '[^/]*';
    } else {
      source += escapeRegExp(character);
    }
  }

  return new RegExp(`^${source}$`);
}

function findPathStart(pattern: string): number {
  const schemeEnd = pattern.indexOf('://');
  const searchFrom = schemeEnd === -1 ? 0 : schemeEnd + 3;
  const slash = pattern.indexOf('/', searchFrom);
  return slash === -1 ? pattern.length : slash;
}

function escapeRegExp(character: string | undefined): string {
  if (!character) return '';
  return /[.+?^${}()|[\]\\]/.test(character) ? `\\${character}` : character;
}
