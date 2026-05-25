=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 113 — Phase 10a design (multi-process plugin host)

V1.5 §10 rotation track, Phase 12 (cards/delete replay), Phase 13
(pairing revoke on compromise) all closed. Backlog empty. Picking
up Phase 10 (V1.5 runtime #1) next — multi-process plugin host
via AIDL/Binder.

Phase 10a is the design phase. The doc at
`docs/plugin-host-multi-process.md` (commit `96085ff`) locks the
AIDL contract, process lifecycle, migration plan, and security
boundaries before any code lands. Phase 10b implements against
this design.

## What the design proposes

- New `:plugin` subprocess via `android:process=":plugin"` on a
  `PluginSandboxService` (BoundService). Same UID + signature as
  the host so file sharing works; separate `ProcessRecord` for
  the OS scheduler + OOM-killer.
- Two AIDL interfaces:
  - `IPluginSandbox` (host → subprocess): `loadPlugin`,
    `unloadPlugin`, `registerHostCallback`, `dispatchHook`,
    `deliverBridgeResult`.
  - `IPluginHostCallback` (subprocess → host): `bridgeCall`,
    `onWebViewError`, `onPluginReady`, `onPluginCrashed`.
- Single `PluginLoadRequest` Parcelable carries
  `(pluginId, bundleFilePath, bundleHashHex, manifestJson,
  timeoutMillis)`. Sandbox **re-verifies** bundle hash before
  evaluating JS (TOCTOU defense).
- Capabilities (camera, network, storage, etc.) STAY in the host.
  Every privileged op crosses an AIDL boundary the host gates
  against the plugin's manifest-declared capability set.
- Single `:plugin` process for ALL plugins (no per-plugin
  isolation — that's a future "plugin-per-process" follow-up).
- Sandbox death → host fires `onPluginCrashed("process_died")`
  and rebinds on the next plugin operation.
- Migration path is a 6-step Phase 10b implementation order:
  AIDL module first, then sandbox module, then rewire
  `PluginBridge` to talk via the connection.

## Risks I want eyes on

1. **`:plugin` and host share UID + signature.** A native code
   exploit inside the WebView can read/write the host's data
   directory because the kernel boundary is identical. The
   `ProcessRecord` boundary still helps the OS scheduler, OOM-
   killer, and Android's per-process MMAP isolation, but it does
   NOT defeat a privilege-escalation that breaks out of the
   WebView sandbox into native code. A different UID
   (`isolatedProcess="true"`) would do that but breaks file
   sharing for staged bundles. Worth flagging if the security
   trade-off should be different.

2. **`PluginLoadRequest.manifestJson` carries the raw manifest
   across the IPC boundary.** Parsing happens twice — once on
   the host (to authorize capabilities pre-load) and once in the
   sandbox (to bind manifest fields to the runtime). A parser
   divergence (different JSON lib settings, locale handling,
   etc.) could let the host think `capability=X` while the
   sandbox runs the plugin under `capability=Y`. Worth flagging
   — should we ship a pre-parsed Parcelable structure instead?

3. **`oneway` AIDL semantics for hook dispatch.** The doc says
   `dispatchHook` is `oneway` so the host doesn't block on the
   sandbox. Combined with "AIDL transactions arrive on a Binder
   thread in the sandbox process" — does the sandbox's Binder
   pool queue hooks deterministically, or can we end up with
   out-of-order delivery if two hooks race? Worth flagging.

4. **`PluginSandboxConnection` singleton holding the binding.**
   Bound services in Android are reference-counted by the
   number of clients. A singleton holding the only client
   means the connection stays alive even when no plugin is
   loaded. Acceptable for V1.5 (cold-start cost is the main
   trade-off), but worth confirming.

5. **Bundle path readability.** Host stages to `getCacheDir()`.
   Sandbox reads same path. Per Android shared-UID semantics
   this works but cacheDir-clearing by the OS (low storage)
   could disappear the file mid-load. Worth flagging — should
   we keep bundles in `filesDir/` instead?

6. **Migration step ordering.** The doc proposes:
   `aidl module → sandbox module → rewire PluginBridge → strip
   in-process WebView → e2e test`. Each step is its own commit.
   Step 4 (strip in-process) is the disruptive one — does the
   ordering give us enough room to roll back if step 5 (e2e
   test) reveals a design hole?

7. **Capability bridges that need callback-into-WebView.**
   Some capabilities (e.g. NotificationBridge's
   "user-clicked-the-notification" event) need to call BACK into
   the plugin's JS. With the WebView in `:plugin`, that becomes
   a host → sandbox AIDL call. The design covers this via
   `dispatchHook`, but worth verifying the existing
   NotificationBridge / MessageBridge call paths fit cleanly.

## Output

Per reviewer, terse:

1. Verdict: GREEN / YELLOW / RED + items.
2. Anything missing in the design (Phase 10a scope, not 10b
   implementation).
3. Anything new (security concerns, lifecycle edge cases, AIDL
   contract gaps).

If dual-GREEN, I move to Phase 10b implementation against this
spec. If items come back, I revise the doc and re-consult.

**Reply text only. Do NOT call any write/mutation tool. Do not
commit, edit, or stage anything.**
