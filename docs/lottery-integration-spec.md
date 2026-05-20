# Lottery → Syncler integration spec

You are integrating a lottery app with the Syncler platform. The user wants to receive lottery number batches on their phone, mark them as "played" with a tap, and have your backend learn about that tap. **Read this end-to-end before writing code; it's ~6 pages.**

## 1. What you're building

Two artifacts:

1. A **JavaScript plugin** (`LotteryPlugin`) that runs in the Syncler Android app's WebView and (a) renders incoming lottery number cards, (b) defines a "Played" button that posts back to your backend.
2. **Backend code** that pushes a card to the user via the Syncler server, and an HTTP endpoint that receives the "played" callback.

The Syncler platform is content-blind: it transports encrypted opaque blobs and hosts your plugin. Your data, your UI, your action handlers — Syncler just gets it from your backend onto the user's phone.

## 2. Protocol

### Payload (your backend → user device)

```json
{
  "batch_id": "<your-internal-batch-id>",
  "draw_date": "2026-06-01",
  "numbers": [[1, 12, 23, 34, 45], [3, 14, 25, 36, 47]]
}
```

That JSON is your `payload` argument to `client.send_to(...)`. Schema is yours.

### Action callback (user → your backend)

When the user taps "Played" in the lottery plugin, the plugin POSTs to your declared endpoint:

```
POST https://lottery.app/api/played
Content-Type: application/json

{
  "batch_id": "...",
  "played_at": "2026-05-20T14:30:00Z",
  "device_id": "<user's device UUID>"
}
```

Idempotency is your responsibility: dedupe by `(batch_id, device_id)` because the platform may double-deliver under retry. Q11 in the design pinned this to plugin/sender, not platform.

## 3. Plugin code

`src/plugin.ts`:

```ts
import { BasePlugin, DismissBehavior, type PluginManifest } from '@syncler/plugin-sdk';

interface LotteryPayload {
  batch_id: string;
  draw_date: string;
  numbers: number[][];
}

export class LotteryPlugin extends BasePlugin {
  static manifest: PluginManifest = {
    id: 'com.lottery.app',          // your plugin_identifier
    name: 'Lottery',
    version: '1.0.0',
    senderId: '<your-sender-id>',   // the UUID Syncler issued you
    bundleHash: '<set by sign-bundle.ts>',
    signature: '<set by sign-bundle.ts>',
    declaredCapabilities: ['network'],
    declaredEndpoints: ['https://lottery.app/api/*'],
    dismissBehavior: DismissBehavior.DISMISS_ALL,
    minPlatformVersion: '1.0.0',
  };

  async onMessage(payload: LotteryPayload) {
    return platform.showNotification({
      title: 'Lottery numbers ready',
      body: `${payload.numbers.length} sets for draw ${payload.draw_date}`,
      importance: 'default',
    });
  }

  render(payload: LotteryPayload): string {
    const rows = payload.numbers
      .map(set => `<li>${set.join('-')}</li>`)
      .join('');
    return `
      <div style="font-family:sans-serif;padding:16px">
        <h2>Lottery ${payload.draw_date}</h2>
        <ol>${rows}</ol>
        <button id="played" style="font-size:18px;padding:12px">Played</button>
      </div>
      <script>
        document.getElementById('played').onclick = async () => {
          await platform.message.respond('played', {
            batch_id: ${JSON.stringify(payload.batch_id)},
          });
        };
      </script>
    `;
  }

  async onAction(actionName: string, payload: { batch_id: string }) {
    if (actionName !== 'played') return;
    await platform.network.fetch('https://lottery.app/api/played', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        batch_id: payload.batch_id,
        played_at: new Date().toISOString(),
        // device_id is added by the Syncler runtime — see SDK reference §4
      }),
    });
  }
}
```

## 4. Build + sign the plugin

```sh
# Install the SDK
cd lottery-plugin
npm install @syncler/plugin-sdk

# Build (esbuild produces a single ESM bundle)
npm run build  # → dist/plugin.bundle.js

# Sign it. You need an Ed25519 private key for your sender.
node node_modules/@syncler/plugin-sdk/tools/sign-bundle.js \
  --bundle dist/plugin.bundle.js \
  --private-key ~/.syncler/keys/lottery.pem \
  --manifest manifest.json \
  --output manifest.signed.json
```

