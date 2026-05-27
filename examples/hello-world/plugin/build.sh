#!/usr/bin/env bash
# Build + sign the hello-world plugin bundle.
#
# Outputs (all under dist/):
#   plugin.bundle.js      — IIFE-format bundle the WebView loads
#   manifest.signed.json  — manifest + bundle hash + Ed25519 signature
#   bundle.sha256         — SHA-256 of the bundle (for sanity)
#
# Requires npm install having been run once.

set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
mkdir -p dist

echo "--- bundling src/plugin.ts (esbuild, IIFE) ---"
npx esbuild src/plugin.ts \
    --bundle \
    --format=iife \
    --global-name=SynclerPluginExports \
    --platform=browser \
    --outfile=dist/plugin.bundle.js

echo "--- bundle hash ---"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum dist/plugin.bundle.js | awk '{print $1}' > dist/bundle.sha256
else
    # macOS / Windows-without-coreutils fallback
    shasum -a 256 dist/plugin.bundle.js | awk '{print $1}' > dist/bundle.sha256
fi
echo "    bundle.sha256: $(cat dist/bundle.sha256)"

echo "--- signing manifest ---"
# Sign with the SDK's sign-bundle.ts tool. Uses the same Ed25519
# private key your sender uses (sender-ed25519.pem) so the resulting
# signature passes the server's publish-time verification.
SENDER_KEY="${SENDER_KEY:-../../sender/sender-ed25519.pem}"
if [ ! -f "$SENDER_KEY" ]; then
    echo "Sender key not found at $SENDER_KEY"
    echo "Run the sender's bot.py once first; it generates the key on first start."
    exit 1
fi

npx tsx node_modules/@syncler/plugin-sdk/tools/sign-bundle.ts \
    manifest.json \
    dist/plugin.bundle.js \
    "$SENDER_KEY" \
    > dist/manifest.signed.json

echo "--- done ---"
echo "    dist/plugin.bundle.js    ($(wc -c < dist/plugin.bundle.js) bytes)"
echo "    dist/manifest.signed.json"
