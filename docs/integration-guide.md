# Syncler integration guide

You are integrating your app with the Syncler platform. Syncler is a content-blind transport + plugin host: your backend pushes encrypted blobs to a user's phone, and a JavaScript plugin you wrote renders them and handles taps. **Read this end-to-end before writing code; it's ~7 pages.**

Syncler does not know what your data looks like, what your UI says, or what your buttons do. Your payload, your plugin code, your callback endpoint — Syncler just routes bytes.

## 1. What you're building

Two artifacts:

1. A **JavaScript plugin** that runs in the Syncler Android app's WebView and renders the **detail view** of a card when the user taps it. The plugin defines buttons that POST back to your backend.
2. **Backend code** that pushes a card to the user via the Syncler server, and an HTTP endpoint that receives the action callback.

The Syncler inbox shows a *native* row for every message — sender name, title, subtitle, summary, arrival time. The host draws that row from structured metadata you embed in the payload (`hostPreview`, §2). The plugin's `render()` is reserved for the full-screen detail view that appears when the user taps a row.

### What the host does for you

Once a card is in a user's inbox, the host handles all of the following without you doing anything plugin-side:

- **Native row rendering** from your `hostPreview` block (title / subtitle / summary) plus the server's arrival time (HH:mm for today, MMM d for older).
- **Unread indicator** + read sync across the user's devices. A row marks read when the user opens the detail view; the state syncs via an encrypted CAS state blob.
- **Left navigation drawer** with selectable views: **Unread** (only items the user hasn't opened), **All** (the default), **Archive** (items the user moved out of the main flow), and **Groups** — an expandable list of every paired sender that lets the user filter the inbox down to one sender at a time. The top-bar title reflects the active view.
- **Inbox search** (case-insensitive substring) over title, subtitle, summary, sender name, AND any tokens you supplied in `hostPreview.searchText`.
- **Archive** + **Delete** + **Multi-select** with bulk Archive / Delete. Archive/delete state is local and synced across devices; deleted messages are hidden from inbox AND archive. The server retains messages until their normal expiry.
- **Mute sync** — the user can mute your plugin globally (all devices) or locally (this device only) from the host-owned settings sheet. Muted plugins are hidden from the active inbox but remain accessible in the Senders tab for unmuting.
- **Bottom navigation** (Inbox / Senders / Settings) — the user is always one tap away from your card.
- **Revocation UX** — if you revoke a plugin version with a `compromised` reason, the host refuses to execute the bundle on the device and shows a security banner instead. See §5.5.

You don't need to opt into any of these. Populate `hostPreview` and they all work.

## 2. Protocol

### Payload (your backend -> user device)

Two parts: a reserved **`hostPreview`** block the platform renders natively as the inbox row, and the rest of the payload that your plugin's `render()` consumes for the detail view.

```json
{
  "hostPreview": {
    "title": "Something happened",
    "subtitle": "Account A • 12 minutes ago",
    "summary": "Brief one-sentence context for the row.",
    "searchText": ["account-a", "alert", "threshold"]
  },
  "item_id": "<your-internal-id>",
  "body": "Details for the detail view, used by render()",
  "action_label": "Acknowledge"
}
```

That JSON is your `payload` argument to `client.send_to(...)`. The plugin's `render(payload)` receives the entire dict — including the `hostPreview` block — but it doesn't need to use it (the host already drew the row).

**`hostPreview` contract** (validated by `client.send_to` — invalid blocks raise at send time):

| Field | Required | Type | Cap |
|---|---|---|---|
| `title` | yes | string | 80 UTF-8 bytes |
| `subtitle` | no | string | 120 UTF-8 bytes |
| `summary` | no | string | 240 UTF-8 bytes |
| `searchText` | no | string[] | 16 entries x 64 UTF-8 bytes each |

Total serialized `hostPreview` <= 2048 UTF-8 bytes. Missing block -> the row falls back to *"New message from {sender_name}"*. Senders **should** include it.

`searchText` is folded into the host's inbox search alongside title, subtitle, summary, and sender name. The host does a case-insensitive substring match — populate this with the terms a user would type into the search box but that aren't part of the visible row (ticker symbols, ticket numbers, account references). Plugin-scoped *content* search (a search bar that runs against your backend) is V1.5.

### Action callback (user -> your backend)

When the user taps an action button in your plugin, the plugin POSTs to your declared endpoint with whatever body your `onclick` handler builds:

```
POST https://your-app.example.com/api/action
Content-Type: application/json

{
  "item_id": "...",
  "acted_at": "2026-05-20T14:30:00Z"
}
```

The platform does **not** inject `device_id` into the body in V1 — see §7 for the full contract. Idempotency is your responsibility: dedupe by `item_id` (or a sender-generated identifier you echo back) because the platform may double-deliver under retry.

## 3. Plugin code

`src/plugin.ts`:

```ts
import {
  BasePlugin,
  DismissBehavior,
  registerPlugin,
  type HostPreview,
  type PluginManifest,
} from '@syncler/plugin-sdk';

interface MyPayload {
  // hostPreview is in every payload your plugin receives — the host already
  // drew the inbox row from it, but render() can still use it (e.g. to repeat
  // the title in the detail header).
  hostPreview: HostPreview;
  item_id: string;
  body: string;
  action_label: string;
}

class MyPlugin extends BasePlugin {
  static manifest: PluginManifest = {
    id: 'com.example.myapp',         // your plugin_identifier
    name: 'My App',
    version: '1.0.0',
    senderId: '<your-sender-id>',    // the UUID Syncler issued you
    bundleHash: '<set by sign-bundle.ts>',
    signature: '<set by sign-bundle.ts>',
    declaredCapabilities: ['network'],
    declaredEndpoints: ['https://your-app.example.com/api/*'],
    renderer: 'script',              // or 'template' for native Compose UI
    template: undefined,             // required if renderer is 'template'
    cardType: 'event',               // or 'live' for persistent cards
    cardKeyPath: undefined,          // required if cardType is 'live'
    dismissBehavior: DismissBehavior.DISMISS_ALL,
    minPlatformVersion: '1.0.0',
  };

  // onMessage is NOT invoked in V1 inbox mode. Message arrival is surfaced by
  // the host-rendered row from hostPreview. If you implement it anyway, do not
  // call platform.showNotification; it rejects in V1 inbox mode.

  render(payload: MyPayload): string {
    // V1 recommended pattern: button handler calls `platform.network.fetch`
    // directly. The `platform.message.respond` + `onAction` round-trip is
    // not part of V1 inbox mode. Post directly to your declared endpoint.
    return `
      <div style="font-family:sans-serif;padding:16px">
        <h2>${escapeHtml(payload.hostPreview.title)}</h2>
        <p>${escapeHtml(payload.body)}</p>
        <button id="act" style="font-size:18px;padding:12px">${escapeHtml(payload.action_label)}</button>
      </div>
      <script>
        const itemId = ${JSON.stringify(payload.item_id)};
        document.getElementById('act').onclick = async () => {
          await platform.network.fetch('https://your-app.example.com/api/action', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              item_id: itemId,
              acted_at: new Date().toISOString(),
            }),
          });
          document.getElementById('act').textContent = 'Sent';
        };
      </script>
    `;
  }
}

// Required: self-register at bundle load. Without this call the host's
// dispatcher has no plugin to dispatch into, and you'll see a "missing
// registerPlugin() call?" error in the card.
registerPlugin(new MyPlugin());
```

