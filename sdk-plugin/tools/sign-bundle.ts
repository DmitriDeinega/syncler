/**
 * Sign a plugin bundle for Syncler.
 *
 * The Android host verifies the signature over
 *   canonical_manifest_without_signature  ||  bundleHash_raw_bytes
 * per ``docs/crypto-spec.md`` §5. This tool computes the bundle hash,
 * inserts it into the manifest, computes the canonical bytes the same
 * way Android does, signs those, and writes the signature back into
 * the manifest.
 *
 * Usage: tsx tools/sign-bundle.ts <bundle.js> <ed25519_private_key.pem> <manifest.json> [output.signed.json]
 */
import { createHash, sign } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { basename } from 'node:path';

interface Manifest {
  bundleHash?: string;
  signature?: string;
  [key: string]: unknown;
}

const [, , bundlePath, privateKeyPath, manifestPath, outputPath] = process.argv;

if (!bundlePath || !privateKeyPath || !manifestPath) {
  console.error(
    'Usage: tsx tools/sign-bundle.ts <bundle.js> <ed25519_private_key.pem> <manifest.json> [output.signed.json]'
  );
  process.exit(1);
}

const bundle = await readFile(bundlePath);
const privateKey = await readFile(privateKeyPath, 'utf8');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as Manifest;

const bundleHashHex = createHash('sha256').update(bundle).digest('hex');
const bundleHashBytes = Buffer.from(bundleHashHex, 'hex');

// Manifest gets bundleHash inserted before signing; signature field is
// removed (cannot self-include) and the manifest is canonicalized.
const manifestWithBundleHash: Manifest = { ...manifest, bundleHash: bundleHashHex };
delete manifestWithBundleHash.signature;

const canonicalManifest = Buffer.from(canonicalJson(manifestWithBundleHash), 'utf8');
const signingInput = Buffer.concat([canonicalManifest, bundleHashBytes]);

const signatureHex = sign(null, signingInput, privateKey).toString('hex');

const signedManifest = {
  ...manifest,
  bundleHash: bundleHashHex,
  signature: signatureHex,
};

const target = outputPath ?? manifestPath.replace(/\.json$/i, '.signed.json');
await writeFile(target, `${JSON.stringify(signedManifest, null, 2)}\n`, 'utf8');

console.log(`Signed ${basename(bundlePath)}`);
console.log(`bundleHash=${bundleHashHex}`);
console.log(`manifest=${target}`);

/**
 * Canonical JSON with sorted keys, no whitespace, ASCII-escape non-ASCII.
 * Matches ``server/app/crypto/signatures.py:canonical_manifest_for_signing``
 * and ``android/.../PluginSignatureVerifier.kt:canonicalJson`` byte-for-byte
 * given equivalent inputs.
 */
function canonicalJson(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('JSON numbers must be finite');
    return Number.isInteger(value) ? String(value) : String(value);
  }
  if (typeof value === 'string') return quote(value);
  if (Array.isArray(value)) {
    return '[' + value.map(canonicalJson).join(',') + ']';
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value as object).sort();
    return (
      '{' +
      keys
        .map((k) => quote(k) + ':' + canonicalJson((value as Record<string, unknown>)[k]))
        .join(',') +
      '}'
    );
  }
  throw new Error(`Unsupported value type: ${typeof value}`);
}

function quote(s: string): string {
  let out = '"';
  for (const ch of s) {
    const code = ch.codePointAt(0)!;
    switch (ch) {
      case '"':
        out += '\\"';
        break;
      case '\\':
        out += '\\\\';
        break;
      case '\b':
        out += '\\b';
        break;
      case '\f':
        out += '\\f';
        break;
      case '\n':
        out += '\\n';
        break;
      case '\r':
        out += '\\r';
        break;
      case '\t':
        out += '\\t';
        break;
      default:
        if (code < 0x20 || code > 0x7e) {
          out += '\\u' + code.toString(16).padStart(4, '0');
        } else {
          out += ch;
        }
    }
  }
  return out + '"';
}
