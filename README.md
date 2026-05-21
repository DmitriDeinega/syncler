# Syncler

A multi-device, end-to-end encrypted, plugin-host platform. External programs push structured messages to a user's Android phone(s); each sender ships its own JavaScript plugin that defines its data, UI, and actions. The platform stays content-blind.

This repo contains the V1 build.

## Layout

```
syncler/
  server/           Python (FastAPI + Postgres) — opaque message bus, broker, plugin registry
  android/          Kotlin (Compose, multi-module) — host app + secure WebView plugin runtime
  sdk-plugin/       TypeScript SDK plugin authors inherit (BasePlugin) + bundle signer
  sdk-python/       Python sender SDK (the canonical sender impl)
  examples/         Runnable examples (trading-bot)
  docs/             crypto-spec.md (the canonical crypto contract)
                    integration-guide.md (how any sender integrates with Syncler)
  .triad/           Design history (planning + reviews) — committed for context
```

## The triad

This codebase was designed and built by a three-model triad:

- **Claude** (Anthropic) — orchestrator + builder (after Codex hit a usage limit at M5 the user shifted lead to Claude)
- **Codex** (OpenAI) — initial build lead for M1–M4b; reviewer thereafter
- **Gemini** (Google) — reviewer throughout

`.triad/` contains the full design Q&A across 10 rounds, the build plan, and every code-review round (typically Claude + Codex + Gemini each commenting on the same diff). Reviews caught real bugs at every milestone — see `.triad/27-review-synthesis.md` and any `*-review.txt` file.

## Architecture, briefly

- **Server is content-blind.** Every byte of message content is encrypted with a per-pairing AES-256-GCM key derived client-side from a 256-bit master key. The master key is wrapped by an Argon2id-derived key from the user's password (Bitwarden/ProtonMail zero-knowledge pattern). The server stores only: account hashes, encrypted master key blobs, encrypted message bodies, encrypted user state, plugin metadata, and signing keys (public only).
- **Each sender writes its own plugin.** Senders are HTTP backends (your trading bot, your lottery app). They publish a signed JavaScript plugin to the platform, then push messages encrypted to a paired user. The user's phone runs the plugin in a hardened, isolated WebView, where it renders + handles user actions.
- **Multi-device** with optimistic-concurrency state sync. User accounts unlock from any device with email + password; the per-device plugin install state, pairings, and dismissal state sync as an encrypted blob.
- **Cryptographic identity for senders** via Ed25519. Every message envelope is signed; every plugin bundle is signed. The phone locks the sender's public-key fingerprint + name + name hash at pairing time, so impersonation is prevented at multiple layers.

Full crypto details: [docs/crypto-spec.md](docs/crypto-spec.md).

## Quickstart

### Run the server

```sh
cd server
docker compose up -d    # postgres
uv sync --extra dev
alembic upgrade head
uvicorn app.main:app --reload
pytest                  # M1.8 integration suite
```

### Build the Android app

```sh
cd android
# Generate the gradle wrapper first if not yet committed:
gradle wrapper
./gradlew :app:assembleDebug
# Push to a connected device:
./gradlew :app:installDebug
```

Android targets minSdk 26 (Android 8.0+). FCM (`google-services.json`) must be added before push delivery works end-to-end; without it the server runs FCM in dev-mode no-op and the app sees no pushes.

### Write a sender (Python)

```sh
pip install -e ./sdk-python

python - <<'PY'
from syncler import Client
client = Client(
    sender_name="My Bot",
    private_key_path="~/.syncler/keys/mybot.pem",
    base_url="http://localhost:8000",
)
sender_id = client.register_if_needed(contact="me@example.com")
print("sender_id =", sender_id)
PY
```

See [sdk-python/README.md](sdk-python/README.md) for the full pairing + send + publish flow.

### Write a plugin (JavaScript)

```sh
cd sdk-plugin
npm install
npm run build
# Build the minimal example:
cd examples/minimal && ./build.sh
```

End-to-end integration guide for senders: [docs/integration-guide.md](docs/integration-guide.md).

## Status

V1 build is complete across all server-side milestones (M1–M9) plus the supporting Android infrastructure. What's NOT yet in V1 (intentional deferrals tracked through review rounds):

- **Gradle wrapper** is not committed. `gradle wrapper` once locally before `./gradlew` works.
- **`PluginUpdateChecker` background worker** + **`PluginUpdatesScreen` Compose UI** — the data model + version comparator are in `:core:storage` (M8), but the WorkManager + Material3 polish is M11 finish-up.
- **`UserStateSyncer`** — the pull-merge-CAS-push loop. The merger + state model exist (M7); wiring needs the master key plumbed through the foreground service (V1 final pass).
- **`DismissEventHandler`** for Gap 1 cross-device dismiss fan-out. Server pushes the FCM data message (M5/M7); device-side handler is M11.
- **`wait_for_pairing` in the Python SDK** raises NotImplementedError — needs a server-side "did this pairing token complete?" poll endpoint.
- **Per-key user-scoped storage sync** with timestamps/tombstones — deferred to V1.5; documented data-loss risk.
- **QR camera scanning** in pairing — V1 uses paste-URL flow; camera is M11.
- **Marketplace** for plugin distribution — V1 uses sideload (URL install). Marketplace is V1.5.
- **iOS** — Android-only V1, per user G2 commitment. iOS deferred until traction.

## Triad protocol notes

The build proceeded under a "lead-builds-others-review" protocol with formal review rounds after each milestone. Every fix-up commit (`M*.1`, `M*.2`) is the response to one or more reviewer findings; the `.triad/*-review.txt` files record what each reviewer caught. Notable saves:

- Codex caught the AAD/envelope contract mismatch at M5 (server-generated fields couldn't be signed by sender). Triggered the V1.1 crypto-spec revision.
- Codex caught the addressed plugin-row-id vs plugin-identifier conflation at M8 (upgrades were impossible).
- Codex caught the unauthenticated revoke endpoint at M8 (anyone with a UUID could nuke a plugin).
- Codex caught the SDK UUID canonicalization gap at M9 (uppercase UUIDs would silently fail signature verification).
- Gemini consistently flagged data-loss risks in merge strategies (M7 userScopedStorage).
- The protocol formalized two parallel reviewers because three trained-on-similar-data models share blind spots; Codex+Gemini found different things.

## License

Not yet chosen.
