=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 140 — V2 #11/#12/#13 closeout review

V2 #10 (Phase 12) shipped end-to-end with the full triad cycle
(137 design → 138 design v2 → 139 mid-track YELLOW + fixups
landing at 7b3aee2 → ee11ace ROADMAP). Subsequent V2 items were
implemented WITHOUT the design / mid-track pattern; this is the
retroactive closeout review covering those three items in one
shot before V3 begins.

This review is essentially a *post-impl* triad — same v0.1 dev
posture, but the verdicts here might mean "FIX before V3" rather
than "FIX before commit" since the code is already shipped.

## Commits in scope

| Commit  | Item     | Content |
|---------|----------|---------|
| 02123ad | V2 #11   | message.respond + showNotification request/response |
| baf5174 | V2 #12   | template layouts (compact_row / score_card / stat_grid) |
| 0d13d43 | V2 #13   | script_fast renderer — pipeline + Android scaffold |

## V2 #11 — message.respond + showNotification

### Shape shipped

`MessageBridge.respond(actionId, endpoint, payload)`:
- args parsed from bridgeCall JSON: requires `actionId` (string)
  + `endpoint` (string). Payload is JSON-encoded.
- Endpoint gated by `EndpointMatcher.matches(endpoint,
  plugin.manifest.declaredEndpoints)` — same glob grammar as
  `platform.network.fetch`.
- Release-build cleartext rejection mirrored from
  TemplateActionRunner / NetworkBridge (https://, or http://
  in debug builds only).
- Synchronous POST via OkHttp; returns `{"status", "body"}`
  JSON to the plugin.
- Audit-log entry per invocation via `auditLogger.denied(...)`
  with outcomes "respond_ok" / "respond_NNN" / "respond_threw"
  / "endpoint_not_declared" / "non_https_endpoint" /
  "invalid_args".

`TemplateActionRunner.postWithResponse(endpoint, payloadJson)`
adds a same-shape request/response variant alongside the
existing fire-and-forget `.post()` for inbox-UI callers.

`NotificationBridge.show(title, body, groupId, actionId,
actionLabel)`:
- New optional `actionId` + `actionLabel` args.
- If `actionId` present, the notification's `contentIntent` is
  set to `PendingIntent.getBroadcast(...)` pointing at a new
  `NotificationActionReceiver` (BroadcastReceiver registered
  process-once with `RECEIVER_NOT_EXPORTED` on API 34+, same-
  package only).
- An additional `addAction(label, pending)` button appears if
  `actionLabel` is supplied.
- Receiver calls `PluginRegistry.dispatchAction(pluginId,
  actionId, payloadJson)` — which routes through
  `PluginInstance.dispatchHook("onAction", payloadJson,
  actionId)`.

`PluginRegistry.dispatchAction` is a new no-op-if-not-loaded
helper.

### What I'm worried about

1. **`endpoint` arg trust.** The plugin passes the endpoint
   string. EndpointMatcher gates it against `declaredEndpoints`
   from the manifest. Is that enough, or should the host
   look up the action.id in the manifest's template.actions
   and use the registered endpoint instead, ignoring the
   plugin-supplied one entirely?
2. **Audit-log key reuse.** I'm routing success + failure
   through `auditLogger.denied(...)` (which is a misleading
   name; existing pattern uses it for all audit). Worth
   renaming to `record(...)` for clarity, or accepted?
3. **No timeout / retries on the OkHttp call.** A slow sender
   endpoint will block the plugin's coroutine for OkHttp's
   default ~10s connect + 10s read. Acceptable, or should
   message.respond carry an explicit timeoutMillis arg?
4. **NotificationActionReceiver is process-once registered.**
   The `init {}` block runs whenever a `NotificationBridge`
   is constructed; with the `@Volatile receiverRegistered`
   guard only the first construction actually registers.
   Process death unregisters it; next instantiation re-registers.
   Race-condition-safe?
5. **`PendingIntent` flags.** Using
   `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`. The
   `requestCode` is the notification id (hashed plugin+title+
   body). Two notifications with the same hashed id would
   share the PendingIntent — is that the right behavior, or
   should each get a unique requestCode?
6. **No de-duplication on the receiver.** If a user taps the
   notification AND the action button, the plugin gets two
   `onAction` calls. Worth deduplicating, or is "exactly the
   number of taps the user did" the right contract?
7. **No upgrade of the inbox-UI flow.** The existing template
   card's action button still calls `TemplateActionRunner.post`
   (fire-and-forget) instead of routing through `plugin.onAction`
   first. I noted this as a follow-up in the commit message
   but it's the "ship the bridge contract first, wire the UI
   later" trade-off. Is that acceptable, or is the UI flow
   the real V2 #11 deliverable and I shipped half of it?

## V2 #12 — template layouts

### Shape shipped

Three new server-validated + Android-rendered layouts beyond
`standard_card`:

