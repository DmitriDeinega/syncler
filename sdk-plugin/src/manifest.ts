import { Capability, DismissBehavior } from './enums';

/**
 * One template field — a JSONPath into the decrypted payload. V1 dialect is
 * `$.field(.subfield)*` only (no array indexing, wildcards, or filters).
 */
export interface TemplateField {
  path: string;
}

/**
 * One template action button. `endpoint` MUST match one of the plugin's
 * `declaredEndpoints` globs (validated server-side at publish time).
 */
export interface TemplateAction {
  id: string;
  label: string;
  endpoint: string;
}

/**
 * Template manifest block. Required when `renderer === 'template'`.
 */
export interface TemplateBlock {
  layout: 'standard_card';
  fields: Record<string, TemplateField>;
  actions?: TemplateAction[];
}

/**
 * Plugin metadata consumed by the native host before loading a bundle.
 */
export interface PluginManifest {
  /** Reverse-DNS plugin identifier, for example `com.example.plugin`. */
  id: string;
  /** Human-readable plugin name. */
  name: string;
  /** Semantic plugin version. */
  version: string;
  /** Sender identifier associated with inbound encrypted messages. */
  senderId: string;
  /** Lowercase or uppercase hexadecimal hash of the JavaScript bundle. */
  bundleHash: string;
  /** Lowercase or uppercase hexadecimal Ed25519 signature. */
  signature: string;
  /** Capabilities the plugin is allowed to use at runtime. */
  declaredCapabilities: Capability[];
  /** Allowed URL patterns for `platform.network.fetch`. */
  declaredEndpoints: string[];
  /** Host dismissal behavior for notifications created by this plugin. */
  dismissBehavior: DismissBehavior;
  /** Minimum compatible native bridge version. */
  minPlatformVersion: string;
  /**
   * Render mode. `'script'` (default) loads the JS bundle in a WebView.
   * `'template'` ships no JS and uses a native Compose card driven by
   * [template]. Server defaults to `'script'` when omitted.
   */
  renderer?: 'script' | 'template';
  /**
   * Template manifest. Required when [renderer] is `'template'`; rejected
   * when [renderer] is `'script'`. Validated server-side at publish time.
   */
  template?: TemplateBlock;
  /**
   * Card delivery semantics. `'event'` (default) — each send is a new
   * immutable inbox row. `'live'` — persistent, upsertable; see
   * `docs/integration-guide.md §5.2`. Server defaults to `'event'`.
   */
  cardType?: 'event' | 'live';
  /**
   * JSONPath into the decrypted payload that yields the stable card
   * identity. REQUIRED when [cardType] is `'live'`; rejected otherwise.
   * V1 validates only `startsWith("$")` server-side.
   */
  cardKeyPath?: string;
}

/**
 * Error raised when plugin manifest validation fails.
 */
export class ManifestValidationError extends Error {
  /** Individual validation failures. */
  readonly issues: string[];

  constructor(issues: string[]) {
    super(`Invalid plugin manifest: ${issues.join('; ')}`);
    this.name = 'ManifestValidationError';
    this.issues = issues;
  }
}