Render whatever HTML/CSS/JS suits your domain. The WebView is isolated; only `platform.*` APIs cross the boundary.

**V1 card-render bridge:**
- `platform.network.fetch(url, init)` — works, gated by `declaredEndpoints` glob match
- `platform.showNotification`, `platform.storage`, `platform.camera`, `platform.gallery`, `platform.file`, `platform.location`, `platform.message.respond` — *reject* with a clear error in V1 inbox mode. For V1 dogfood, stick to `network.fetch`.

## 4. Build + sign the plugin

```sh
# Install the SDK
cd my-plugin
npm install @syncler/plugin-sdk

# Build — IIFE format, NOT ESM. The host loads the bundle inside a regular
# `<script>` tag, which can't parse `export` statements.
npx esbuild src/plugin.ts \
  --bundle \
  --format=iife \
  --global-name=SynclerPluginExports \
  --platform=browser \
  --outfile=dist/plugin.bundle.js

# Sign it (positional args, tsx is required for the TS tool):
npx tsx node_modules/@syncler/plugin-sdk/tools/sign-bundle.ts \
  dist/plugin.bundle.js \
  ~/.syncler/keys/sender.pem \
  manifest.json \
  manifest.signed.json
```

The tool signs the canonical-manifest-with-bundleHash-bytes (the exact bytes the Android host verifies against — see `docs/crypto-spec.md` §5). `manifest.signed.json` will contain populated `bundleHash` + `signature` fields.

