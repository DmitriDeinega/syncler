# Syncler V1 — Build Plan (Claude's draft, for Gemini to attack)

Working name: **Syncler** (from project directory). Final naming = V1.5 task.

Drafter: Claude. Attacker: Gemini. Arbiter: Codex (lead, planning phase).

## 0. Executive summary

V1 is a multi-device, end-to-end encrypted, plugin-host platform. External programs push structured messages to a user's Android phone(s); each sender ships its own JavaScript plugin that defines its own data, UI, and actions; the platform is content-blind. Pairing brokered through the server. User account = email + password under the Bitwarden zero-knowledge model.

**V1 stack:** Python (FastAPI) server + Kotlin (Jetpack Compose) Android app + JS plugin SDK. Supabase + Fly.io free tier for V1, AWS RDS+Lambda transition pre-ship.

**Estimated build:** ~140 hours of platform work. Overshoots the 80h fantasy by 60h. Cut order in §8 brings it to ~100h if forced.

**Hour-80 demo (per user Q4):** trading bot + lottery plugin both functioning end-to-end, lottery plugin written from a 6-page spec doc handed to a separate Claude Code instance (the dogfood DX test). Multi-device sync demoed across two Android phones.

## 1. Architecture

### Actors
- **User** — identified by UUID, owns 1+ devices.
- **Device** — Android phone running the Syncler app; carries decrypted master key in memory after login.
- **Sender** — any external program (trading bot, lottery app); holds a sender keypair.
- **Plugin** — JS module distributed by the sender, sandboxed in WebView, runs on each user device.
- **Server** — FastAPI + Postgres; stores opaque ciphertexts and metadata; FCM push fan-out.

### Trust model
- Zero-knowledge for content. Server never sees password, master key, or message bodies.
- Server sees metadata: account exists, plugin X installed by user U, sender S paired with user U, message exists with envelope `(sender, recipient, timestamp, min_plugin_version)`.
- Each plugin install requires explicit user permission grant **per device**.

### Data flow (sender → user device)
1. Sender's backend calls `client.send_to(user_uuid, encrypted_payload)`.
2. Server records envelope; fans out FCM to all of user's devices.
3. Each device wakes; routes to correct plugin's WebView; plugin's `onMessage()` runs.
4. Plugin decrypts payload (per-sender pairing key derived from master key + sender_id).
5. Plugin calls `platform.showNotification({title, body})`.
6. User taps notification → opens card view → plugin's `render(data)` paints UI.
7. User taps action → plugin POSTs to declared endpoint at sender's API.

## 2. Key hierarchy

```
password
  └── Argon2id(salt=user_uuid, params=high-cost)
        ├── auth_key  → SHA256 → server stores hash (login verification)
        └── master_key_wrap_key (in-memory only)
              └── decrypts encrypted_master_key (server-stored blob)

master_key (256-bit, client-generated at signup)
  ├── encrypts user state (plugin installs, pairings) → stored encrypted on server
  └── HKDF(master_key, sender_id) → pairing_key (per-sender E2E)

pairing_key
  └── AES-GCM(message body) — between sender and user devices
```

**Why the indirection:** rotating password (user changes it) re-wraps the master key with new wrap key. Master key itself stays. All encrypted data remains decryptable. Direct password→master_key would force re-encryption of everything on every password change.

## 3. Server

### Stack
- **Python 3.12 + FastAPI** — async, auto-OpenAPI, fits Python SDK story
- **PostgreSQL 16** — Supabase free tier (V1), AWS RDS (ship)
- **Blob storage** — Supabase Storage (V1), S3 (ship) — encrypted blobs only
- **Push** — Firebase Cloud Messaging
- **Hosting** — Fly.io free tier (V1), AWS Lambda + API Gateway (ship)

### API surface
- `POST /v1/auth/signup` — email, auth_key_hash, encrypted_master_key
- `POST /v1/auth/login` — returns session + encrypted_master_key
- `POST /v1/auth/devices/enroll` — register device pubkey + FCM token
- `POST /v1/pairing/initiate` — sender publishes pairing request
- `POST /v1/pairing/complete` — device scans QR, broker exchange
- `POST /v1/messages/send` — sender posts encrypted envelope
- `GET /v1/messages/inbox` — device polls/subscribes
- `POST /v1/messages/{id}/dismiss` — device reports dismiss (Gap 1)
- `GET /v1/plugins/{plugin_id}/manifest`
- `POST /v1/plugins/install` — device records install in encrypted user state

