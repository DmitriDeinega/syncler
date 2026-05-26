=================================================================
ABSOLUTE INSTRUCTION — REVIEW MODE.
No write/mutation tool. Reply text only.
Do NOT commit, edit, write, or stage anything. Read freely.
=================================================================

# Consultation 138 — Phase 12 design v2 (post-137)

Triad 137: codex + gemini both YELLOW with a convergent FIX
list. v2 (`docs/plugin-capability-expansion.md`, commit
0daf4c2) addresses the load-bearing items. Goal of this round
is to greenlight or surface remaining blockers before
implementation kicks off.

## v2 deltas vs v1

1. **Binary transport via handles.** Camera/gallery/file
   results no longer return bytes inline. Host stages under
   `cacheDir/plugin-capability/<sandbox_token>/{call_id}.bin`
   and returns `{handle, name, mime, sizeBytes, expiresAtMs}`.
   Plugin reads via new `ctx.fileBytes(handle, offset, length)`
   (256 KB chunks max) and `ctx.releaseHandle(handle)`. 16 MB
   total size cap; 32 concurrent handles per plugin; 5 min
   TTL; per-token wipe on plugin unload; per-handle wipe on
   release/expiry/app-start.

2. **Location capability split.** `location` removed; new
   pair `location.coarse` + `location.fine`. The bridge
   returns `{lat, lon, accuracyMeters, precision}` where
   `precision` reflects what the OS actually granted (a
   plugin requesting fine whose user chose "approximate"
   sees `coarse`). Legacy `location` rejected at publish
   time — V0.1 has no shipped manifests using it.

3. **ActivityResultRegistry at Application level.**
   No more WeakReference-to-Activity. The registry survives
   config change; activities re-bind callbacks on `onCreate`.

