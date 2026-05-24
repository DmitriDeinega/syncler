#!/usr/bin/env node
/**
 * @syncler/create-plugin — scaffold a new Syncler plugin project.
 *
 * Invoked as `npm create @syncler/plugin <name>` or
 * `npx @syncler/create-plugin <name>`. Prompts the author for the
 * manifest details, validates them against the SDK's
 * `validatePluginManifest`-compatible rules, and writes a starter
 * project directory with all the wiring an author needs to skip
 * the "blank page" problem.
 *
 * Plain Node stdlib only — no inquirer, no chalk, no fs-extra. Plain
 * template literals over a `templates/` directory (loaded as files
 * for clarity).
 *
 * Phase 5c per `.triad/70-phase5-agreement.md`.
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Matches the server + SDK regex (PluginManifest.id):
const ID_PATTERN = /^[a-zA-Z][a-zA-Z0-9-]*(\.[a-zA-Z][a-zA-Z0-9-]*)+$/;
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

// Mirrors sdk-plugin/src/enums.ts Capability. `showNotification` isn't
// a capability — it's an always-available platform method.
const CAPABILITIES = ['network', 'storage', 'camera', 'gallery', 'file', 'location', 'background-exec'];
const RENDERERS = ['script', 'template'];
const CARD_TYPES = ['event', 'live'];

// Project dirs must produce a valid TypeScript class identifier when
// pascal-cased. Lowercase letters/digits/hyphens/underscores, must
// start with a letter.
const DIR_NAME_PATTERN = /^[a-z][a-z0-9_-]*$/;

function err(message) {
  process.stderr.write(`error: ${message}\n`);
}

function readPositionalName() {
  // `npm create @syncler/plugin foo` passes `foo` as argv[2] when run
  // via npm; via npx the positional is argv[2] too. Accept it but
  // fall back to a prompt below if missing.
  const positional = process.argv.slice(2).find((a) => !a.startsWith('-'));
  return positional && positional.length > 0 ? positional : null;
}

async function ensureValidDirName(ask, positional) {
  if (positional && DIR_NAME_PATTERN.test(positional)) {
    return positional;
  }
  if (positional) {
    err(`"${positional}" is not a valid project directory name — must start with a letter, then [a-z0-9_-] only`);
  }
  return await prompt(ask, 'Project directory name (lowercase, starts with letter)', {
    validate: (v) => DIR_NAME_PATTERN.test(v)
      ? null
      : 'must start with a letter and contain only [a-z0-9_-]',
  });
}

// Node's readline.question() callback only fires once when stdin is a
// pipe — subsequent question() calls silently never resolve, so we'd
// hang or silently exit halfway through the prompts. Driving readline
// via the 'line' event instead works in both TTY and piped contexts.
function makeAsker(rl) {
  const queue = [];
  let pendingResolver = null;
  let pendingRejector = null;
  rl.on('line', (line) => {
    if (pendingResolver) {
      const r = pendingResolver;
      pendingResolver = null;
      pendingRejector = null;
      r(line);
    } else {
      queue.push(line);
    }
  });
  rl.on('close', () => {
    if (pendingRejector) {
      const j = pendingRejector;
      pendingResolver = null;
      pendingRejector = null;
      j(new Error('stdin closed before answer'));
    }
  });
  return function ask(text) {
    process.stdout.write(text);
    return new Promise((resolve, reject) => {
      if (queue.length > 0) {
        resolve(queue.shift());
      } else {
        pendingResolver = resolve;
        pendingRejector = reject;
      }
    });
  };
}

async function prompt(ask, question, { defaultValue, validate } = {}) {
  for (;;) {
    const hint = defaultValue !== undefined ? ` [${defaultValue}]` : '';
    const answer = await ask(`${question}${hint}: `);
    const trimmed = answer.trim();
    const value = trimmed.length > 0 ? trimmed : defaultValue;
    if (value === undefined || value === '') {
      err('value is required');
      continue;
    }
    if (validate) {
      const issue = validate(value);
      if (issue) {
        err(issue);
        continue;
      }
    }
    return value;
  }
}

async function promptMultiSelect(ask, question, options) {
  process.stdout.write(`${question} (comma-separated; available: ${options.join(', ')}):\n`);
  for (;;) {
    const answer = await ask('> ');
    const tokens = answer.split(',').map((s) => s.trim()).filter(Boolean);
    const invalid = tokens.filter((t) => !options.includes(t));
    if (invalid.length > 0) {
      err(`unknown: ${invalid.join(', ')}`);
      continue;
    }
    return tokens;
  }
}

async function main() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const ask = makeAsker(rl);
  try {
    const dirName = await ensureValidDirName(ask, readPositionalName());

    const targetDir = path.resolve(process.cwd(), dirName);
    if (fs.existsSync(targetDir)) {
      err(`${targetDir} already exists — choose a different name`);
      process.exit(1);
    }

    const id = await prompt(ask, 'Plugin id (reverse-DNS, e.g. com.example.weather)', {
      validate: (v) => ID_PATTERN.test(v) ? null : 'must be reverse-DNS (e.g. com.example.weather)',
    });
    const name = await prompt(ask, 'Display name', { defaultValue: dirName });
    const senderId = await prompt(ask, 'Sender id (UUID from `client.register_if_needed()`)', {
      validate: (v) => UUID_PATTERN.test(v) ? null : 'must be a UUID (lowercase, hyphenated)',
    });
    const renderer = await prompt(ask, `Renderer (${RENDERERS.join('|')})`, {
      defaultValue: 'script',
      validate: (v) => RENDERERS.includes(v) ? null : `must be one of ${RENDERERS.join(', ')}`,
    });
    const cardType = await prompt(ask, `Card type (${CARD_TYPES.join('|')})`, {
      defaultValue: 'event',
      validate: (v) => CARD_TYPES.includes(v) ? null : `must be one of ${CARD_TYPES.join(', ')}`,
    });
    const capabilities = await promptMultiSelect(ask, 'Capabilities', CAPABILITIES);

    let cardKeyPath = null;
    if (cardType === 'live') {
      cardKeyPath = await prompt(ask, 'card_key_path (JSONPath into payload, e.g. $.order_id)', {
        defaultValue: '$.id',
        validate: (v) => v.startsWith('$') ? null : 'must start with $',
      });
    }

    rl.close();

    const ctx = { dirName, id, name, senderId, renderer, cardType, capabilities, cardKeyPath };
    writeScaffold(targetDir, ctx);

    console.log(`\nScaffolded ${dirName}/\n`);
    console.log('Next steps:');
    console.log(`  cd ${dirName}`);
    console.log('  npm install               # installs esbuild + tsx + typescript');
    console.log('  ./build.sh                # builds dist/plugin.bundle.js (prints the sign command)');
    console.log('  # then sign + publish (see README.md and examples/trading-bot/).');
    console.log('');
  } catch (e) {
    err(String(e && e.message || e));
    process.exit(1);
  } finally {
    rl.close();
  }
}

function writeScaffold(targetDir, ctx) {
  fs.mkdirSync(targetDir, { recursive: true });
  fs.mkdirSync(path.join(targetDir, 'src'), { recursive: true });

  fs.writeFileSync(path.join(targetDir, 'manifest.json'), renderManifestJson(ctx) + '\n');
  fs.writeFileSync(path.join(targetDir, 'src', 'plugin.ts'), renderPluginTs(ctx));
  fs.writeFileSync(path.join(targetDir, 'build.sh'), renderBuildSh(), { mode: 0o755 });
  fs.writeFileSync(path.join(targetDir, 'package.json'), renderPackageJson(ctx) + '\n');
  fs.writeFileSync(path.join(targetDir, '.gitignore'), 'dist/\nmanifest.signed.json\nnode_modules/\n*.pem\n');
  fs.writeFileSync(path.join(targetDir, 'README.md'), renderReadme(ctx));
}

function renderManifestJson(ctx) {
  const m = {
    id: ctx.id,
    name: ctx.name,
    version: '1.0.0',
    senderId: ctx.senderId,
    bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    declaredCapabilities: ctx.capabilities,
    declaredEndpoints: ['https://your-app.example.com/api/*'],
    dismissBehavior: 'dismiss_local_only',
    minPlatformVersion: '1.0.0',
  };
  if (ctx.renderer !== 'script') m.renderer = ctx.renderer;
  // SDK manifest validation rejects renderer=template without a template
  // block, and rejects renderer=script with one. Ship a minimal 2-field
  // template block when the user opted into template rendering.
  if (ctx.renderer === 'template') {
    m.template = {
      layout: 'standard_card',
      fields: {
        title: { path: '$.title' },
        body: { path: '$.body' },
      },
    };
  }
  if (ctx.cardType !== 'event') m.cardType = ctx.cardType;
  if (ctx.cardKeyPath) m.cardKeyPath = ctx.cardKeyPath;
  return JSON.stringify(m, null, 2);
}

function renderPluginTs(ctx) {
  // `background-exec` → `BACKGROUND_EXEC`. Mirror enums.ts naming.
  const capArr = ctx.capabilities
    .map((c) => `Capability.${c.replace(/-/g, '_').toUpperCase()}`)
    .join(', ');
  const className = pascalCase(ctx.dirName);
  // Use JSON.stringify for all author-supplied strings so any quote /
  // backslash characters in name/id/senderId/cardKeyPath end up as
  // valid TS string literals.
  const jsonStr = (v) => JSON.stringify(v);
  const rendererLine = ctx.renderer !== 'script' ? `    renderer: ${jsonStr(ctx.renderer)},\n` : '';
  const cardTypeLine = ctx.cardType !== 'event' ? `    cardType: ${jsonStr(ctx.cardType)},\n` : '';
  const cardKeyPathLine = ctx.cardKeyPath ? `    cardKeyPath: ${jsonStr(ctx.cardKeyPath)},\n` : '';

  if (ctx.renderer === 'template') {
    // Template renderer ships no UI JS; the host renders a native
    // Compose card from payload + manifest.template.fields. The class
    // still needs to exist (BasePlugin.render is abstract) but the
    // body is a no-op since the host never invokes it.
    return `import {
  BasePlugin,
  Capability,
  DismissBehavior,
  registerPlugin,
  type HostPreview,
  type PluginManifest,
} from '@syncler/plugin-sdk';

interface ${className}Payload {
  hostPreview: HostPreview;
  // For template renderer, the host pulls these via manifest.template.fields
  // (JSONPath into the decrypted payload). Keep this interface in sync.
  title: string;
  body: string;
}

class ${className}Plugin extends BasePlugin {
  static manifest: PluginManifest = ${manifestStaticLines(ctx, jsonStr, capArr, rendererLine, cardTypeLine, cardKeyPathLine, /* templateBlock */ true)};

  // Template renderer: host never calls render(). onMessage / onAction
  // are still useful for sender-controlled side effects.
  render(_payload: ${className}Payload): string {
    return '';
  }
}

