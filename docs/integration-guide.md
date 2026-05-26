# Syncler integration guide

You are integrating your app with the Syncler platform. Syncler is a content-blind transport + plugin host: your backend pushes end-to-end-encrypted blobs to a user's phone, and your plugin renders them. **Read this end-to-end before writing code; it's ~9 pages.**

Syncler does not know what your data looks like, what your UI says, or what your buttons do. Your payload, your plugin code (or template manifest), your callback endpoint — Syncler just routes bytes.

## 1. What you're building

You pick one of two render modes when you publish:

- **Script** (`renderer: "script"`) — a JavaScript bundle running inside a sandboxed WebView. Maximum flexibility; you ship a `render(payload)` function that returns HTML. Buttons POST through `platform.network.fetch`.
- **Template** (`renderer: "template"`) — no JavaScript. You ship a small JSON manifest that maps fields out of your payload via JSONPath (`$.title`, `$.body.summary`) into a native Material 3 card. Buttons are host-rendered and the host POSTs the decrypted payload to the action's declared endpoint. Faster, lighter, no JS attack surface; constrained to a fixed set of layouts.

And one of two delivery modes:

- **Event** (`card_type: "event"`) — the default. Each `send_to` produces a new immutable inbox row. Good for notifications, alerts, transactional events.
- **Live** (`card_type: "live"`) — persistent, upsertable. Subsequent upserts to the same `card_key` replace the previous payload (highest `sequence_number` wins). Good for status cards that change over time (delivery progress, market position, sensor reading). Server-enforced 48h TTL; you re-upsert to extend.

In every case you also need **backend code** that publishes the plugin once and sends payloads on demand, plus (for script renderer) an HTTP endpoint that receives the action callback.

The Syncler inbox shows a *native* row for every message — sender name, title, subtitle, summary, arrival time — pulled from the `hostPreview` block you embed in your payload (§2). The detail view that opens on tap is what your script bundle's `render()` or your template manifest produces.

**Pairing** — V1 has the user copy `user_id` + `pairing_key_hex` from the device into your sender's CLI by hand. V1.5 (the default going forward) replaces the copy step with an encrypted POST from the device to your sender's broker. The user only sees the fingerprint-confirm step; the rest is automatic. See §8.5 for setup.

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
- **Live cards pin above events.** When mixed in the same inbox, live cards (`card_type: "live"`) sort above event cards (`card_type: "event"`), each subsorted by recency. The "Live" view in the drawer shows only live cards.
- **Per-device dismiss (server-side filter).** When the user dismisses a message on device A, `/v1/messages/{id}/dismiss` writes a `DeliveryStatus` row keyed on `(message_id, device_id=A)`. Subsequent calls to `/v1/messages/inbox` from device A LEFT JOIN `DeliveryStatus` for `device_id=A` and filter rows where `dismissed_at IS NOT NULL` out of A's feed. The dismiss SSE event still fans out to *all* of the user's devices, but the server-side filter is per-device — devices B, C, etc. receive the hint and refresh, but their own feeds keep the row until they dismiss it themselves. Dismiss is a per-device gesture by design — some users want a card visible elsewhere; a cross-device "dismiss everywhere" toggle is on the roadmap.
- **Real-time hints over SSE.** Foreground devices keep an authenticated Server-Sent Events stream open to `/v1/events`. The server pushes content-blind hints — `inbox.changed`, `state.changed`, `dismiss`, `card.upsert`, `card.delete` — and the client re-fetches over REST in response. The actual payloads still flow over the existing pull endpoints; SSE is the wakeup signal, not the data channel. Backgrounded devices fall back to FCM.
- **Live channel.** Plugins can open a long-lived authenticated WebSocket via `platform.live.connect(channel)` for sub-second push from the sender to the device + outbound `send` from the device. The host owns the WS lifecycle (heartbeat, reconnect, rate limit, revocation-driven close); the server is a routing pipe — frames carry your V2-encrypted envelopes opaquely. Plugins receive incoming frames via the `onLiveMessage(channel, envelopeBase64)` hook on `BasePlugin`. See §12.
- **Field-level live-card patches.** Persistent live cards can be updated one field at a time with `client.patch_card(card_id, patches=[(field, value)])` — no whole-card re-encryption per tick. The host applies the patch on top of the existing card payload atomically; failed/missing patches in the chain fall back to the last full upsert. See §13.
- **Guided lost-device recovery.** The Settings → Security → "I lost a device" flow walks the user through device revoke → master-key rotation → re-pair handoff as a single security-recovery wizard. See §11.
- **Per-plugin user preferences.** The user can mute your plugin, override the displayed label, set notification cadence (realtime / 15-min / hourly / daily digest), and configure quiet hours — all without any plugin-side code. The OS notification is gated; the inbox row still updates so the user sees the missed event on next open. See §14.

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
  Capability,
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
    declaredCapabilities: [Capability.NETWORK],  // enum, not the string literal
    declaredEndpoints: ['https://your-app.example.com/api/*'],
    renderer: 'script',              // or 'template' for native Compose UI
    cardType: 'event',               // or 'live' for persistent cards
    // template / cardKeyPath are omitted here: required only when renderer
    // is 'template' / cardType is 'live'. The validator rejects them when
    // they don't belong, so leave them unset for an event-mode script
    // plugin like this one.
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
- `platform.network.fetch(url, init)` — works, gated by `declaredEndpoints` glob match. Contract documented in §3.1 below.
- `platform.showNotification`, `platform.storage`, `platform.camera`, `platform.gallery`, `platform.file`, `platform.location`, `platform.message.respond` — *reject* with a clear error in V1 inbox mode. For V1 dogfood, stick to `network.fetch`.

### 3.1 `declaredEndpoints` glob syntax