`manifest.signed.json` now contains `bundleHash` + `signature` populated. Host the bundle file at a stable HTTPS URL (CDN, S3, GitHub raw).

## 5. Publishing the plugin (your backend, one time per version)

```python
from syncler import Client
import hashlib, json

client = Client(
    sender_name="Lottery",
    private_key_path="~/.syncler/keys/lottery.pem",
    base_url="https://api.syncler.app",
)
client.set_sender_id("<the sender_id Syncler issued you>")

bundle = open("dist/plugin.bundle.js", "rb").read()
manifest = json.loads(open("manifest.signed.json").read())

response = client.publish_plugin(
    plugin_identifier="com.lottery.app",
    version="1.0.0",
    manifest_hash=hashlib.sha256(json.dumps(
        {k: v for k, v in manifest.items() if k != "signature"},
        sort_keys=True, separators=(",", ":")
    ).encode()).digest(),
    bundle_hash=hashlib.sha256(bundle).digest(),
    bundle_signature=bytes.fromhex(manifest["signature"]),
    signed_bundle_url="https://lottery.app/syncler-plugin.bundle.js",
    capabilities=["network"],
    endpoints=["https://lottery.app/api/*"],
)

print("Published row:", response["plugin_row_id"])
# Save this — you'll need it in `send_to(plugin_id=...)`.
```

## 6. Sending a batch (your backend, per draw)

```python
# After pairing — see SDK README for the pairing dance.
client.set_pairing(user_id="...", pairing_key=b"...32-bytes...")

result = client.send_to(
    user_uuid=user_uuid,
    plugin_identifier="com.lottery.app",
    plugin_id="<plugin_row_id from publish_plugin>",
    payload={
        "batch_id": batch_id,
        "draw_date": "2026-06-01",
        "numbers": [[1, 12, 23, 34, 45], [3, 14, 25, 36, 47]],
    },
    min_plugin_version="1.0.0",
)
```

## 7. The action callback endpoint

`https://lottery.app/api/played` accepts:

```http
POST /api/played
Content-Type: application/json

{ "batch_id": "...", "played_at": "...", "device_id": "..." }
```

- Status 200 → recorded.
- Status 409 → idempotent dedupe (already saw this `(batch_id, device_id)`); platform retries on transient network errors so be ready.
- Status 4xx other → platform shows the user a generic failure.

```python
# Flask example
@app.post("/api/played")
def played():
    data = request.json
    key = (data["batch_id"], data["device_id"])
    if Played.query.filter_by(batch_id=key[0], device_id=key[1]).first():
        return "", 409
    db.session.add(Played(batch_id=key[0], device_id=key[1], played_at=data["played_at"]))
    db.session.commit()
    return "", 200
```

## 8. Testing it end-to-end

1. Install the Syncler Android app (`./gradlew :app:installDebug`).
2. Sign up + log in.
3. From your laptop, run the lottery bot to print a pairing QR.
4. Scan the QR in the Syncler app. Confirm the fingerprint matches what the bot prints.
5. Run `client.send_to(...)` with a test batch.
6. Tap "Played" on the phone.
7. Confirm your `/api/played` endpoint received the POST.

## 9. Common errors

- **`401 invalid envelope signature`** — your sender's Ed25519 key doesn't match the one registered. Re-check `private_key_path`.
- **`410 plugin missing, revoked, or not owned by sender`** — `plugin_id` (the row UUID) is wrong, or you accidentally used `plugin_identifier`. The two are distinct (see §5 vs §6).
- **`410 recipient has no active device with FCM token`** — user uninstalled the plugin or revoked the pairing.
- **`409 nonce already used`** — your code reused a nonce somehow; SDK generates fresh nonces per `send_to`, so this means you're calling `send_to` with cached envelope bytes. Rebuild.
- **`429 rate limited`** — back off + retry per `Retry-After` header.

Cross-reference `docs/crypto-spec.md` for the AAD + envelope canonical byte shapes if signatures disagree.

---

**Six pages.** If you needed more than that to integrate, the SDK has a DX problem — file a finding back at the Syncler team.