Host the bundle file at a stable HTTPS URL (CDN, S3, GitHub raw). For local development, debug builds of the Android app also accept plain `http://` URLs (e.g. a Python `-m http.server` on your LAN). Release builds require HTTPS.

## 5. Publishing the plugin (your backend, one time per version)

```python
from syncler import Client
import hashlib, json

client = Client(
    sender_name="My App",
    private_key_path="~/.syncler/keys/sender.pem",
    base_url="https://api.syncler.app",
)
client.set_sender_id("<the sender_id Syncler issued you>")

bundle = open("dist/plugin.bundle.js", "rb").read()
manifest = json.loads(open("manifest.signed.json").read())

response = client.publish_plugin(
    plugin_identifier="com.example.myapp",
    version="1.0.0",
    manifest_hash=hashlib.sha256(json.dumps(
        {k: v for k, v in manifest.items() if k != "signature"},
        sort_keys=True, separators=(",", ":")
    ).encode()).digest(),
    bundle_hash=hashlib.sha256(bundle).digest(),
    bundle_signature=bytes.fromhex(manifest["signature"]),
    signed_bundle_url="https://your-app.example.com/syncler-plugin.bundle.js",
    renderer="script",
    template=None,
    card_type="event",
    card_key_path=None,
    capabilities=["network"],
    endpoints=["https://your-app.example.com/api/*"],
)

print("Published row:", response["plugin_row_id"])
# Save this — you'll need it in `send_to(plugin_id=...)`.

### 5.1 Native Template Renderer

If you set `renderer="template"`, the host uses a native Material 3 Compose renderer instead of a WebView. This is faster and uses less battery. You must supply a `template` object:

```json
{
  "layout": "standard_card",
  "fields": {
    "title":    { "path": "$.title" },
    "subtitle": { "path": "$.subtitle" },
    "body":     { "path": "$.body" }
  },
  "actions": [
    { "id": "ack", "label": "Acknowledge", "endpoint": "https://example.com/api/ack" }
  ]
}
```

- **JSONPath mapping**: Fields are extracted from your decrypted payload using simple `$.path` syntax.
- **Standard Layout**: `standard_card` supports `title` (required), `subtitle`, and `body`.
- **Native Actions**: Buttons in the card POST your payload to the specified `endpoint`. Endpoints must be in your `declaredEndpoints`.

### 5.2 Live Cards

If you set `card_type="live"`, the host treats the card as a persistent, upsertable unit rather than a one-off event. You must provide `card_key_path` (JSONPath into your payload yielding a stable ID for the card).

#### Upserting a live card

Use `upsert_card` to create or update a card. The host performs a sequence number check to prevent stale updates.

```python
client.upsert_card(
    user_id=user_id,
    plugin_id=plugin_row_id,
    card_key="order-456",
    encrypted_payload=client.encrypt_payload({
        "title": "Pizza Delivery",
        "body": "Out for delivery! ETA: 8 mins"
    }),
    nonce=os.urandom(12),
    sequence_number=2,
    expires_at=datetime.now(UTC) + timedelta(hours=48),
)
```

- **Sequence Number**: Must be strictly increasing. The server and client both reject updates with `sequence_number <= current`.
- **Rate Limit**: 1 upsert per second per card (60/min). Exceeding this returns 429.
- **TTL**: Live cards expire automatically after `expires_at`. Maximum allowed TTL is 48 hours.

#### AAD binding

For security, live card upserts bind metadata to the encrypted payload using AES-GCM Additional Authenticated Data (AAD). Your SDK handles this, but for reference, the following fields are bound:
`card_key`, `card_type: "live"`, `sender_id`, `user_id`, `plugin_id`, `expires_at`, `sequence_number`.

#### Deleting a live card

```python
client.delete_card(card_key="order-456")
```

### 5.5 Revoking a plugin version

Revoke a single published plugin row when that exact version should be treated as inactive:

```python
client.revoke_plugin(
    plugin_row_id="<plugin_row_id from publish_plugin>",
    reason="superseded",
)
```

`reason` is optional for legacy compatibility, but senders should always supply one of:

| Reason | Use when | Host UX |
|---|---|---|
| `superseded` | A newer version replaces this row. | Shows a subdued "newer version available" banner in detail; the historical bundle can still render. |
| `compromised` | The bundle or signing key is unsafe. | Refuses to execute the bundle and shows a security banner with the decrypted payload fallback. |
| `sender_disabled` | The sender/service is intentionally disabled. | Shows a neutral "sender unavailable" banner in detail; the historical bundle can still render. |
| `unspecified` | You are migrating legacy tooling and cannot classify the revoke. | Shows a generic revoked banner; avoid using this for new revokes. |

The revoke request is signed by the SDK. The `reason` value is included in the signed envelope, so an intermediary cannot strip `compromised` down to a softer classification. Re-revoking a row keeps the original `revoked_at`; a higher-severity reason can promote the row, while downgrades are ignored.

## 6. Sending a card (your backend, per event)

```python
# After pairing — see SDK README for the pairing dance.
client.set_pairing(user_id="...", pairing_key=b"...32-bytes...")

