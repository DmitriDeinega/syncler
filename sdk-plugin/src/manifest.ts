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

// Plugin id regex. The server-side parity check now enforces the same
// pattern on `plugin_identifier` (server/app/schemas.py), so authors get
// the same rejection client-side and server-side.
const idPattern = /^[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+$/;
// Semver-lite with 6-digit caps on each numeric component, matching the
// server's `_SEMVER` (server/app/services/plugins.py). Capping the
// components keeps client + server Int parsers in agreement.
const semverPattern =
  /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
// $.field(.subfield)* — same surface as the server's `_JSONPATH_REGEX`.
// No array indexing, wildcards, or filters in V1.
const jsonPathPattern = /^\$(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
const hexPattern = /^[0-9a-fA-F]+$/;
const allCapabilities = new Set<string>(Object.values(Capability));
const allDismissBehaviors = new Set<string>(Object.values(DismissBehavior));

// Layout → allowed `template.fields` keys (required ∪ optional). Mirror of
// the server's `_LAYOUT_REQUIRED_FIELDS` + `_LAYOUT_OPTIONAL_FIELDS`. Adding
// a new layout here MUST track the server change in lockstep.
const layoutRequiredFields: Record<string, ReadonlySet<string>> = {
  standard_card: new Set(['title']),
};
const layoutOptionalFields: Record<string, ReadonlySet<string>> = {
  standard_card: new Set(['subtitle', 'body']),
};

let activeManifest: PluginManifest | undefined;

/**
 * Options for [validatePluginManifest] and [assertPluginManifest].
 */
export interface ValidatePluginManifestOptions {
  /**
   * When true, accepts any non-empty string for `bundleHash` and `signature`
   * (skipping the hex format + 64/128-length checks). The runtime path —
   * `registerPlugin` inside a loaded bundle — uses this because:
   *  - the in-bundle source manifest can't know its own bundle hash or
   *    signature (those are produced by `tools/sign-bundle.ts` and written
   *    to `manifest.signed.json` on disk, not back into the JS source);
   *  - the host doesn't trust these fields from the loaded bundle anyway —
   *    it has the authoritative signed values from the server side.
   * Publish-time tooling and server validators keep the default (false).
   */
  allowUnsignedPlaceholders?: boolean;
}

/**
 * Returns true when `value` is a valid plugin manifest.
 */
export function isPluginManifest(value: unknown): value is PluginManifest {
  return validatePluginManifest(value).valid;
}

/**
 * Validates unknown JSON-like data as a `PluginManifest`.
 */
export function validatePluginManifest(
  value: unknown,
  options: ValidatePluginManifestOptions = {}
):
  | { valid: true; manifest: PluginManifest }
  | { valid: false; issues: string[] } {
  const issues: string[] = [];
  const allowUnsignedPlaceholders = options.allowUnsignedPlaceholders === true;

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
    if (allowUnsignedPlaceholders) return;
    if (!hexPattern.test(hash)) {
      issues.push('bundleHash must be hex');
    } else if (hash.length !== 64) {
      issues.push(
        'bundleHash must be 64 hex chars (SHA-256). ' +
          'Run sign-bundle.ts on your built bundle to populate this field.'
      );
    }
  });
  requireString(value, 'signature', issues, (signature) => {
    if (allowUnsignedPlaceholders) return;
    if (!hexPattern.test(signature)) {
      issues.push('signature must be hex');
    } else if (signature.length !== 128) {
      issues.push(
        'signature must be 128 hex chars (Ed25519, 64 bytes). ' +
          'Run sign-bundle.ts on your built bundle to populate this field.'
      );
    }
  });
  requireString(value, 'minPlatformVersion', issues, (version) => {
    if (!semverPattern.test(version))
      issues.push('minPlatformVersion must be semver');
  });

  // renderer + template pairing. Both are optional; server defaults
  // renderer to 'script'. When present, renderer must be one of the
  // two enum values, and template/script ↔ template-present pairing
  // is enforced strictly (matches server's
  // PluginPublishRequest.validate_renderer_template_pairing).
  const renderer = value.renderer;
  if (
    renderer !== undefined &&
    renderer !== 'script' &&
    renderer !== 'template'
  ) {
    issues.push('renderer must be "script" or "template" when set');
  }
  const effectiveRenderer = renderer ?? 'script';
  if (effectiveRenderer === 'template' && !value.template) {
    issues.push('template required when renderer is "template"');
  }
  if (
    effectiveRenderer === 'script' &&
    value.template !== undefined &&
    value.template !== null
  ) {
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
  if (
    effectiveCardType === 'event' &&
    value.cardKeyPath !== undefined &&
    value.cardKeyPath !== null
  ) {
    issues.push('cardKeyPath must be omitted when cardType is "event"');
  }
  if (
    effectiveCardType === 'live' &&
    typeof value.cardKeyPath === 'string' &&
    !jsonPathPattern.test(value.cardKeyPath)
  ) {
    issues.push(
      'cardKeyPath must match $.field(.subfield)* ' +
        '(no array indexing, wildcards, or filters)'
    );
  }

  // Template block validation. Only run when the manifest has actually
  // declared `renderer: 'template'` (or a template block is present); the
  // renderer/template pairing rule above already covered presence/absence.
  if (effectiveRenderer === 'template' && isRecord(value.template)) {
    validateTemplateBlock(value.template, value.declaredEndpoints, issues);
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

  if (
    typeof value.dismissBehavior !== 'string' ||
    !allDismissBehaviors.has(value.dismissBehavior)
  ) {
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
export function assertPluginManifest(
  value: unknown,
  options?: ValidatePluginManifestOptions
): asserts value is PluginManifest {
  const result = validatePluginManifest(value, options);
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
export function setRegisteredManifest(
  manifest: PluginManifest | undefined
): void {
  activeManifest = manifest;
}

function requireString(
  record: Record<string, unknown>,
  key: keyof PluginManifest,
  issues: string[],
  validate?: (value: string) => void
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

function validateTemplateBlock(
  template: Record<string, unknown>,
  declaredEndpointsRaw: unknown,
  issues: string[]
): void {
  // layout — must be in the known set.
  const layout =
    typeof template.layout === 'string' ? template.layout : undefined;
  const required = layout ? layoutRequiredFields[layout] : undefined;
  const optional = layout ? layoutOptionalFields[layout] : undefined;
  if (!layout || !required || !optional) {
    issues.push(
      `template.layout must be one of: ${Object.keys(layoutRequiredFields).join(', ')}`
    );
    return;
  }
  const allowed = new Set<string>([...required, ...optional]);

  // fields — keys must be in the layout's allowed set; required keys
  // must be present. (Mirrors server's TemplateObject.validate_fields_for_layout.)
  const fields = template.fields;
  if (!isRecord(fields)) {
    issues.push('template.fields must be an object');
  } else {
    for (const key of Object.keys(fields)) {
      if (!allowed.has(key)) {
        issues.push(
          `template.fields.${key} is not allowed for layout "${layout}" ` +
            `(allowed: ${[...allowed].sort().join(', ')})`
        );
      }
      const field = fields[key];
      if (
        !isRecord(field) ||
        typeof field.path !== 'string' ||
        !jsonPathPattern.test(field.path)
      ) {
        issues.push(
          `template.fields.${key}.path must match $.field(.subfield)*`
        );
      }
    }
    for (const key of required) {
      if (!(key in fields)) {
        issues.push(
          `template.fields.${key} is required for layout "${layout}"`
        );
      }
    }
  }

  // actions — endpoint must be HTTPS, or HTTP targeting a LAN private
  // range / localhost. (Debug builds on Android also accept LAN HTTP;
  // release builds reject cleartext at the bridge. Server-side mirror
  // enforces the same rule at publish time.)
  const declaredEndpoints = Array.isArray(declaredEndpointsRaw)
    ? declaredEndpointsRaw.filter((p): p is string => typeof p === 'string')
    : [];
  const actions = template.actions;
  if (actions !== undefined) {
    if (!Array.isArray(actions)) {
      issues.push('template.actions must be an array');
    } else {
      const seen = new Set<string>();
      for (let index = 0; index < actions.length; index += 1) {
        const action = actions[index];
        if (!isRecord(action)) {
          issues.push(`template.actions[${index}] must be an object`);
          continue;
        }
        const aid = typeof action.id === 'string' ? action.id : undefined;
        const label =
          typeof action.label === 'string' ? action.label : undefined;
        const endpoint =
          typeof action.endpoint === 'string' ? action.endpoint : undefined;
        if (!aid || !label || !endpoint) {
          issues.push(
            `template.actions[${index}] must set id, label, and endpoint`
          );
          continue;
        }
        if (seen.has(aid)) {
          issues.push(`template.actions[${index}].id "${aid}" is duplicated`);
        }
        seen.add(aid);
        if (!isAllowedActionEndpointScheme(endpoint)) {
          issues.push(
            `template.actions[${index}].endpoint "${endpoint}" must be HTTPS, ` +
              'or HTTP targeting localhost / a LAN private range (10.x, 172.16-31, 192.168.x).'
          );
        }
        // Match against declaredEndpoints globs (same matcher as
        // `assertEndpointDeclared` so behavior agrees).
        if (
          declaredEndpoints.length > 0 &&
          !endpointMatchesAnyGlob(endpoint, declaredEndpoints)
        ) {
          issues.push(
            `template.actions[${index}].endpoint "${endpoint}" not covered by declaredEndpoints`
          );
        }
      }
    }
  }
}

function isAllowedActionEndpointScheme(url: string): boolean {
  // Validate the raw URL string with a strict regex BEFORE handing it
  // to any parser. Codex consultations 80/81/82 caught a series of
  // SDK/server mismatches when each side parsed the URL with its
  // platform parser (WHATWG `URL` in the SDK, `urllib.parse.urlparse`
  // on the server):
  //   - consultation 80: HTTPS-with-userinfo accepted; IPv4 octets not
  //     range-validated.
  //   - consultation 81: WHATWG normalized legacy IPv4 (`0177.0.0.1`,
  //     `127.1`, `010.0.0.1`); urlparse didn't.
  //   - consultation 82: WHATWG treats empty userinfo (`http://@host/x`)
  //     as no userinfo; urlparse doesn't. WHATWG normalizes backslash
  //     schemes (`https:\host/x`); urlparse doesn't.
  //
  // Skipping the parsers and validating the raw string against a
  // canonical authority grammar removes all of these mismatches.
  // Server-side `_is_allowed_action_endpoint_scheme` uses the
  // byte-equivalent regex.
  const match = ACTION_ENDPOINT_PATTERN.exec(url);
  if (!match) return false;
  const scheme = match[1]?.toLowerCase();
  const host = match[2]?.toLowerCase();
  if (scheme === undefined || host === undefined) return false;
  if (scheme === 'https') return true;
  if (host === 'localhost') return true;
  return isLanPrivateIpv4(host);
}

// scheme://<host>[:port][/path|?query|#fragment]
//
// Mirror of the server's `_ACTION_ENDPOINT_PATTERN`. Lowercase-only
// scheme + host — no `/i` flag, since Python's `re.IGNORECASE` does
// Unicode case folding even with `re.ASCII` (Codex 84: `K` U+212A
// folds into `[A-Z]`, `ſ` folds into `s`, etc.). Authors write
// lowercase URLs.
//
// Char-class details:
//   - Host: ASCII alnum + `.` `-` `_` only (no IDN, no whitespace,
//     no userinfo `@`, no backslash-scheme `\`, no IPv6 brackets).
//   - Port digits: `[0-9]+` (not `\d`).
//   - Path/query/fragment: printable ASCII `[\x21-\x7e]` only —
//     rejects whitespace and `\r` (Codex 84).
const ACTION_ENDPOINT_PATTERN =
  /^(https?):\/\/([a-z0-9._-]+)(?::[0-9]+)?(?:[/?#][\x21-\x7e]*)?$/;

function isLanPrivateIpv4(host: string): boolean {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!match || match.length < 5) return false;
  // Reject leading-zero octets so `010.0.0.1` is not treated as `10.x`.
  // Codex 81 RED: WHATWG URL normalizes leading-zero octets as octal,
  // Python `urlparse` doesn't normalize at all. Strict canonical
  // 4-octet decimal keeps both sides in agreement.
  const groups = [match[1], match[2], match[3], match[4]];
  for (const group of groups) {
    if (group === undefined) return false;
    if (group.length > 1 && group.startsWith('0')) return false;
  }
  const [a, b, c, d] = groups.map((g) => Number(g));
  for (const octet of [a, b, c, d]) {
    if (octet === undefined || !Number.isInteger(octet) || octet < 0 || octet > 255) {
      return false;
    }
  }
  if (a === 10) return true;
  if (a === 172 && b! >= 16 && b! <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 127) return true; // 127.0.0.0/8 loopback
  return false;
}

function endpointMatchesAnyGlob(
  url: string,
  patterns: readonly string[]
): boolean {
  return patterns.some((pattern) => endpointPatternToRegExp(pattern).test(url));
}

function endpointPatternToRegExp(pattern: string): RegExp {
  // Mirror of network.ts:endpointPatternToRegExp. Duplicated here to
  // avoid pulling network's `assertEndpointDeclared` (which reads the
  // active runtime manifest) into manifest-validation context.
  const schemeEnd = pattern.indexOf('://');
  const searchFrom = schemeEnd === -1 ? 0 : schemeEnd + 3;
  const slash = pattern.indexOf('/', searchFrom);
  const pathStart = slash === -1 ? pattern.length : slash;
  let source = '';
  for (let index = 0; index < pattern.length; index += 1) {
    const character = pattern[index];
    if (character === undefined) continue;
    if (character === '*') {
      source += index < pathStart ? '[^./]*' : '[^/]*';
    } else if (/[.+?^${}()|[\]\\]/.test(character)) {
      source += `\\${character}`;
    } else {
      source += character;
    }
  }
  return new RegExp(`^${source}$`);
}