const idPattern = /^[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+$/;
const semverPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
const hexPattern = /^[0-9a-fA-F]+$/;
const allCapabilities = new Set<string>(Object.values(Capability));
const allDismissBehaviors = new Set<string>(Object.values(DismissBehavior));

let activeManifest: PluginManifest | undefined;

/**
 * Returns true when `value` is a valid plugin manifest.
 */
export function isPluginManifest(value: unknown): value is PluginManifest {
  return validatePluginManifest(value).valid;
}

/**
 * Validates unknown JSON-like data as a `PluginManifest`.
 */
export function validatePluginManifest(value: unknown): { valid: true; manifest: PluginManifest } | { valid: false; issues: string[] } {
  const issues: string[] = [];

  if (!isRecord(value)) {
    return { valid: false, issues: ['manifest must be an object'] };
  }

  requireString(value, 'id', issues, (id) => {
    if (!idPattern.test(id)) issues.push('id must be reverse-DNS format');
  });
  requireString(value, 'name', issues);
  requireString(value, 'version', issues, (version) => {
    if (!semverPattern.test(version)) issues.push('version must be semver');
  });
  requireString(value, 'senderId', issues);
  // bundleHash is SHA-256 of the bundle bytes → 32 bytes → 64 hex chars.
  // signature is Ed25519 over (canonical manifest || bundleHash) → 64 bytes
  // → 128 hex chars. The previous "any hex" check let "00" or any other
  // placeholder through validation, which authors hit constantly: the
  // server rejects at publish time with a 422, but the SDK should have
  // caught it locally. (External DX review, lottery-claude, Phase 4.1 #1.)
  requireString(value, 'bundleHash', issues, (hash) => {
    if (!hexPattern.test(hash)) {
      issues.push('bundleHash must be hex');
    } else if (hash.length !== 64) {
      issues.push(
        'bundleHash must be 64 hex chars (SHA-256). ' +
          'Run sign-bundle.ts on your built bundle to populate this field.',
      );
    }
  });
  requireString(value, 'signature', issues, (signature) => {
    if (!hexPattern.test(signature)) {
      issues.push('signature must be hex');
    } else if (signature.length !== 128) {
      issues.push(
        'signature must be 128 hex chars (Ed25519, 64 bytes). ' +
          'Run sign-bundle.ts on your built bundle to populate this field.',
      );
    }
  });
  requireString(value, 'minPlatformVersion', issues, (version) => {
    if (!semverPattern.test(version)) issues.push('minPlatformVersion must be semver');
  });

  // renderer + template pairing. Both are optional; server defaults
  // renderer to 'script'. When present, renderer must be one of the
  // two enum values, and template/script ↔ template-present pairing
  // is enforced strictly (matches server's
  // PluginPublishRequest.validate_renderer_template_pairing).
  const renderer = value.renderer;
  if (renderer !== undefined && renderer !== 'script' && renderer !== 'template') {
    issues.push('renderer must be "script" or "template" when set');
  }
  const effectiveRenderer = renderer ?? 'script';
  if (effectiveRenderer === 'template' && !value.template) {
    issues.push('template required when renderer is "template"');
  }
  if (effectiveRenderer === 'script' && value.template !== undefined && value.template !== null) {
    issues.push('template must be omitted when renderer is "script"');
  }

  // cardType + cardKeyPath pairing. Same defaulting rules — optional in
  // the SDK, server defaults to 'event'. cardKeyPath is required iff
  // cardType is 'live'.
  const cardType = value.cardType;
  if (cardType !== undefined && cardType !== 'event' && cardType !== 'live') {
    issues.push('cardType must be "event" or "live" when set');
  }
  const effectiveCardType = cardType ?? 'event';
  if (effectiveCardType === 'live' && !value.cardKeyPath) {
    issues.push('cardKeyPath required when cardType is "live"');
  }
  if (effectiveCardType === 'event' && value.cardKeyPath !== undefined && value.cardKeyPath !== null) {
    issues.push('cardKeyPath must be omitted when cardType is "event"');
  }
  if (
    effectiveCardType === 'live' &&
    typeof value.cardKeyPath === 'string' &&
    !value.cardKeyPath.startsWith('$')
  ) {
    issues.push('cardKeyPath must begin with "$"');
  }

  if (!Array.isArray(value.declaredCapabilities)) {
    issues.push('declaredCapabilities must be an array');
  } else {
    for (const capability of value.declaredCapabilities) {
      if (typeof capability !== 'string' || !allCapabilities.has(capability)) {
        issues.push(`unknown capability: ${String(capability)}`);
      }
    }
  }

  if (!Array.isArray(value.declaredEndpoints)) {
    issues.push('declaredEndpoints must be an array');
  } else {
    for (const endpoint of value.declaredEndpoints) {
      if (typeof endpoint !== 'string' || endpoint.length === 0) {
        issues.push('declaredEndpoints entries must be non-empty strings');
      }
    }
  }

  if (typeof value.dismissBehavior !== 'string' || !allDismissBehaviors.has(value.dismissBehavior)) {
    issues.push('dismissBehavior must be a known dismiss behavior');
  }

  if (issues.length > 0) {
    return { valid: false, issues };
  }

  return { valid: true, manifest: value as unknown as PluginManifest };
}

/**
 * Validates a manifest and throws `ManifestValidationError` when invalid.
 */
export function assertPluginManifest(value: unknown): asserts value is PluginManifest {
  const result = validatePluginManifest(value);
  if (!result.valid) {
    throw new ManifestValidationError(result.issues);
  }
}

/**
 * Returns the manifest registered for the currently loaded plugin, if any.
 */
export function getRegisteredManifest(): PluginManifest | undefined {
  return activeManifest;
}

/**
 * Stores the manifest for SDK runtime checks performed after plugin registration.
 *
 * @internal
 */
export function setRegisteredManifest(manifest: PluginManifest | undefined): void {
  activeManifest = manifest;
}

function requireString(
  record: Record<string, unknown>,
  key: keyof PluginManifest,
  issues: string[],
  validate?: (value: string) => void,
): void {
  const value = record[key];
  if (typeof value !== 'string' || value.length === 0) {
    issues.push(`${String(key)} must be a non-empty string`);
    return;
  }
  validate?.(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