### Tables
- `users(id, email, auth_key_hash, encrypted_master_key, created_at)`
- `devices(id, user_id, public_key, fcm_token, last_seen)`
- `senders(id, public_key, name, contact, created_at)`
- `pairings(id, user_id, sender_id, encrypted_state, created_at)`
- `messages(id, sender_id, user_id, encrypted_body_pointer, min_plugin_version, sent_at)`
- `delivery_status(message_id, device_id, delivered_at, dismissed_at)`
- `plugins(id, manifest_hash, signed_bundle_url, version, sender_id, capabilities, endpoints)`

## 4. Android app

### Stack
- **Kotlin + Jetpack Compose**
- **AndroidX WebView** — one isolated instance per active plugin
- **AndroidX Security + Tink** for crypto
- **EncryptedSharedPreferences + SQLCipher** for local storage
- **Firebase SDK** for FCM
- **Foreground Service** for plugin background execution on message receipt
- **Target SDK 34**

### Modules
- `:app` — entry, navigation
- `:core:auth` — login, signup, key management
- `:core:crypto` — Argon2id, HKDF, AES-GCM
- `:core:network` — API client, WebSocket
- `:core:storage` — local encrypted DB
- `:feature:inbox` — message feed UI
- `:feature:plugin-host` — WebView management, JS bridge, sandboxing
- `:feature:pairing` — QR scan, broker exchange
- `:feature:settings` — devices, plugins, permissions

### JS bridge (exposed to plugins)
```
platform.showNotification({title, body, importance?})
platform.storage.get(key) / set(key, value)
platform.network.fetch(url, options)         // restricted to declared endpoints
platform.camera.capture()
platform.gallery.pick()
platform.file.pick({mimeTypes})
platform.location.current({accuracy})
platform.message.respond(actionId, payload)
platform.message.dismissBehavior(enum)
```

## 5. Plugin SDK (JavaScript) — example

```javascript
import { BasePlugin, DismissBehavior } from '@syncler/plugin-sdk';

export class LotteryPlugin extends BasePlugin {
  static manifest = {
    id: 'com.lottery.app',
    name: 'Lottery',
    version: '1.0.0',
    declaredCapabilities: ['network'],
    declaredEndpoints: ['https://lottery.app/api/*'],
    dismissBehavior: DismissBehavior.DISMISS_ALL,
  };

  async onMessage(payload) {
    const { numbers, draw_date } = payload;
    return platform.showNotification({
      title: 'Lottery numbers ready',
      body: `${numbers.length} sets for ${draw_date}`,
    });
  }

  render(data) { /* HTML/CSS/JS inside isolated WebView */ }

  async onAction(actionName, payload) {
    if (actionName === 'played') {
      await platform.network.fetch('https://lottery.app/api/played', {
        method: 'POST',
        body: JSON.stringify({ card_id: payload.card_id, numbers: payload.numbers }),
      });
    }
  }
}
```

## 6. Server SDK (Python)

```python
from syncler import Client

client = Client(
    sender_id='com.lottery.app',
    signing_key_path='~/.syncler/keys/lottery_private.pem',
)

client.send_to(
    user_uuid='<recipient>',
    payload={'numbers': [[1,2,3],[4,5,6]], 'draw_date': '2026-06-01'},
    min_plugin_version='1.0.0',
)
```

Surface: `Client(...)`, `client.send_to(...)`, `client.wait_for_action(message_id, timeout)`, `client.list_paired_users()`, `client.create_pairing_qr()`, `client.complete_pairing(token)`.

## 7. Milestones