`declaredEndpoints` controls which URLs your plugin may pass to `platform.network.fetch`. The host (both Android `EndpointMatcher.kt` and the TS SDK's `network.ts`) implements the same glob grammar:

- A literal `*` matches **exactly one segment**, never multiple. The boundary changes by position:
  - In the host portion of the URL (before the first `/` after the scheme), `*` matches `[^./]*` — a single host label. `https://*.example.com/feed` matches `https://api.example.com/feed`, but **not** `https://a.b.example.com/feed`.
  - In the path portion, `*` matches `[^/]*` — a single path segment. `https://example.com/api/*` matches `https://example.com/api/v1`, but **not** `https://example.com/api/v1/users`.
- There is no `**` for "any number of segments." You need one entry per depth, or you need to make the path component a single segment (e.g. encode trailing path in a query string).
- Match is anchored: the full URL must match, including query string. To allow a query, include `*` in the path or query position.

Example: to allow both `/api/v1/ack` and `/api/v2/ack`, declare both: `["https://example.com/api/v1/*", "https://example.com/api/v2/*"]`. Or, if you don't care about path depth, declare `["https://example.com/api/*", "https://example.com/api/*/*"]` and so on.

This is the single most common manifest mistake — a plugin author declares `https://example.com/api/*` and then `fetch`-es `https://example.com/api/v1/users`, which gets rejected with `endpoint_not_declared`. The same pattern controls **template action endpoints** (the publish-time validator uses the same matcher to verify `action.endpoint` is in `endpoints`).

### 3.2 `platform.network.fetch` contract

The host bridge wraps a stock OkHttp client. Behavior:

- **Returns a `Response`** for any HTTP response (2xx, 4xx, 5xx all resolve). The status is on `response.status`. You're responsible for checking it.
- **Throws** on:
  - `endpoint_not_declared` — URL doesn't match any `declaredEndpoints` glob (see §3.1).
  - `cleartext_in_release` — `http://` URL in a release build. Debug builds allow cleartext for LAN dev.
  - Network failures (DNS, connection refused, timeout) propagate as an `Error` whose message includes the underlying cause; treat them like a generic offline state.
- **Timeouts**: two layers. The host's OkHttp client uses library defaults (~10s connect / 10s read each). The plugin bridge wraps every call in a hard 30s ceiling — even if the underlying HTTP request is still streaming, the bridge cancels and rejects the JS promise after 30 seconds. Plan UX around the 30s upper bound; if your endpoint is reliably slower, you need a redesign (e.g. async ack endpoint that the plugin polls).
- **Request shape**: standard `RequestInit` subset. `method`, `headers`, `body` (string), `contentType` are forwarded. No streaming bodies, no `AbortController` in V1.
- **Cookies are disabled** at the bridge (`CookieJar.NO_COOKIES`). Authenticate via headers or signed bodies.

```ts
try {
  const response = await platform.network.fetch('https://example.com/api/ack', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ item_id: itemId }),
  });
  if (!response.ok) {
    // 4xx/5xx — your backend rejected the action. Show an error in the card.
    return;
  }
  // 2xx — success.
} catch (err) {
  // endpoint not declared, cleartext in release, offline, timeout, etc.
  // The exception message is human-readable; surface it or fall back to a
  // generic "couldn't reach server" state.
}
```

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
```

### 5.1 Native Template Renderer

Set `renderer="template"` to ship a Compose-rendered native card instead of a JS bundle. The publish call looks like this:

```python
response = client.publish_plugin(
    plugin_identifier="com.example.weather",
    version="1.0.0",
    manifest_hash=hashlib.sha256(b"manifest-placeholder").digest(),
    bundle_hash=hashlib.sha256(b"unused-for-template").digest(),
    bundle_signature=b"\x00" * 64,            # ignored for template
    signed_bundle_url="https://example.com/unused",  # ignored for template
    capabilities=[],
    endpoints=["https://example.com/api/*"],
    renderer="template",
    template={
        "layout": "standard_card",
        "fields": {
            "title":    {"path": "$.title"},
            "subtitle": {"path": "$.subtitle"},
            "body":     {"path": "$.body"},
        },
        "actions": [
            {"id": "ack", "label": "Acknowledge", "endpoint": "https://example.com/api/ack"},
        ],
    },
)
```

**JSONPath dialect.** Only `$.field(.subfield)*` is accepted. No array indexing (`$.items[0]`), no wildcards (`$.*`), no filters (`$.items[?(@.x>0)]`). Field-name segments must match `[A-Za-z_][A-Za-z0-9_]*`. The server rejects manifests with malformed paths at publish time, and the client-side resolver returns `null` for malformed input as a defense-in-depth.

**Layouts.** V1 ships one layout:

| Layout | Required fields | Optional fields | Notes |
|---|---|---|---|
| `standard_card` | `title` | `subtitle`, `body` | All values rendered as plain text via Compose `Text`. No markdown / HTML / link interpretation — a hostile payload value cannot inject a clickable link. |

Additional layouts (`compact_row`, `score_card`, etc.) are deferred to a future phase.

**Actions.** Each action is a button on the card. When tapped, the host POSTs the **full decrypted payload as `application/json`** to `endpoint`. No `Authorization` header is attached — the host explicitly uses an unauthenticated HTTP client for action POSTs so the user's Syncler bearer token never reaches your plugin endpoint (security boundary). If you need request authentication, HMAC the body using a sender-specific secret your backend knows out of band, or rely on TLS + endpoint allowlisting alone.

**Validation rules** the publish endpoint enforces (returns 422 otherwise):

- `layout` is in the supported set.
- Required fields for the layout are present.
- No fields outside the layout's allowed set.
- Every `path` matches the JSONPath regex.
- Every `action.endpoint` matches at least one of your declared `endpoints` globs (the same glob syntax that gates `platform.network.fetch` in script renderers).
- Every `action.id` is unique within the template.

### 5.2 Live Cards

Set `card_type="live"` to ship a persistent, upsertable card. The plugin manifest must also provide `card_key_path` — a JSONPath into your decrypted payload that yields the stable identity for each card (e.g. `$.order_id`). This lets the inbox merge multiple upserts for the same logical card into one row.

```python
response = client.publish_plugin(
    plugin_identifier="com.example.delivery",
    version="1.0.0",
    manifest_hash=hashlib.sha256(b"manifest").digest(),
    bundle_hash=hashlib.sha256(open("dist/plugin.bundle.js","rb").read()).digest(),
    bundle_signature=bytes.fromhex(manifest["signature"]),
    signed_bundle_url="https://example.com/plugin.bundle.js",
    capabilities=["network"],
    endpoints=["https://example.com/api/*"],
    card_type="live",
    card_key_path="$.order_id",
)
```

Live cards work with **either** renderer. The most common combo is `renderer="template"` + `card_type="live"` (delivery progress card with no JS).

#### Upserting a live card

```python
from syncler.crypto import assemble_live_card_aad, encrypt_payload
from datetime import datetime, timedelta, UTC
import json

expires_at = datetime.now(UTC) + timedelta(hours=12)

# Live card plaintext. Include `hostPreview` so the inbox row uses your
# title/subtitle instead of the generic "New message from {sender_name}"
# fallback. The `order_id` field is what the manifest's
# `card_key_path="$.order_id"` resolves to and MUST equal the `card_key`
# you pass to `upsert_card` below — see the note after the example.
plaintext = json.dumps({
    "hostPreview": {
        "title": "Pizza Delivery",
        "subtitle": "Order #456",
        "summary": "Out for delivery — ETA 8 minutes",
    },
    "order_id": "order-456",
    "title": "Pizza Delivery",
    "subtitle": "Order #456 • ETA 8 minutes",
    "body": "Driver: Sam. Truck: 7F-12.",
}).encode("utf-8")

# AAD the device will verify against on AES-GCM decrypt. `sequence_number`
# stays as a Python int — the canonical JSON emits an integer literal.
aad = assemble_live_card_aad(
    sender_id=client.sender_id,
    user_id=user_id,
    plugin_id=plugin_row_id,
    card_key="order-456",
    sequence_number=2,
    expires_at=expires_at,
)

# V2 (current): client.upsert_card takes the plaintext payload + card_type
# directly — the SDK handles per-device V2 envelope sealing (HPKE per
# recipient device + AES-GCM payload, AAD bound to envelope_kind +
# card_type + sequence_number per docs/crypto-spec.md §11). You don't
# touch encrypt_payload / nonce yourself anymore.
client.upsert_card(
    user_id=user_id,
    plugin_id=plugin_row_id,
    card_key="order-456",
    card_type="standard_card",
    payload={
        "hostPreview": {"title": "Order #456", "summary": "Shipped"},
        "order_id": "order-456",
        "status": "shipped",
    },
    sequence_number=2,
    expires_at=expires_at,  # datetime, server caps at now + 48h
)
```

**`card_key` must equal `card_key_path` resolved on the plaintext.** The manifest's `card_key_path` (declared at publish time, e.g. `"$.order_id"`) is the path the host extracts on decrypt to use as the merge key for this card. The `card_key` you pass to `upsert_card` is what the server stores. If the two disagree, the host's per-card merge breaks and your "live" card behaves like a series of event cards. The server doesn't cross-check the two — enforcement is your responsibility. (V1 validates `card_key_path` only as `startswith("$")`; a future phase will tighten this to the full `$.field(.subfield)*` JSONPath grammar used by template fields.)

See `docs/crypto-spec.md §8` for the exact `assemble_live_card_aad` canonical byte shape.

**Server-side gates on upsert** (return 4xx if violated — see §9):

- **Active pairing required.** The `(sender_id, user_id)` pair must have a non-revoked `Pairing` row; otherwise 410. Mirrors the message-send path.
- **Plugin must be active and live-type.** `Plugin.revoked_at IS NULL` and `card_type = "live"`; otherwise 410.
- **TTL window.** `expires_at` must be strictly future (else 400) AND at most 48 hours from now (else 400). The cap is server-enforced and not negotiable.
- **Sequence monotonicity.** New `sequence_number` must be strictly greater than the existing row's; else 409.
- **Rate limit.** Fixed window of 60 upserts per minute per `(sender_id, user_id, card_key)`; else 429. (There's also an IP-bucketed pre-auth limit of 120/min for cheap DoS protection.)

The successful response triggers a `card.upsert` SSE event to every active device of the user, so foreground clients refresh within ~1s.

#### Deleting a live card

```python
client.delete_card(
    user_id=user_id,
    plugin_id=plugin_row_id,
    card_key="order-456",
)
```

`user_id` AND `plugin_id` are **required**. The canonical signed envelope binds `(sender_id, user_id, plugin_id, card_key)` — without `plugin_id` in the signature, a captured delete envelope from one of your plugins could be replayed against a different plugin row with the same `card_key`. The delete endpoint matches exactly that quadruple in the row lookup.

**Freshness + replay protection:** the delete envelope also binds a `nonce` (12 random bytes) and `expires_at` (defaults to now + 24 h; server caps at 48 h). The SDK generates both automatically, so the canonical call above keeps working unchanged. Callers MAY pass `nonce=` and `expires_at=` to control the freshness window explicitly — useful when retrying after a 409 to confirm a previous delete already landed. Server-side enforcement: expired envelopes return 400; replayed nonces return 409. See `docs/crypto-spec.md §11.6` for the V2 delete envelope shape.

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

`https://your-app.example.com/api/action` accepts whatever body the host POSTs when the user taps an action button. The shape depends on which renderer you chose:

**Script renderer** — your `onclick` handler builds the body via `platform.network.fetch`. In the §3 example that's:

```http
POST /api/action
Content-Type: application/json

{ "item_id": "...", "acted_at": "..." }
```

**Template renderer** — the host POSTs the **full decrypted payload** as the body, verbatim. There's no JS hook to intervene. Your endpoint receives the whole payload JSON; pull whatever fields you need server-side. No `Authorization` header is added by the host (the user's Syncler bearer token never reaches your endpoint — security boundary). If you need request authentication, HMAC the payload using a sender-specific secret your backend shares out of band with itself, or rely on TLS + endpoint allowlisting alone.

