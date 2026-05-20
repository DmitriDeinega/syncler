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
  console.error('Usage: tsx tools/sign-bundle.ts <bundle.js> <private_key.pem> <manifest.json> [output.manifest.json]');
  process.exit(1);
}

const bundle = await readFile(bundlePath);
const privateKey = await readFile(privateKeyPath, 'utf8');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8')) as Manifest;

const bundleHash = createHash('sha256').update(bundle).digest('hex');
const signature = sign(null, bundle, privateKey).toString('hex');
const signedManifest = {
  ...manifest,
  bundleHash,
  signature,
};

const target = outputPath ?? manifestPath.replace(/\.json$/i, '.signed.json');
await writeFile(target, `${JSON.stringify(signedManifest, null, 2)}\n`, 'utf8');

console.log(`Signed ${basename(bundlePath)}`);
console.log(`bundleHash=${bundleHash}`);
console.log(`manifest=${target}`);