| # | Milestone | Hours | Risk |
|---|---|---:|---|
| M1 | Server scaffold: FastAPI, DB schema, auth, JWT sessions | 18 | low |
| M2 | Crypto layer: Argon2id, key hierarchy, AES-GCM, HKDF | 12 | medium |
| M3 | Android shell: Compose UI, login/signup, secure local storage, device enrollment | 15 | low |
| M4 | Plugin SDK + WebView host: BasePlugin, JS bridge, sandboxing, per-plugin isolation | 28 | **high** |
| M5 | Push + delivery: FCM data messages, encrypted payload pipeline, background service | 18 | **high** (Android battery restrictions) |
| M6 | Pairing broker: QR, scan flow, key exchange, sender pubkey registration | 14 | medium |
| M7 | Multi-device sync: Gap 1 (dismiss enum), Gap 3 (min_plugin_version), user state sync | 10 | medium |
| M8 | Trading bot integration + lottery spec doc (6 pages cap) | 8 | low |
| M9 | Lottery plugin integration (dogfood DX test) | 4 | depends on spec quality |
| M10 | Bug fixing + polish | 13 | — |
| **Total** | | **140** | overshoots 80h by 60h |

## 8. Cut order if budget breaks (target = 100h)

1. **M10 polish** drop to triage only → −8h
2. **M9 dogfood test** defer to post-V1 → −4h
3. **M7 dismiss sync** ship V1 with local-only dismiss → −5h
4. **M5 background execution** ship with static-text notifications, dynamic in V1.5 → −8h
5. **M3 device enrollment** ship single-device V1, multi-device V1.5 → −8h (reverses G1 commitment — only if user re-decides)

Cuts 1–3 = 100h target hit. Cut 4 acceptable. Cut 5 is nuclear and reverses a committed decision.

## 9. Risks (ordered by severity)

1. **FCM data messages + Foreground Service for encrypted dynamic notifications.** Android battery-optimization restrictions on newer Android versions (12+) may delay or block background execution. Mitigation: extensive testing on Pixel + Samsung devices early; fallback to static-text notifications if blocked.
2. **WebView per-plugin isolation.** Android WebView is shared; isolating storage, cookies, and JS contexts per plugin requires careful handling. Risk: side-channel attacks if isolation incomplete. Mitigation: separate WebView instance per plugin, distinct user-agent and storage paths, disable JS-to-host bypass.
3. **Key hierarchy correctness.** Crypto bugs are silent. Mitigation: use Tink (Google) on Android and `cryptography` (Python); write tests against Wycheproof test vectors; no custom crypto.
4. **Multi-device key sync race.** New device login before previous device finished writing user state. Mitigation: explicit pull-on-login with version vector on encrypted state blob.
5. **Spec doc DX test.** 6-page cap may be too tight or too loose. Mitigation: write the doc, integration-test with the lottery's Claude Code, iterate on the doc until integration succeeds. The doc length is itself the unit of measurement.
6. **Plugin update propagation.** Q23 said prompt-to-update per device. If user updates Phone 1 but not Phone 2, new-format messages on Phone 2 hit Gap 3 placeholder. Accepted UX cost.

## 10. Decisions I made that Gemini should attack

- **FastAPI** vs alternatives (Django, Flask, Express on Node)
- **Postgres** vs SQLite (V1 single-tenant cheap path) or DynamoDB
- **Supabase + Fly.io free tier** for V1 vs user's "AWS free tier" answer — I'm proposing Supabase for V1 specifically because it covers DB + Auth + Storage with less plumbing; AWS pre-ship per user
- **Tink** on Android, `cryptography` on Python — vs libsodium / PyNaCl
- **Plugin bundling**: signed single .js bundle with manifest header — vs zip + manifest.json + entry.js
- **WebView per plugin** vs single shared WebView with iframe sandboxing
- **M4 at 28h** — this might be 35–45h honestly
- **Spec doc cap at 6 pages** — arbitrary; defensible only if it actually works
- **Hour estimates overall** — every M could be ±30%
- **Cut order assumes M5 can be cut to static notifications** — if Q18 (code-based dynamic) is non-negotiable, M5 can't be cut

## 11. Disposition of remaining backlog questions

- **Spec-doc page cap (Q13):** 6 pages picked.
- **Scheduled sending:** sender's job, not platform's (per G2 philosophy).
- **Naming:** Syncler placeholder, V1.5 final.
- **Marketplace review rules:** V1.5 problem.

## Ready for Gemini attack

Specific pressure points wanted: (a) hour estimates especially M4/M5, (b) the cut order, (c) FCM + Foreground Service feasibility on modern Android, (d) WebView per-plugin isolation, (e) key hierarchy correctness, (f) Supabase-vs-AWS for V1.