4. **Skip in-app prompt for camera/gallery/file** (gemini's
   stronger UX position). The OS picker / camera UI IS the
   consent surface for those three; the host launches the
   intent directly. `location.*` keeps the in-app prompt
   because it's a silent service call. Grant row inserted
   ONLY after BOTH plugin grant AND OS permission succeed
   (codex's "insert after full success").

5. **Continuation lifecycle state machine** (codex missing
   item). States: created → waiting_for_grant →
   waiting_for_os_perm → launched → completed / cancelled /
   timed_out. Timeouts: 30s for prompt states, 5 min for
   launched. Per-token cancellation propagates to all
   pending continuations on `onPluginUnloaded` /
   IBinder.DeathRecipient.

6. **Concurrency: one active activity-result op at a time.**
   Second concurrent request returns `capability_busy`.
   `location.*` exempt (they don't launch activities).

7. **IBinder.DeathRecipient on sandbox connection.** Sandbox
   crash mid-Activity-result cleans up continuation + temp
   files (gemini missing item).

8. **Mid-flight revocation re-check** (gemini missing item).
   After the Activity result returns but BEFORE staging /
   returning, re-check `plugin_capability_grants` + OS
   permission. Revoked-during-pick returns
   `capability_denied`.

9. **SAF content-URI immediate-copy** (gemini missing item).
   The `FLAG_GRANT_READ_URI_PERMISSION` lifetime is bound to
   the activity-result delivery; host opens InputStream and
   copies to staging synchronously before the callback
   returns.

10. **Per-invocation audit retained** (codex over gemini).
    Forensic value > storage cost; aggregation is a UI
    concern, not a source-of-truth concern. No coordinates
    or filenames in audit rows.

11. **NATIVE_SDK_ABI bump 1 → 2.** SDK return types change
    shape (handle-bearing instead of bytes-bearing). Any
    Phase 11-era native plugins need re-publish. V0.1 has
    none in the wild.

12. **SDK error taxonomy.** Eleven codes spelled out:
    `capability_not_declared` / `capability_denied` /
    `capability_prompt_rate_limited` / `os_denied` /
    `no_foreground_activity` / `capability_busy` /
    `cancelled` / `timeout` / `result_too_large` /
    `invalid_handle` / `io_error`.

13. **Session-cooldown for denied prompts** (gemini #2).
    Re-prompt within the same foreground session returns
    `capability_prompt_rate_limited` immediately; cleared on
    host main Activity onResume. Two consecutive denials
    within a single session = permanent block until manual
    re-grant in settings (Android's "Don't ask again").

## What I'm asking for

Greenlight conditions from 137 were:
1. Replace JSON/base64 bytes with staged opaque handles. ✓
2. Split location into coarse/fine. ✓
3. Define Activity-result continuation lifecycle across
   config change, cancellation, timeout, concurrency. ✓
4. Clarify grant insertion timing around OS permission
   success. ✓ ("insert after full success")
5. Use AndroidX Photo Picker as the primary gallery path. ✓
   (`PickVisualMedia` unconditionally, no SDK_INT branching)

For each of the above, confirm v2 actually closes the item or
flag what's still under-specified.

Additionally:

A. **Handle string scoping enforcement.** Spec says "handle
   lookup requires (plugin_row_id, handle) match so Plugin A
   guessing Plugin B's handle still fails the per-plugin
   scoping check." Is that enough, or should the handle also
   be cryptographically bound (HMAC over plugin_row_id +
   call_id)? The handle itself is a UUID; brute-forcing a
   match is 2^128. Probably fine?

A 16-bit dispute resolution prefix is not in the spec; want
me to add one?

B. **Handle TTL clock source.** `expiresAtMs` is set by
   `System.currentTimeMillis() + 5 min`. If the system clock
   jumps (user changed timezone, NTP correction), the TTL
   becomes meaningless. Should it be `SystemClock.elapsedRealtime`
   instead, with the wall-clock value just stored for the
   plugin's display purpose?

C. **fileBytes chunk re-read semantics.** A plugin can call
   `fileBytes(handle, offset=0, length=256KB)` repeatedly.
   The spec doesn't say whether the host's read is stateful
   (each call returns the next chunk) or stateless (each
   call seeks to offset). I intended stateless seek-and-read.
   Confirm or change?

D. **Camera tempfile lifecycle interplay with handle.** The
   v1 spec had ACTION_IMAGE_CAPTURE writing to a tempfile,
   then host reads bytes + deletes. v2 moves to handles:
   the host reads from the camera-tempfile into the staging
   file, then deletes the camera-tempfile. Plugin holds the
   handle to the staging copy. Confirm camera-tempfile is
   deleted immediately after the staging copy completes, not
   on handle release?

E. **Audit row schema.** I haven't specified the Room
   schema for `plugin_capability_audit`. Should it carry
   `outcome` (the error code from the taxonomy, or
   `success`), `at_ms`, and `sandbox_token`? Anything else
   forensically valuable that doesn't leak privacy?

F. **NATIVE_SDK_ABI bump messaging.** Existing Phase 11
   plugin manifests declare `native_sdk_abi: 1`. With ABI 2
   shipping in Phase 12, the sandbox rejects ABI 1 plugins
   with `unsupported_sdk_abi`. The error code already exists.
   Do we want to additionally surface a UI banner in settings
   ("This plugin needs an update — incompatible with Syncler
   X.Y") or rely on the existing load-failure UI?

G. **Server-side capability validation.** When the location
   split lands, do we also reject manifests that declare
   BOTH `location.coarse` and `location.fine`? Plugin author
   intent is probably to fall back, but I'd argue they
   should just declare `location.fine` (which the OS may
   downgrade) and check the returned `precision`. Mandatory
   single-granularity declaration?

H. **Photo Picker single vs multi.** `PickVisualMedia`
   returns one URI; `PickMultipleVisualMedia` returns
   multiple. The native bridge currently returns
   `GalleryResult(items: List<...>)`. Should
   `platform.galleryPick(maxItems = 1)` use the single
   contract and `maxItems > 1` use multi, with a cap of,
   say, 10? Or always use multi with a max?

## Greenlight target

If 138 returns OK / NIT, I move straight to implementation
(steps 3–17 in the v2 spec). If FIX/DESIGN on any of A–H or
on whether 137's items #1–5 are actually closed, I write v3.

Skip cosmetics; flag substance.
