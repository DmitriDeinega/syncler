#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"

npx esbuild "$ROOT_DIR/examples/minimal/src/plugin.ts" \
  --bundle \
  --format=esm \
  --platform=browser \
  --alias:@syncler/plugin-sdk="$ROOT_DIR/src/index.ts" \
  --outfile="$ROOT_DIR/examples/minimal/dist/plugin.bundle.js"

echo "Bundle written to examples/minimal/dist/plugin.bundle.js"
echo "Sign with: npx tsx tools/sign-bundle.ts examples/minimal/dist/plugin.bundle.js private_key.pem examples/minimal/manifest.json"
