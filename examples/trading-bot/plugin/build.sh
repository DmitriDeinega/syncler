#!/usr/bin/env bash
# Phase 5b — plugin build for examples/trading-bot/plugin.
#
# Requires Node. Run from this directory:
#   chmod +x build.sh && ./build.sh
#
# Output: dist/plugin.bundle.js (IIFE format — the Syncler host loads
# bundles in a regular <script> tag, which can't parse ESM exports).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

mkdir -p dist
npx --yes esbuild src/plugin.ts \
  --bundle \
  --format=iife \
  --global-name=SynclerPluginExports \
  --platform=browser \
  --alias:@syncler/plugin-sdk=../../../sdk-plugin/src/index.ts \
  --outfile=dist/plugin.bundle.js

echo "Built dist/plugin.bundle.js"
echo "Next: sign with"
echo "  npx tsx ../../../sdk-plugin/tools/sign-bundle.ts dist/plugin.bundle.js ~/.syncler/keys/trading-bot.pem manifest.json manifest.signed.json"
