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
- **Per-device dismiss (server-side filter).** When the user dismisses a message on device A, `/v1/messages/{id}/dismiss` writes a `DeliveryStatus` row keyed on `(message_id, device_id=A)`. Subsequent calls to `/v1/messages/inbox` from device A LEFT JOIN `DeliveryStatus` for `device_id=A` and filter rows where `dismissed_at IS NOT NULL` out of A's feed. The dismiss SSE event still fans out to *all* of the user's devices, but the server-side filter is per-device — devices B, C, etc. receive the hint and refresh, but their own feeds keep the row until they dismiss it themselves. V1 design: dismiss is a per-device gesture (some users want a card visible elsewhere); cross-device "dismiss everywhere" is a V1.5 add.
- **Real-time hints over SSE.** Foreground devices keep an authenticated Server-Sent Events stream open to `/v1/events`. The server pushes content-blind hints — `inbox.changed`, `state.changed`, `dismiss`, `card.upsert`, `card.delete` — and the client re-fetches over REST in response. The actual payloads still flow over the existing pull endpoints; SSE is the wakeup signal, not the data channel. Backgrounded devices fall back to FCM.

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

# encrypt_payload is keyword-only, generates a fresh 12-byte nonce
# internally, and returns (nonce, ciphertext_with_tag). Do NOT generate
# your own nonce — using the returned one is mandatory because that's the
# one bound to the ciphertext by AES-GCM.
nonce, ciphertext = encrypt_payload(
    pairing_key=client.pairing_key,
    plaintext=plaintext,
    aad=aad,
)

client.upsert_card(
    user_id=user_id,
    plugin_id=plugin_row_id,
    card_key="order-456",
    encrypted_payload=ciphertext,
    nonce=nonce,
    sequence_number=2,
    expires_at=expires_at,
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
    card_key="order-456",
)
```

`user_id` is **required**. The canonical signed envelope binds `(sender_id, user_id, card_key)` — without `user_id` in the signature, an attacker (or a coincidence: two users with the same `card_key` under the same sender) could be deleted by mistake. The delete endpoint matches exactly that triple in the row lookup.

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

1. Install the Syncler Android app (`./gradlew :app:installDebug`).
2. Sign up + log in. The app auto-enrolls this device with the server.
3. From your backend, run a script that registers your sender and prints a pairing QR (see SDK README).
4. Tap **Pair sender** -> **Scan QR** in the Syncler app. Confirm the fingerprint matches what your script prints.
5. After confirming, the app shows your `user_id` and a `pairing_key_hex`. Copy both and feed them into your backend's `client.set_pairing(user_id, pairing_key=bytes.fromhex(...))` so it can encrypt for you.
6. Publish your plugin (`client.publish_plugin(...)`) — save the `plugin_row_id`.
7. Run `client.send_to(...)` with a test payload.
8. The server pushes an `inbox.changed` event over the SSE stream the open app is subscribed to (foreground); the phone refreshes its inbox, decrypts the message, fetches your bundle (or applies your template manifest), and shows the native row within ~1s. If the app is backgrounded, FCM wakes it and the inbox refresh happens on the next foreground.
9. Tap the row to open the plugin-rendered detail view, then tap your action button.
10. Confirm your `/api/action` endpoint received the POST.

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

If a user loses a device, they should follow this three-step procedure to protect their data:

1. **Revoke the device**: Go to **Settings -> Devices** on a trusted device and revoke the lost one. This immediately kills its network access and SSE stream.
2. **Rotate keys**: For any sender that handled sensitive data, the user should **re-pair** on a trusted device. This generates a new 32-byte AES key for that sender, ensuring that any future messages from them cannot be decrypted by the lost device (even if it were to come back online).
3. **Data Loss**: Past cached data on the lost device remains encrypted under the user's master key, but if the device itself is compromised and the master key extracted, that historical data is unrecoverable. Syncler does not support remote wipe.

---

**Seven pages.** If you needed more than that to integrate, the SDK has a DX problem — file a finding back at the Syncler team.
