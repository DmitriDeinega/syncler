=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 160 — five deferred quick-wins (design pre-work)

After V3+V4 + bugfix cycles closed at triad 159, five
items remain in the "deferred" list that genuinely don't
need human-in-the-loop UX review. Doing them now closes
the partner-facing surface and removes the worst of the
TODO-debt in the integration guide. UI work (V4 #18
Compose screens, V4 #19 Plugins tab) stays deferred — it
needs the user's eyes.

v0.1 dev posture preserved.

## Item 1 — Python SDK `client.live_push(...)` helper

**Why**: integration guide §12 currently tells Python
integrators to drive the raw POST because the SDK has no
typed helper. That's an integrator papercut.

**Proposed shape**:

```python
def live_push(
    self,
    *,
    plugin_row_id: str,
    channel: str,
    envelope: dict[str, Any],
) -> int:
    """V3 #14 — push a V2 envelope into the live channel.

    Server wraps in a `message` frame and fans out to every
    paired user's open WS for `plugin_row_id`. Returns the
    server's reported `delivered` count (per-user-topic, NOT
    per-device — best-effort telemetry).

    `channel` MUST match `^[a-zA-Z0-9._-]+$` (server rejects
    400 if not). `envelope` is the V2 envelope dict built
    by the SDK's seal helpers — pass it as-is.
    """
    self._require_sender_id()
    plugin_row_id = _canon_uuid(plugin_row_id)
    body = {"channel": channel, "envelope": envelope}
    resp = self.session.post(
        f"{self.base_url}/v1/live/plugin/{plugin_row_id}/push",
        json=body,
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json().get("delivered", 0)
```

Q: I think `envelope` is the right shape (the caller has
already sealed it via `seal_v2_envelopes`). Or should the
helper take `plaintext + recipients` and do the sealing
internally like `upsert_card` does? I lean toward "take
the envelope as-is" because the live channel doesn't
constrain the envelope's `envelope_kind` the way upsert
does.

## Item 2 — `live_inbound_url=` kwarg on `publish_plugin`

**Why**: TypeScript manifest exposes `liveInboundUrl`;
Python doesn't — partners using the Python SDK can't set
the webhook URL through the typed API.

**Proposed shape**: extend `Client.publish_plugin(...)`
with `live_inbound_url: str | None = None`. If provided,
inject it into the publish body as `live_inbound_url`
(snake_case to match the server schema). The canonical
signing input doesn't change — the field is opt-in and
server-side validated.

Need to verify the server's `PluginPublishRequest` schema
accepts this field (it does — V3 #14 step 6 added it).

## Item 3 — Server `GET /v1/server/webhook-public-key` endpoint

**Why**: today partners must obtain the server's webhook-
signing Ed25519 public key OUT-OF-BAND from the operator.
The integration guide flags this as a known gap. A
read-only public endpoint solves it.

**Proposed shape**:

```python
@router.get("/server/webhook-public-key")
async def webhook_public_key() -> dict[str, str]:
    """Returns the server's Ed25519 public key partners use
    to verify `X-Syncler-Signature` on webhook deliveries
    from the live-channel forwarder.

    Unauthenticated (the key is public information by
    design). Returns 404 when the server is missing its
    signing seed (production misconfig)."""
```

Body:

```json
{
  "public_key_base64": "<base64 of 32-byte Ed25519 public key>",
  "algorithm": "ed25519"
}
```

Q: is unauthenticated the right call? The key is meant to
be widely known (otherwise partners can't verify
deliveries). I think yes. Any objection?

Q: where in the routes module should this live? My
inclination: `server/app/routers/live.py` (it's part of
the live-channel surface) OR a new `routers/server.py`
for things partners discover about the deployment. The
latter feels right — there will be more such endpoints
(server signing key fingerprint, build version, etc.) and
they don't belong nested under `/v1/live/...`.

## Item 4 — Android factory test for the JWT provider plumbing

**Why**: triad 159 codex NIT #6: "Android JWT-provider
plumbing is small, but one focused factory/clientFactory
test is worth adding because omission degrades live into
no_session."

**Proposed shape**: a JVM unit test (`PluginLoaderTest`
or a new sibling file) that constructs the factory both
WITH and WITHOUT a `Session` and asserts the
`deviceJwtProvider` lambda behaves correctly:

- With a Session that returns "fake-token" → provider
  returns "fake-token".
- With a Session that returns null → provider throws
  `LiveChannelException("no_session", ...)` with the
  "locked / signed out" reason.
- With no Session wired (default arg null) → provider
  throws `LiveChannelException("no_session", ...)` with
  the "no Session wired" reason — distinct audit-log key.

Q: this can live as a unit test that constructs only the
`LiveChannelClientFactory` (not the whole PluginLoader).
The factory is an anonymous object inside
`PluginLoader.android()` though — to test it directly,
I'd extract it to a named class first. Worth the
refactor, or accept the existing anonymous-object shape
and instead test through `PluginLoader.android()`'s
constructed instance?

## Item 5 — V4 #18 affected-senders inference

**Why**: `LostDeviceFlowViewModel.State.Done.affectedSenders`
ships as `emptyList()` today. The doc promises a re-pair
handoff CTA backed by inference; v0.1 surfaces "Review
paired senders" generically.

**Proposed shape**: a small helper in `feature/inbox`
(reading inbox row history is its responsibility) that
walks the `InboxRepository`'s state and returns the
distinct `senderId`s for messages whose `recipientEnvelopes`
included the revoked device's deviceId.

```kotlin
fun affectedSendersForDevice(
    items: List<InboxItem>,
    revokedDeviceId: String,
): List<String>
```

Then in `LostDeviceFlowViewModel.performRotation`, on
success, call this helper with the in-memory inbox state
+ the revoked device ID to populate `Done.affectedSenders`.

Q: `InboxItem` has `senderId` + (post V2) the recipient
envelopes are buried inside the V2 envelope shape, not
on the in-memory `InboxItem` directly. The simplest
v0.1 inference: "any sender whose messages this device
has decrypted on the current device" — that's a less
precise but easier metric. Show all `senderId` distinct
across `_items.value`. The actual lost device's
recipient-envelope set isn't directly accessible from
the trusted device's inbox.

Better proposal: just list ALL `paired_senders` for the
user (we know what they are from `PairedSenderStore`) and
let the user pick which to re-pair. The "affected
inference" was always best-effort; the safer UX is
"here's every sender — re-pair any whose pairing was
only ever set up on the lost device".

Q: agree with that simplification, or do you want me to
push for the more precise inference (which needs
inspecting V2 envelope recipient sets, possibly via a
server endpoint)?

## What I'm asking for

Per-item: verdict (OK / NIT / FIX / DESIGN) on my
proposed shape + answers to the Qs.

Plus: anything I missed that a partner project would
actually hit during integration but neither this list
nor the integration guide covers today.

Cap at 500 words.