result = client.send_to(
    user_uuid=user_uuid,
    plugin_identifier="com.example.myapp",
    plugin_id="<plugin_row_id from publish_plugin>",
    payload={
        "hostPreview": {
            "title": "Something happened",
            "subtitle": "Account A",
            "summary": "Brief one-sentence context.",
            "searchText": ["account-a", "alert"],
        },
        "item_id": "abc-123",
        "body": "Details for the detail view.",
        "action_label": "Acknowledge",
    },
    min_plugin_version="1.0.0",
)
```

`ttl_seconds` defaults to 7 days. The server rejects envelopes that are already expired or more than 30 days in the future.

## 7. The action callback endpoint

`https://your-app.example.com/api/action` accepts whatever body your plugin's `onclick` handler POSTs. In the example above that's:

```http
POST /api/action
Content-Type: application/json

{ "item_id": "...", "acted_at": "..." }
```

Note: the platform does **not** inject `device_id` into the body for you in V1. If you need to distinguish which device acted, include a sender-generated identifier in your payload and have the plugin echo it back. Dedupe by `item_id` alone if a single tap is what you care about.

- Status 200 -> recorded.
- Status 409 -> idempotent dedupe (already saw this item); platform may retry on transient network errors so be ready.
- Status 4xx other -> platform surfaces a generic failure to the plugin's promise.

```python
# Flask example — dedupe by item_id alone in V1.
@app.post("/api/action")
def action():
    data = request.json
    item_id = data["item_id"]
    if Action.query.filter_by(item_id=item_id).first():
        return "", 409
    db.session.add(Action(item_id=item_id, acted_at=data["acted_at"]))
    db.session.commit()
    return "", 200
```

## 8. Testing it end-to-end

1. Install the Syncler Android app (`./gradlew :app:installDebug`).
2. Sign up + log in. The app auto-enrolls this device with the server.
3. From your backend, run a script that registers your sender and prints a pairing QR (see SDK README).
4. Tap **Pair sender** -> **Scan QR** in the Syncler app. Confirm the fingerprint matches what your script prints.
5. After confirming, the app shows your `user_id` and a `pairing_key_hex`. Copy both and feed them into your backend's `client.set_pairing(user_id, pairing_key=bytes.fromhex(...))` so it can encrypt for you.
6. Publish your plugin (`client.publish_plugin(...)`) — save the `plugin_row_id`.
7. Run `client.send_to(...)` with a test payload.
8. Within ~15s the phone's inbox poll picks it up, decrypts it, fetches your bundle, and shows the native inbox row.
9. Tap the row to open the plugin-rendered detail view, then tap your action button.
10. Confirm your `/api/action` endpoint received the POST.

