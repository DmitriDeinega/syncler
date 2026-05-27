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
#
# The key gets generated here if missing — sign-bundle.ts must run
# BEFORE the sender bot, so we can't depend on bot.py creating it on
# first launch. The SDK's `load_private_key` loads PKCS8 Ed25519 PEMs,
# which is exactly what `openssl genpkey -algorithm Ed25519` writes.
SENDER_KEY="${SENDER_KEY:-../sender/sender-ed25519.pem}"
if [ ! -f "$SENDER_KEY" ]; then
    mkdir -p "$(dirname "$SENDER_KEY")"
    echo "Generating sender key at $SENDER_KEY"
    openssl genpkey -algorithm Ed25519 -out "$SENDER_KEY"
fi

# Positional args for sign-bundle.ts: <bundle> <private_key> <manifest> [output]
# The tool writes the signed manifest directly to <output> — no stdout redirect.
npx tsx node_modules/@syncler/plugin-sdk/tools/sign-bundle.ts \
    dist/plugin.bundle.js \
    "$SENDER_KEY" \
    manifest.json \
    dist/manifest.signed.json

echo "--- done ---"
echo "    dist/plugin.bundle.js    ($(wc -c < dist/plugin.bundle.js) bytes)"
echo "    dist/manifest.signed.json"