Note: the platform does **not** inject `device_id` into the body for you in V1. If you need to distinguish which device acted, include a sender-generated identifier in your payload and (for script renderer) have the plugin echo it back. For template renderer, your `card_key` (or any other field in the payload) serves the same dedupe role. Dedupe by `item_id` / `card_key` alone if a single tap is what you care about.

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

> **Starting a new plugin from scratch?** Run `npm create @syncler/plugin <name>` first — it scaffolds `src/plugin.ts`, `manifest.json`, `build.sh`, `package.json`, `README.md`, and `.gitignore` for you, validated against the SDK rules. Pair it with the `examples/trading-bot/` directory for a complete sender + plugin round-trip you can copy from.

1. Install the Syncler Android app (`./gradlew :app:installDebug`).
2. Sign up + log in. The app auto-enrolls this device with the server.
3. From your backend, run a script that registers your sender and prints a pairing QR (see SDK README).
4. Tap **Pair sender** -> **Scan QR** in the Syncler app. Confirm the fingerprint matches what your script prints.
5. **Pairing handoff.** Two paths depending on how your sender is set up:
   - **V1.5 automated (default going forward):** if your sender has registered a bootstrap key and supplied `sender_broker_url` at `create_pairing_qr(...)` time, the app POSTs an encrypted bootstrap envelope (containing `user_id` + `pairing_key`, sealed with the sender's X25519 bootstrap public key) to your broker after fingerprint confirmation; your sender's `Client.wait_for_pairing(...)` returns the decrypted pair. No copy step. See §8.5 for the broker setup.
   - **V1 manual:** the app shows your `user_id` and a `pairing_key_hex`. Copy both and feed them into your backend's `client.set_pairing(user_id, pairing_key=bytes.fromhex(...))` so it can encrypt for you. This is also the fallback when the broker POST fails in the automated path.
6. Publish your plugin (`client.publish_plugin(...)`) — save the `plugin_row_id`.
7. Run `client.send_to(...)` with a test payload.
8. The server pushes an `inbox.changed` event over the SSE stream the open app is subscribed to (foreground); the phone refreshes its inbox, decrypts the message, fetches your bundle (or applies your template manifest), and shows the native row within ~1s. If the app is backgrounded, FCM wakes it and the inbox refresh happens on the next foreground.
9. Tap the row to open the plugin-rendered detail view, then tap your action button.
10. Confirm your `/api/action` endpoint received the POST.

A complete `bot.py` + `plugin/` pair you can run as-is lives at `examples/trading-bot/` — `python bot.py register` → `pair` → `publish-plugin` → `ack-server` + `loop` produces a real card with a working Acknowledge round-trip. The example uses the automated V1.5 pairing flow with an in-process broker thread; `set-pairing` is preserved as a V1 manual fallback when the broker can't be reached.

## 8.5 Automated pairing (V1.5)

V1 pairing makes the user copy `user_id` + `pairing_key_hex` by hand into your sender's CLI. V1.5 replaces step 5 above with an encrypted POST: after the user confirms the fingerprint, the app silently delivers the pairing key to your sender's broker. The user never sees a hex blob.

The protocol underneath (HPKE-style envelope, X25519 ECDH, AES-GCM with AAD-binding) is in `docs/crypto-spec.md §9`. This section is the integration walkthrough.

### Why automated

- One-step pairing for users.
- Production senders don't need a manual-paste UX.
- The substitution-attack guards (Ed25519 over the bootstrap key + AAD-binding on `sender_broker_url`) keep V1's trust model intact.

### Sender setup

You need to do four things, once:

1. **Generate an X25519 bootstrap keypair** alongside your existing Ed25519 signing key. Persist the private key somewhere safe (file with 0600, KMS, secrets manager).

   ```python
   from syncler.bootstrap import x25519_keypair_pem
   bootstrap_priv, bootstrap_pub_raw = x25519_keypair_pem()
   # bootstrap_pub_raw is 32 raw bytes — store both fields somewhere
   # your broker process can read them.
   ```

2. **Register the bootstrap key with syncler** (once per sender, or per rotation):

   ```python
   client.register_bootstrap_key(bootstrap_public_key_raw=bootstrap_pub_raw)
   ```

   Internally the SDK Ed25519-signs `b"syncler-v1-bootstrap-key:" + bootstrap_pub_raw` and POSTs to `/v1/senders/me/bootstrap-key`.

3. **Run a broker** that your devices POST to. The SDK ships one under the `[broker]` extra:

   ```sh
   pip install 'syncler[broker]'
   ```

   ```python
   # broker_app.py
   from syncler.broker import make_app
   from syncler.broker_storage import InMemoryBrokerStorage
   from syncler.bootstrap import load_x25519_private_key_from_raw

   storage = InMemoryBrokerStorage()
   priv = load_x25519_private_key_from_raw(open("bootstrap.key", "rb").read())
   pub_raw = open("bootstrap.pub", "rb").read()

   app = make_app(
       bootstrap_private_key=priv,
       bootstrap_public_key_raw=pub_raw,
       sender_broker_url="https://sender.example.com/syncler/bootstrap",
       storage=storage,
   )
   ```

   ```sh
   uvicorn broker_app:app --host 0.0.0.0 --port 8443 \
       --ssl-keyfile=key.pem --ssl-certfile=cert.pem
   ```

   Your reverse proxy maps `https://sender.example.com/syncler/bootstrap` → this app.

4. **Tell `Client` where the broker storage lives** so `wait_for_pairing` can poll it:

   ```python
   client = Client(
       sender_name="Example",
       private_key_path="sender.pem",
       broker_storage=storage,            # NEW — same storage the broker app writes to
   )
   # The sender's X25519 bootstrap keypair lives outside Client (the
   # broker app holds it). The Client only needs the storage handle
   # to learn when a pairing has completed.

   path = client.create_pairing_qr(
       ttl_seconds=300,
       out_path="pair.png",
       sender_broker_url="https://sender.example.com/syncler/bootstrap",  # NEW
   )

   # Blocks until the broker writes (user_id, pairing_key) to storage.
   # On success the Pairing dataclass carries `pairing_id` + `user_id`;
   # the 32-byte pairing key is stored on `client.pairing_key` so
   # subsequent `send_to(...)` calls just work.
   pairing = client.wait_for_pairing(timeout_seconds=120)
   # `client.pairing_key` is now set; no further `set_pairing` call needed.
   ```

### Android user flow

When the device fetches the preview and sees all four bootstrap fields, the pairing screen uses the automated path:

1. User scans the QR.
2. App fetches preview; verifies `bootstrap_key_signature` against the sender's Ed25519 pub key.
3. User confirms the fingerprint (unchanged).
4. App calls `/complete` — Syncler-side pairing is finalized.
5. App builds the bootstrap envelope and POSTs to `sender_broker_url`.
6. Broker decrypts, writes to its storage, returns 201.
7. `Client.wait_for_pairing` in your sender's send loop picks up the new pairing tuple.

If step 5 fails (broker down, network glitch), the app **shows a fallback banner** with the same `user_id` + `pairing_key_hex` block as the V1 manual flow. The device-side pairing is already real (step 4 finalized it); only the sender catch-up needs the manual paste.

### Failure modes

| What | App behavior |
|---|---|
| `bootstrap_key_signature` doesn't verify | **Hard refusal.** Pairing NOT finalized. Treated as substitution-attack indicator — user does not get a fallback. |
| Preview has only some bootstrap fields | **Hard refusal**, same reason. Sender MUST register all four atomically. |
| Broker returns 401 (decrypt failed) | Fallback banner (manual paste). |
| Broker returns 404 (pairing slot not found) | Fallback banner. Usually a multi-process miswire: the Client's `reserve()` call landed in a different process / worker than the broker's `is_reserved()` check. Use a shared `BrokerStorage` backend (Redis, Postgres) so reservations propagate. |
| Broker returns 409 (replay with different values) | Fallback banner — the pairing key from your sender's `wait_for_pairing` will not match this device's, so the user must paste the device's values. |
| Broker returns 5xx or times out | Retried up to 3 attempts (250ms / 750ms backoff). If still failing, fallback banner. |
| Sender's `wait_for_pairing` times out | Sender's responsibility — typically loop again with a fresh QR. The device-side pairing is still real; user can paste manually. |

### Security boundaries

Four guards make this safe:

1. **Bootstrap key signature.** The sender's X25519 bootstrap public key is signed by its long-term Ed25519 signing key (which the user confirms via fingerprint). The Syncler server cannot substitute its own X25519 key — the signature would fail to verify against the Ed25519 pub key the user just confirmed.

2. **AAD-binding on `sender_broker_url`.** Even if the Syncler server could change which URL the device POSTs to, the broker reconstructs AAD using its OWN configured `sender_broker_url`. An envelope crafted for a different URL fails AEAD tag verification.

3. **Pending-pairing registry at the broker.** Before any decrypt, the broker checks `storage.is_reserved(pairing_id)` and 404s unknown IDs. An attacker who knows the public bootstrap key can mint a cryptographically valid envelope for an arbitrary uuid4, but it can't reach the decrypt path unless the Client previously reserved that ID via `create_pairing_qr(sender_broker_url=...)`.

4. **CAS replay guard at the broker.** A captured envelope replayed to the same broker with the same plaintext is idempotent (200). Replayed with different values gets a 409.

### Production hardening

- Use Redis or Postgres for `BrokerStorage`. The shipped `InMemoryBrokerStorage` is single-process only — fine for the trading-bot example where the Client and broker share one Python process, but **insufficient for multi-process deployments**. `Client.create_pairing_qr(sender_broker_url=...)` calls `storage.reserve(pairing_id)`; if the broker app runs in a different uvicorn worker, that call's effect won't be visible to the broker's `is_reserved()` check and every pairing will 404. Use a shared backing store that implements the `BrokerStorage` Protocol atomically (one `reserve` call visible to all workers).
- The `rate_limiter` hook on `make_app(...)` is **mandatory** in production. The pending-pairing registry blocks envelopes for un-issued `pairing_id`s, but once an attacker knows a reserved ID they can still spam decrypt attempts on it — the rate limiter is your CPU defense. Implement per-IP and per-`pairing_id` limits.
- Terminate TLS at your reverse proxy; the broker is HTTP-only inside the trust boundary.
- See `docs/crypto-spec.md §9.3` for the single-fixed-`sender_broker_url` design (per-pairing URLs are a V2 add).

## 9. Common server errors

The server returned a 4xx or 5xx; here's what to change.

- **`401 invalid envelope signature`** — your sender's Ed25519 key doesn't match the one registered. Re-check `private_key_path`.
- **`410 plugin missing, revoked, or not owned by sender`** — `plugin_id` (the row UUID) is wrong, or you accidentally used `plugin_identifier`. The two are distinct (see §5 vs §6).
- **`410 recipient has no active devices`** — the user has no enrolled device (signed up but never opened the app, or revoked all devices). FCM availability is *not* required; messages are stored regardless and pulled via `/v1/messages/inbox`.
- **`409 nonce already used`** — your code reused a nonce somehow; SDK generates fresh nonces per `send_to`, so this means you're calling `send_to` with cached envelope bytes. Rebuild.
- **`429 rate limited`** — back off + retry per `Retry-After` header.
- **`410 plugin missing, revoked, not live-type, or not owned by sender`** (cards upsert) — the plugin row's `card_type` isn't `"live"`, or the row has a non-null `revoked_at`, or the publish row's `sender_id` doesn't match the signed envelope's sender. The error message is intentionally generic so a probing caller can't distinguish the cases (don't leak which plugin UUIDs exist).
- **`410 no active pairing`** (cards upsert) — the recipient user has not paired with your sender, or the pairing has been revoked. The user needs to re-scan your pairing QR.
- **`400 expires_at exceeds the 48h live-card cap`** (cards upsert) — your supplied `expires_at` is more than 48 hours from now. Live cards are capped server-side to prevent slot squatting. Send shorter and re-upsert to extend.
- **`400 expires_at is not in the future`** (cards upsert) — you sent an already-expired `expires_at`. Use a future timestamp.
- **`409 sequence_number not greater than existing`** (cards upsert) — the existing card has a `sequence_number` ≥ what you sent. Sequence must be strictly increasing per `card_key`.
- **`HostPreviewValidationError: hostPreview.X is N UTF-8 bytes; max is M`** — caught at `client.send_to` time. Trim the offending field; see §2 for the caps. UTF-8 byte counts, not characters — emoji and accented characters cost more than one byte.
- **`422 manifest validation failed`** (cards/plugins publish) — Pydantic rejected the manifest body. The detail field tells you which check failed. Most common: `template required when renderer == 'template'`, `card_key_path required when card_type == 'live'`, `action endpoint not allowed by declared endpoints`. Mirror this validation client-side via `validatePluginManifest` (TS SDK) to catch before the round-trip.

Cross-reference `docs/crypto-spec.md` for the AAD + envelope canonical byte shapes if signatures disagree.

## 9.1 Common pitfalls

These pass server-side validation but bite you at runtime. The SDK validator catches most of them now; check here first when something compiles and publishes but doesn't behave.

- **`UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE` in `bundleHash` / `signature`.** You forgot to run `sign-bundle.ts` on the built bundle. The SDK validator rejects this (post Phase 4.1) — `bundleHash` must be exactly 64 hex chars (SHA-256), `signature` must be exactly 128 hex chars (Ed25519). The signer emits lowercase, but the validator and server tolerate either case. If the SDK lets it through, the server rejects at publish with 422; if both let it through, the device fails the manifest-hash check and shows "Plugin not loaded."
- **`registerPlugin()` not called.** Card shows *"render failed: plugin did not install __syncler_internal_dispatch — missing registerPlugin() call?"*. Your bundle defined the plugin class but never called `registerPlugin(new YourPlugin())` at module scope. The host's bundle loader looks for the dispatcher hook the SDK installs from `registerPlugin`; without it, there's nothing to dispatch into.
- **Single-segment wildcard surprise in `declaredEndpoints`.** `https://example.com/api/*` matches `https://example.com/api/v1` but NOT `https://example.com/api/v1/users`. `*` is one segment, never multiple. Card shows *"endpoint not declared"*. See §3.1 for the full glob grammar.
- **`card_key` doesn't match `card_key_path` resolved on the plaintext.** The manifest declares `card_key_path: "$.order_id"`; the device extracts that on decrypt and uses it as the merge key. You pass `card_key="order-456"` to `upsert_card`. If the plaintext's `$.order_id` doesn't equal `"order-456"`, the device treats each upsert as a separate row — your "live" card behaves like an event card. The server doesn't cross-check; it's your responsibility.
- **Template/script field mismatches.** Setting `template: {...}` on a `renderer: "script"` manifest, or omitting `template` on `renderer: "template"`. SDK validator catches both. Same with `cardType: "live"` requiring `cardKeyPath` and `cardType: "event"` rejecting it. If the SDK validator misses a case you hit, file a Phase 4.5 doc-bug.
- **Row shows "New message from {sender}" with fallback text only.** Your message was sent without a `hostPreview` block, or the block was malformed (logged + ignored on the device). The detail view still loads the plugin and renders normally; only the row is generic. Add a valid `hostPreview` and re-send.
- **Action POST body shape surprise.** Script renderer: your `onclick` builds whatever body you want. Template renderer: the host POSTs the **entire decrypted payload** verbatim, with no Authorization header. If your endpoint expects `{item_id, acted_at}` and you swapped to template renderer, the endpoint now sees the full payload — adjust the receiver, don't fight the host.

## 10. Versioning + history

Publish every plugin release as a new row under the same `plugin_identifier`. `plugin_identifier` is the stable name, while `plugin_row_id` is the exact version row returned by `client.publish_plugin(...)`.

Version strings use semver-lite: `MAJOR.MINOR.PATCH` with an optional prerelease suffix. Each numeric component is capped at 6 digits, and every publish for the same `(sender_id, plugin_identifier)` must be strictly greater than all existing versions.

When you send a card, pass the exact `plugin_row_id` that matches the payload schema you produced. The message stores that row UUID. On the device, Syncler resolves the historical row by ID, not by `/latest`, so a v1 message keeps using the v1 bundle even after you publish v2.

The device verifies the downloaded bundle against the row's SHA-256 `bundle_hash` and caches verified bundle bytes by hash for the process lifetime. On app restart, the cache is empty and the device re-fetches the same historical row's `signed_bundle_url`. Keep old bundle URLs available for as long as you want old inbox messages to remain renderable.

Archive/delete are user-organization states synced through the encrypted user-state blob. They do not remove the server copy, and archive does not store a local message body beyond the server's retention window.

## 11. Lost or compromised devices

The host now wraps the revoke + rotate sequence into a single guided flow. The user opens **Settings → Security → "I lost a device"** on a trusted device and walks through:

1. **Pick the lost device** from the enrolled-devices list (current device is excluded).
2. **Preflight** — the flow confirms the rotation endpoint is reachable before any irreversible step.
3. **Confirm revoke** — full-screen warning. `POST /v1/auth/devices/{device_id}/revoke` runs server-side; idempotent (a concurrent revoke on another trusted device just reads back as "already revoked").
4. **Rotation recommended** — the host explains why rotation is the security completion step (the lost device may still hold the user's master-key wrap). Two buttons: "Rotate now" / "Skip for now".
5. **Rotate** — user re-enters their master password. The flow calls `POST /v1/account/rotate-master-key/challenge` followed by `POST /v1/account/rotate-master-key`, rewrapping every per-sender pairing entry the host holds locally under the new master key.
6. **Done** — summary lists the senders that need re-pair (best-effort inferred from the lost device's recent inbox traffic) with deep-links into the existing pairing flow.

If the user picks "Skip for now" at step 4, a home-screen banner appears for **30 days** ("You revoked a device but didn't rotate your master key — rotate now"). After 30 days the banner auto-clears so a user who genuinely doesn't want to rotate isn't nagged forever. Spec: `docs/lost-device-flow.md`.

**What you need to do as an integrator**: no sender-side API change. Host rewraps the local per-sender pairing material under the new master key during step 5; subsequent messages from your sender decrypt transparently. **However**: if the user's pairing with your sender was only set up on the lost device (and never synced down to another device before the loss), the host has nothing to rewrap and the user must re-pair through your existing pairing flow. The "Done" summary surfaces this case as a deep-link checklist.

**Past cached data caveat**: messages already cached on the lost device remain encrypted under the previous master key. If the lost device is compromised AND the master key is extracted from secure storage, that historical data is unrecoverable. Syncler does not support remote wipe.

## 12. Two-way live channel

Use this when you need sub-second push from your backend to currently-connected devices, plus a device → sender callback path. The host opens an authenticated WebSocket per plugin row; your **V2 envelopes ride opaque through the channel**, so the server stays content-blind. Don't use this for state of record — the inbox is still the authoritative path.

The app composition root must pass the host's `Session` into `PluginLoader.android(context, scope, session = ...)` so the bridge's `deviceJwtProvider` resolves to a real device JWT. If `Session` isn't wired, `platform.live.connect(...)` returns `no_session`. The standard Hilt-wired app does this; tests + minimal builds default to the throwing placeholder.

### Wire contract — what every sender needs to know

```
WS endpoint:   wss://api.syncler.app/v1/live/plugin/{plugin_row_id}
WS subprotocol: Sec-WebSocket-Protocol: syncler.v1, bearer.<connect_token>
```

The connect token is a 256-bit single-use bearer minted by `POST /v1/live/connect-token` (the device's regular JWT auths the mint, the WS only sees the opaque token). Tokens expire after 60 seconds. The device side handles minting; you don't touch this from your sender.

The wire is a multiplex of named **channels** over one socket, with seven frame types (`open / close / message / ack / error / ping / pong`). The full frame schema is in `docs/live-channel.md §"Outer envelope (all frames)"`; the TypeScript SDK + Android runtime handle framing for you, so you only touch the schema if you're building a non-SDK client.

Server enforces:
- **Heartbeat** — server `ping` every 30s, closes `4408` if no inbound frame in 60s.
- **Rate limit** — 16 KB/s sustained, 64 KB burst per socket; over-budget closes `4429`.
- **Revocation** — when the user revokes their pairing with your sender, every device's WS for that plugin row closes `4401` immediately. No grace period.

### Pushing from your sender into the live channel

Your backend POSTs an opaque V2 envelope to the plugin-row's push endpoint. The server fans it out to every currently-connected device WS belonging to a paired user under your sender:

```
POST https://api.syncler.app/v1/live/plugin/{plugin_row_id}/push
Content-Type: application/json
```

Body:

```json
{
  "channel": "ticker",
  "envelope": {
    "...":                                "the standard V2 envelope object",
    "sender_id":                          "<your sender UUID>",
    "envelope_kind":                      "event",
    "payload_nonce":                      "...",
    "payload_ciphertext":                 "...",
    "recipient_envelopes":                [ ... per-device HPKE envelopes ... ],
    "envelope_signature":                 "..."
  }
}
```

`channel` matches the WS multiplex channel name the device-side plugin `connect`'d to (regex `^[a-zA-Z0-9._-]+$`, ≤ 64 chars). The server wraps the envelope in a `message`-type frame on this channel before fanning out to devices — your sender doesn't construct the frame itself.

The envelope is the same V2 shape you'd hand to `send_to(...)` — sealed for each of the user's devices with their per-device HPKE keys, signed by your Ed25519 key over the canonical bytes (`docs/crypto-spec.md §11.8`). The push endpoint treats the envelope as opaque; the authenticity gate is the envelope signature the devices verify, not a separate POST-level signature. Server fans out to **every paired user under your sender** for `plugin_row_id`, and each device picks its own `recipient_envelope` to decrypt. Devices that have no matching `recipient_envelope` on the frame just ignore it.

Response: `202 {"delivered": <int>}` where `delivered` counts the per-user topics the server published to. It is NOT a per-device delivery count.

Today neither the Python nor TypeScript SDK ships a typed `live_push` helper — drive the raw POST until they do.

### Receiving from devices on your sender

Devices push back to you via a server-operated webhook forwarder. Prerequisites:

1. **Register a `liveInboundUrl`** in your plugin manifest at publish time. The TypeScript SDK manifest exposes this field; the Python `Client.publish_plugin(...)` does not yet expose `live_inbound_url`, so Python integrators must drive the raw `POST /v1/plugins/publish` body to set it until the helper grows the parameter.
2. **Obtain the server's Ed25519 webhook-signing public key out-of-band.** The current API does not include the server's signing public key in the publish response. For v0.1 deployments, ask the Syncler operator for the key (an admin / well-known endpoint is planned). Pin it in your service; you'll verify `X-Syncler-Signature` against it.

When a device sends a frame on the live channel, the server delivers to your endpoint:

```
POST <liveInboundUrl>
Content-Type: application/json
X-Syncler-Signature: <base64 Ed25519 signature over the raw body bytes>
```

Body (compact JSON; this is exactly what `X-Syncler-Signature` covers):

```json
{
  "plugin_row_id":     "...",
  "channel":           "...",
  "envelope":          "<string — JSON-encoded V2 envelope from the device>",
  "received_at":       1748296894,
  "device_pseudonym":  "<base64url, ≤32 chars>"
}
```

Notes for verification + integration:
- `received_at` is integer **epoch seconds** (not ISO 8601).
- `device_pseudonym` is `HMAC-SHA256(server_signing_seed, "{device_id}|{sender_id}")` truncated to 32 chars of base64url. It is stable per (device, sender) and uncorrelated across senders.
- `envelope` is a **string** carrying whatever the device pushed verbatim — the server does NOT decode/re-encode it. Devices using the TypeScript SDK call `platform.live.send(channel, envelopeBase64)` and base64 the V2 envelope before sending, so in that path the webhook's `envelope` is the base64 form. The server treats it as opaque bytes either way; your receiver decodes the same way it was encoded on the device.
- Verify `X-Syncler-Signature` over the **raw POST body bytes** (the compact-JSON serialization the server signed). Do not re-serialize before verifying.
- Three retries with exponential backoff (1s → 4s → 16s); 5s per-attempt timeout. If the server's signing seed is unset, the forwarder logs LOUDLY and refuses to deliver rather than silently dropping the signature header.

Spec deep-dive: `docs/live-channel.md`. Read it before shipping a live integration — it pins the multiplex frame schema, the close-code table, and the connect-token contract in full.

### Plugin-side (device) live channel snippet

Inside your plugin bundle (`src/plugin.ts`):

```ts
import { BasePlugin, platform, registerPlugin } from "@syncler/plugin-sdk";

export class TickerPlugin extends BasePlugin {
  async onMessage(payload: unknown) {
    // Standard event / live-card delivery still flows through
    // here. After the first message lands, open the live
    // channel for sub-second updates.
    await platform.live.connect("ticker");
  }

  async onLiveMessage(channel: string, envelopeBase64: string) {
    // Fires per inbound live frame. envelopeBase64 is the
    // opaque V2-style envelope sealed for THIS device — decode
    // + open it with the V2 helpers in @syncler/plugin-sdk.
    if (channel !== "ticker") return;
    const envelope = JSON.parse(atob(envelopeBase64));
    // …verify, decrypt, react…
  }

  async onLiveError(channel: string, code: string) {
    // Optional. Common codes: rate_limit_exceeded,
    // channel_name_invalid, no_session. Override to surface
    // diagnostics; defaults to no-op.
  }

  async onLiveClosed(channel: string) {
    // Optional. Fires when a channel closes (either side).
    // Plugins that hold per-channel state should clear it here.
  }

  async sendTick(envelopeBase64: string) {
    // envelopeBase64 is your V2 envelope addressed to the
    // sender — built with the V2 helpers in
    // @syncler/plugin-sdk.
    await platform.live.send("ticker", envelopeBase64);
  }

  async onAction(actionName: string, payload: unknown) {
    // Action callbacks are unchanged.
  }
}

registerPlugin(new TickerPlugin());
```

Key contract points:
- `platform.live.connect(channelName)` opens (or reuses) the WS for this plugin row and starts the named channel. Idempotent — calling twice on the same channel just returns success.
- Incoming frames arrive at your `onLiveMessage(channel, envelopeBase64)` hook on `BasePlugin`. The hook is plugin-global — switch on `channel` if you've connected to more than one. Errors and channel-close events arrive at `onLiveError` / `onLiveClosed` respectively.
- `platform.live.send(channel, envelopeBase64)` MUST be preceded by `connect`. Calling `send` on an un-connected channel returns `channel_not_open`.
- `platform.live.close(channel)` is idempotent. Closing one channel does NOT tear down the underlying WS for other channels.
- `platform.live.subscribe(channelName)` is a one-line alias for `connect` that emphasizes read-only intent; same wire path.

### Operator note (production)

The server's live fan-out is backed by a Redis transport in any multi-worker deployment (`LIVE_BACKPLANE=redis` + `REDIS_URL`). For single-worker dev the default `memory` backplane works without Redis. The integrator doesn't pick this; the operator does. Spec: `docs/live-backplane.md`.

## 13. Field-level live-card patches

For persistent live cards (`card_type: "live"`) that change frequently — scoreboards, typing indicators, presence dots — use `client.patch_card(...)` instead of paying the per-tick full-card seal cost of `upsert_card`. The wire frame is opaque (server NEVER sees the field path or the new value); per-recipient HPKE-sealed JSON patches ride inside.

### When to use which

Use `upsert_card` when:
- You're publishing the card for the first time.
- The card's identity (`card_key`, `card_type`) or schema changed.
- The `hostPreview` block changes (title / subtitle / summary).
- You need to extend the TTL beyond the current `expires_at`.
- You're recovering from a sequencing mistake — a new upsert resets the patch chain.

Use `patch_card` when:
- The card exists (an `upsert_card` already landed) and only a small set of fields needs to change.
- Your update rate is high enough that re-sealing the whole payload per tick hurts.
- You can maintain **contiguous `patch_seq`** numbering (one publisher per card; no gaps).

If you can't guarantee contiguous publishing — e.g. multiple worker processes might race on the same `(card_id, base_seq)` — fall back to `upsert_card` per tick. The server rejects gaps and replays with `409`; a non-publisher process trying to patch usually loses that race.

### Sender side

```python
client.patch_card(
    user_id="...",
    plugin_id="...",
    card_id="...",         # the live card's row UUID, returned by the prior upsert
    base_seq=42,           # must equal the card's current sequence_number
    patch_seq=7,           # contiguous (= last_patch_seq + 1)
    patches=[
        ("home_score", "42"),
        ("away_score", "17"),
    ],
    field_paths={          # name → JSONPath mapping; SDK never sees raw paths in your code
        "home_score": "$.home_score",
        "away_score": "$.away_score",
    },
)
```

Both fields update **atomically** on the device: either all replace ops succeed and the device's local card payload mutates in one shot, or any failure (unknown JSONPath, decrypt failure, signature mismatch) discards the whole batch. The renderer recomposes on the new payload.

### Server contract

- `POST /v1/cards/patch` with the same recipient-set rules as `cards.upsert`.
- Sequence: server enforces **contiguous** `patch_seq` (must equal `last_patch_seq + 1`). Out-of-order or skipped sequence numbers return `409 patch_seq_gap`; replays return `409 patch_seq_regression`; collisions return `409 patch_seq_collision`. The strict contiguity guarantees devices never see a chain with holes.
- A new whole-card `cards.upsert` resets the chain: server purges every CardPatch row for that card before committing the upsert; `last_patch_seq` resets to 0; any in-flight stale patches the device receives are dropped.
- 48h TTL + parent-card-delete cleanup keep the patches table bounded.

### Device catch-up

Disconnected devices catch up on the next `/v1/messages/inbox` pull. The inbox response inlines every persisted patch for the card's current `card_seq` (ordered by `patch_seq`); the device applies them in order on top of the freshly-decrypted upsert payload before the row lands in the inbox. If a patch is missing (TTL ran out on an earlier link), the chain halts and the device falls back to the last full upsert.

Spec: `docs/live-card-patch.md`. **Privacy invariant**: the outer wire frame never carries plaintext field paths or values, ever. Confirm if your usage drifts.

## 14. Per-plugin user preferences

The user controls these per-plugin settings from the host UI. There's no integration step — your sender keeps calling the same publish + send APIs, and the host gates notifications transparently. They sync across the user's devices through the existing encrypted user-state blob.

| Preference | Behavior |
|---|---|
| **Label override** | Free-form 64-char string. Falls back to your manifest's `name`. |
| **Notification cadence** | `realtime` (default) / `batched_15m` / `batched_1h` / `digest_daily`. Non-realtime cadences **suppress the immediate OS notification**; the inbox row still updates so the user sees the missed events on next inbox open. Wall-clock-aligned batched delivery (one notification fired per boundary) is on the roadmap; today only the suppression half ships. |
| **Quiet hours** | Configurable start + end local-hour window in the user's saved IANA timezone (NOT the device's current timezone — a traveler keeps their home quiet hours). Wraps midnight when start > end. Suppresses **notifications only**; data ingestion continues normally so inbox + live-card state stay current. |
| **Mute** | Per-plugin mute; independent of the per-sender mute. Both must be off for a notification to fire. |

**What you need to do as an integrator**: nothing. Your sender's `send_to / upsert_card / patch_card / live push` paths are unchanged. The notification adapter gates on prefs before posting; if it suppresses, the message still arrives in the inbox.

Cross-device sync follows the existing user-state lockstep contract — last-writer-wins on `modified_at` for the whole settings row. Two devices concurrently editing prefs for the same plugin: the later write wins whole-row (intermediate mute toggles can be lost, but no row corruption). Field-level merge is on the roadmap if real concurrent-edit pressure appears.

Spec: `docs/plugin-prefs.md`. The plugin SDK has NO API to read or write prefs — they're user policy, not plugin policy.

## 15. Surfaces not covered in this guide

The platform ships a few execution surfaces beyond `script` and `template` that aren't a public integration target yet. Mentioning them so you don't go looking:

- **`native_kotlin` plugins** — signed DEX-loaded plugins running in an isolated Android process. Available to Syncler-internal first-party plugins; the public SDK + signing pipeline for partners ships later.
- **`script_fast`** — a Javy/QuickJS runtime alternative to the WebView for pure-logic plugins. Same wire contract as `script`; different host execution path. Not partner-ready.

For partner integrations today, `renderer: "script"` and `renderer: "template"` are the two options.

---

**Fifteen sections.** If you needed more than that to integrate, the SDK has a DX problem — file a finding back at the Syncler team.
