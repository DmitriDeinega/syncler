import { Capability, DismissBehavior } from './enums';

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
  requireString(value, 'bundleHash', issues, (hash) => {
    if (!hexPattern.test(hash)) issues.push('bundleHash must be hex');
  });
  requireString(value, 'signature', issues, (signature) => {
    if (!hexPattern.test(signature)) issues.push('signature must be hex');
  });
  requireString(value, 'minPlatformVersion', issues, (version) => {
    if (!semverPattern.test(version)) issues.push('minPlatformVersion must be semver');
  });

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