- `compact_row` (req: `leading`; opt: `trailing`, `subtitle`)
- `score_card` (req: `score`, `label`; opt: `caption`)
- `stat_grid` (req: `title`; opt: `stat1_label/value` through
  `stat4_label/value` — 4 tiles in a 2x2 Compose grid)

Server (`schemas.py` + `services/plugins.py`):
- `_TEMPLATE_LAYOUTS` frozen-set extended.
- Layout → required/optional field maps gain the new entries.
- `_validate_template` loops the per-layout required set,
  preserving the legacy "missing required field: title"
  wording for standard_card-specific test compatibility.
- 8 new tests (`test_v2_12_layouts.py`); full server suite
  188 → 196.

Android (`feature/inbox/.../TemplateCard.kt`):
- Three new `@Composable private fun` renderers + a
  `StatEntry` data class.
- Layout dispatch in `TemplateCard`'s
  `when (template.layout)` block.
- Existing `standard_card` behavior unchanged.

SDK (`sdk-plugin/src/manifest.ts`):
- `layoutRequiredFields` + `layoutOptionalFields` mirror the
  server's new sets in lockstep.

### What I'm worried about

8. **Compose rendering not unit-tested.** I deferred Compose-
   layer tests (the project doesn't have a Compose test runner
   wired). Is the layered server-validator + JSONPath-resolver
   coverage enough, or does V2 #12 really need golden render
   tests (as the spec explicitly calls out)?
9. **`stat_grid` field naming.** Numbered slot names
   (`stat1_label`, `stat1_value`, ...) feel clumsy compared
   to a nested-array shape like `{stats: [{label, value},
   ...]}`. The current schema is flat because the manifest
   `fields` map is `Map<String, {path}>` — nested structures
   would need a schema extension. Accept the flat shape, or
   should this prompt a schema-level change before V3?
10. **No layout pagination / overflow handling.** A stat_grid
    with 5+ stats just silently drops the extras (the
    renderer hard-codes `listOf(1,2,3,4)`). Server validator
    should probably reject `stat5_*` field names; I didn't
    add that. Strict?
11. **`compact_row` button placement.** Actions render as
    full-width buttons in a column below the row — visually
    the same as `standard_card`. For a truly compact
    single-line layout, actions arguably belong inline or
    not at all. Acceptable, or worth a layout-specific
    action style?

## V2 #13 — script_fast renderer (scaffold)

### Shape shipped

- Server `PluginPublishRequest.renderer` Literal extended to
  include `"script_fast"`.
- `publish_plugin` accepts it (no template / entry_class
  requirements).
- SDK `manifest.ts` renderer union widened + validator
  accepts the new value.
- Android `SandboxRouter.loadPlugin` rejects `script_fast`
  with `IllegalStateException("script_fast_not_available")`
  via the new `SCRIPT_FAST_NOT_AVAILABLE` constant.
- The actual QuickJS/Javy execution engine is V0.2 work.

### What I'm worried about

12. **Shipping the renderer name before the engine.** A
    publisher can declare `renderer: "script_fast"` today and
    devices reject the load. Is that the right "publishers
    can prepare, devices catch up" pattern, or should the
    server reject `script_fast` until the engine is ready?
13. **No engine choice documented.** QuickJS vs Javy vs
    something else — I didn't lock the choice. Worth
    deciding now via a V2 #13 design triad, or wait until
    the V0.2 implementation kicks off?
14. **No SDK guidance for plugin authors.** A plugin author
    has no way to know what API surface their script_fast
    bundle should target (DOM unavailable, only platform.*
    globals?). Should the SDK ship a typed `platform`
    interface that's renderer-aware?

## General questions

15. **Skipped-triad-during-impl pattern.** I shipped these
    three items straight to code without the design /
    mid-track triad cycle. Phase 12 had triads 137/138/139;
    V2 #11-#13 had none. Was that a mistake on every item, or
    is the depth of the work the deciding factor (Phase 12
    was deep enough to warrant triads; #11/#12/#13 were
    incremental enough to skip)?

16. **Roadmap reordering.** With #11/#12/#13 shipped (mostly),
    V3 starts next. V3 #14 (WebSocket) is substantial — it
    deserves a real design triad. V3 #15/#16/#17 build on
    #14. Should the V3 design triad cover all four, or just
    #14 with #15-#17 as follow-on triads?

## What I'm asking for

Per-item verdict (OK / NIT / FIX / DESIGN) for each numbered
concern, plus anything you spot in the referenced code that's
worth flagging before V3. Be especially aggressive on:
- V2 #11's auth model (endpoint trust, PendingIntent flags,
  receiver registration).
- V2 #12's schema choice (numbered stat slots vs nested).
- V2 #13's contract clarity (is it even useful to ship the
  renderer without the engine?).

Bottom line I want: a clear list of what to fix before V3
starts vs what's defensible as-is.