## 9. Common errors

- **`401 invalid envelope signature`** — your sender's Ed25519 key doesn't match the one registered. Re-check `private_key_path`.
- **`410 plugin missing, revoked, or not owned by sender`** — `plugin_id` (the row UUID) is wrong, or you accidentally used `plugin_identifier`. The two are distinct (see §5 vs §6).
- **`410 recipient has no active devices`** — the user has no enrolled device (signed up but never opened the app, or revoked all devices). FCM availability is *not* required; messages are stored regardless and pulled via `/v1/messages/inbox`.
- **`409 nonce already used`** — your code reused a nonce somehow; SDK generates fresh nonces per `send_to`, so this means you're calling `send_to` with cached envelope bytes. Rebuild.
- **`429 rate limited`** — back off + retry per `Retry-After` header.
- **Card shows "render failed: plugin did not install __syncler_internal_dispatch — missing registerPlugin() call?"** — your bundle defined the plugin class but didn't call `registerPlugin(new YourPlugin())` at module scope. See §3.
- **Card shows "endpoint not declared"** — your `onclick` handler is calling a URL that doesn't match any pattern in your manifest's `declaredEndpoints`. Add the URL pattern (globs allowed: `https://example.com/api/*`) and re-publish.
- **`HostPreviewValidationError: hostPreview.X is N UTF-8 bytes; max is M`** — caught at `client.send_to` time. Trim the offending field; see §2 for the caps. UTF-8 byte counts, not characters — emoji and accented characters cost more than one byte.
- **Row shows "New message from {sender}" with fallback text only** — your message was sent without a `hostPreview` block, or the block was malformed (logged + ignored on the device). The detail view still loads the plugin and renders normally; only the row is generic. Add a valid `hostPreview` and re-send.

Cross-reference `docs/crypto-spec.md` for the AAD + envelope canonical byte shapes if signatures disagree.

## 10. Versioning + history

Publish every plugin release as a new row under the same `plugin_identifier`. `plugin_identifier` is the stable name, while `plugin_row_id` is the exact version row returned by `client.publish_plugin(...)`.

Version strings use semver-lite: `MAJOR.MINOR.PATCH` with an optional prerelease suffix. Each numeric component is capped at 6 digits, and every publish for the same `(sender_id, plugin_identifier)` must be strictly greater than all existing versions.

When you send a card, pass the exact `plugin_row_id` that matches the payload schema you produced. The message stores that row UUID. On the device, Syncler resolves the historical row by ID, not by `/latest`, so a v1 message keeps using the v1 bundle even after you publish v2.

The device verifies the downloaded bundle against the row's SHA-256 `bundle_hash` and caches verified bundle bytes by hash for the process lifetime. On app restart, the cache is empty and the device re-fetches the same historical row's `signed_bundle_url`. Keep old bundle URLs available for as long as you want old inbox messages to remain renderable.

Archive/delete are user-organization states synced through the encrypted user-state blob. They do not remove the server copy, and archive does not store a local message body beyond the server's retention window.

## 11. Lost or compromised devices

If a user loses a device, they should follow this three-step procedure to protect their data:

1. **Revoke the device**: Go to **Settings -> Devices** on a trusted device and revoke the lost one. This immediately kills its network access and SSE stream.
2. **Rotate keys**: For any sender that handled sensitive data, the user should **re-pair** on a trusted device. This generates a new 32-byte AES key for that sender, ensuring that any future messages from them cannot be decrypted by the lost device (even if it were to come back online).
3. **Data Loss**: Past cached data on the lost device remains encrypted under the user's master key, but if the device itself is compromised and the master key extracted, that historical data is unrecoverable. Syncler does not support remote wipe.

---

**Seven pages.** If you needed more than that to integrate, the SDK has a DX problem — file a finding back at the Syncler team.