registerPlugin(new ${className}Plugin());
`;
  }

  // Script renderer (default).
  return `import {
  BasePlugin,
  Capability,
  DismissBehavior,
  registerPlugin,
  type HostPreview,
  type PluginManifest,
} from '@syncler/plugin-sdk';

interface ${className}Payload {
  hostPreview: HostPreview;
  // Add your payload fields here. The host already drew the inbox row
  // from hostPreview; render() draws the detail view shown on tap.
  title?: string;
  body?: string;
}

class ${className}Plugin extends BasePlugin {
  static manifest: PluginManifest = ${manifestStaticLines(ctx, jsonStr, capArr, rendererLine, cardTypeLine, cardKeyPathLine, /* templateBlock */ false)};

  render(payload: ${className}Payload): string {
    return \`
      <div style="font-family:system-ui,sans-serif;padding:16px">
        <h2>\${escapeHtml(payload.hostPreview.title)}</h2>
        \${payload.body ? \`<p>\${escapeHtml(payload.body)}</p>\` : ''}
        <button id="ack" style="font-size:16px;padding:10px 18px">Acknowledge</button>
      </div>
      <script>
        document.getElementById('ack').onclick = async () => {
          // Replace the URL with one of your manifest's declaredEndpoints.
          await platform.network.fetch('https://your-app.example.com/api/action', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ acted_at: new Date().toISOString() }),
          });
        };
      </script>
    \`;
  }
}

registerPlugin(new ${className}Plugin());

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]!));
}
`;
}

function manifestStaticLines(ctx, jsonStr, capArr, rendererLine, cardTypeLine, cardKeyPathLine, includeTemplate) {
  const templateLine = includeTemplate ? `    template: {\n` +
    `      layout: 'standard_card',\n` +
    `      fields: {\n` +
    `        title: { path: '$.title' },\n` +
    `        body: { path: '$.body' },\n` +
    `      },\n` +
    `    },\n` : '';
  return `{
    id: ${jsonStr(ctx.id)},
    name: ${jsonStr(ctx.name)},
    version: '1.0.0',
    senderId: ${jsonStr(ctx.senderId)},
    bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    declaredCapabilities: [${capArr}],
    declaredEndpoints: ['https://your-app.example.com/api/*'],
${rendererLine}${templateLine}${cardTypeLine}${cardKeyPathLine}    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  }`;
}

function renderBuildSh() {
  // For V0.1, @syncler/plugin-sdk isn't published to npm. The build
  // aliases the import path to the SDK's source inside the syncler
  // monorepo. Override via SYNCLER_SDK_SRC if you put this scaffold
  // outside the monorepo or want to point at a different SDK checkout.
  return `#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# Locate the SDK source. Walk up parents looking for sdk-plugin/src/index.ts
# unless SYNCLER_SDK_SRC overrides it. Required because @syncler/plugin-sdk
# is not yet on npm (V0.1).
find_sdk_src() {
  local dir="$HERE"
  while [ "$dir" != "/" ]; do
    if [ -f "$dir/sdk-plugin/src/index.ts" ]; then
      echo "$dir/sdk-plugin/src/index.ts"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  return 1
}

SDK_SRC="\${SYNCLER_SDK_SRC:-$(find_sdk_src || true)}"
if [ -z "$SDK_SRC" ] || [ ! -f "$SDK_SRC" ]; then
  echo "error: could not locate sdk-plugin/src/index.ts in any ancestor of $HERE." >&2
  echo "       Set SYNCLER_SDK_SRC to the absolute path of your sdk-plugin/src/index.ts." >&2
  exit 1
fi

mkdir -p dist
npx --yes esbuild src/plugin.ts \\
  --bundle \\
  --format=iife \\
  --global-name=SynclerPluginExports \\
  --platform=browser \\
  --alias:@syncler/plugin-sdk="$SDK_SRC" \\
  --outfile=dist/plugin.bundle.js

echo "Built dist/plugin.bundle.js (aliased @syncler/plugin-sdk -> $SDK_SRC)"
echo "Next: sign with"
echo "  npx tsx \\"$(dirname "$SDK_SRC")/../tools/sign-bundle.ts\\" \\\\"
echo "      dist/plugin.bundle.js ~/.syncler/keys/sender.pem \\\\"
echo "      manifest.json manifest.signed.json"
`;
}

function renderPackageJson(ctx) {
  // @syncler/plugin-sdk is intentionally NOT a dependency here (V0.1
  // — it's not on npm yet). build.sh uses esbuild --alias to resolve
  // imports from the SDK source inside the syncler monorepo. Add the
  // dependency line once @syncler/plugin-sdk is published.
  return JSON.stringify({
    name: ctx.dirName,
    version: '1.0.0',
    private: true,
    type: 'module',
    scripts: {
      build: './build.sh',
    },
    devDependencies: {
      esbuild: '^0.25.0',
      tsx: '^4.7.0',
      typescript: '^5.4.0',
    },
  }, null, 2);
}

function renderReadme(ctx) {
  return `# ${ctx.name}

Generated by \`npm create @syncler/plugin ${ctx.dirName}\`.

- **Renderer**: \`${ctx.renderer}\`
- **Card type**: \`${ctx.cardType}\`
- **Capabilities**: ${ctx.capabilities.length ? ctx.capabilities.map((c) => '`' + c + '`').join(', ') : '_(none declared)_'}

## V0.1 prerequisite

\`@syncler/plugin-sdk\` is not on npm yet. The scaffold expects to live
inside (or alongside) the syncler monorepo: \`build.sh\` walks up parent
directories to find \`sdk-plugin/src/index.ts\` and aliases the import to
that source. Override with \`SYNCLER_SDK_SRC=/abs/path/to/index.ts\` if
your layout differs. Once the SDK is published to npm, drop the
\`--alias\` from \`build.sh\` and \`npm install @syncler/plugin-sdk\`.

## Build → sign → publish

\`\`\`sh
npm install                                  # installs esbuild + tsx + typescript
./build.sh                                   # writes dist/plugin.bundle.js
# Sign the bundle (path to sign-bundle.ts is printed by build.sh).
# Then publish from your sender backend via the syncler Python SDK;
# see examples/trading-bot/ for an end-to-end round-trip example.
\`\`\`

## Edit checklist

- [ ] Replace \`declaredEndpoints\` placeholders with your real backend URLs.
${ctx.renderer === 'template'
    ? `- [ ] Edit \`manifest.template.fields\` to match your payload shape (the host renders a native Compose card from these JSONPaths; \`render()\` is unused).`
    : `- [ ] Replace the \`render()\` body with your domain UI.`}
- [ ] If \`cardType: 'live'\`, make sure \`card_key_path\` resolves to a stable identifier in your payload (see \`docs/integration-guide.md §5.2\`).
- [ ] Wire up an action callback endpoint on your backend. \`docs/integration-guide.md §7\`.

## Docs

- \`docs/integration-guide.md\` — full protocol + plugin authoring guide.
- \`docs/crypto-spec.md\` — canonical bytes for AAD + envelope.
- \`examples/trading-bot/\` — full round-trip plugin + sender example.
`;
}

function pascalCase(s) {
  return s.replace(/(^|[^a-zA-Z0-9])([a-z])/g, (_, _sep, ch) => ch.toUpperCase());
}

main();
