# Plugin Capability Expansion (V2 #10, Phase 12)

V1's plugin bridge already supports `network` and `notification`.
Phase 9b shipped `storage` (SQLCipher-backed K/V per plugin).
V2 #10 adds the four remaining capabilities surfaced in the SDK
runtime contract for native plugins (`docs/plugin-host-native-kotlin.md`)
and exposed via the JS `platform.*` bridge:

- `camera` — capture a photo via `ACTION_IMAGE_CAPTURE` or
  CameraX, written to a host-owned temp file.
- `gallery` — pick existing media via AndroidX `PickVisualMedia`
  (Photo Picker on API 33+, backport fallback for 26–32).
- `file` — pick a non-media document via `ACTION_OPEN_DOCUMENT`.
- `location.coarse` / `location.fine` — single-fix via Fused
  Location Provider; granularity is part of the capability name.

> Design history: this is **spec v2** (triad 137 YELLOW with
> convergent FIX list across codex + gemini). v1 lived briefly
> on disk between commits 30602b7 and the v2 rewrite; the v1
> design returned bytes via base64-over-Binder (broken at
> ~1 MB), used WeakReference-to-Activity (drops on config
> change), and lumped location into one capability.

Each capability lands with:
1. **Per-grant user prompt where the OS picker UI itself is
   NOT the consent surface** — for `location.coarse` /
   `location.fine` the host shows an in-app dialog explaining
   what's about to happen. For `camera` / `gallery` / `file`,
   the OS picker / camera UI itself is the consent surface;
   the host does NOT show an additional in-app prompt for
   those (per gemini 137 review). Grant is per-(device, plugin
   row, capability); revocable from the settings sheet.
2. **OS permission acquisition** — for `camera` and the
   `location.*` family, the host launches the runtime
   permission request via the ActivityResultRegistry contract.
   The host inserts the per-plugin grant row ONLY after BOTH
   the plugin grant AND the OS permission succeed (codex
   137 #3 — "insert after full success").
3. **Audit log entry per invocation** (codex 137 #8). The
   audit table is the forensic source of truth; retention +
   UI aggregation are separate concerns. Coordinates and file
   names are NOT logged.

   **Schema (triad 138 E, both reviewers):**

   ```sql
   CREATE TABLE plugin_capability_audit (
     id              INTEGER PRIMARY KEY AUTOINCREMENT,
     plugin_row_id   TEXT NOT NULL,
     capability      TEXT NOT NULL,
     sandbox_token   INTEGER NOT NULL,
     call_id         TEXT NOT NULL,
     at_ms           INTEGER NOT NULL,  -- wall-clock for display
     duration_ms     INTEGER NOT NULL,  -- elapsedRealtime delta
     outcome         TEXT NOT NULL,     -- "success" | SDK error code
     surface         TEXT NOT NULL,     -- "activity_result" | "service_call"
     size_bytes      INTEGER,           -- staged size for camera/gallery/file
     item_count      INTEGER,           -- for gallery
     precision       TEXT,              -- "coarse" | "fine" for location
     mime_claimed    TEXT,              -- provider-stated MIME
     mime_detected   TEXT               -- magic-byte sniff result
   );
   ```

   No filename, no URI, no coordinates, no handle string.
4. **Bridge return shape** — handles, not bytes (see "Binary
   transport" below). Plugin's typed Kotlin `Result<...>` or
   JS promise resolves with the handle + metadata; the plugin
   then issues a follow-up `platform.fileBytes(handle, …)`
   call to read the staged content.

## Capability matrix

| Cap               | Manifest declares      | OS perm needed     | Result kind                    |
|-------------------|------------------------|--------------------|--------------------------------|
| `camera`          | yes                    | CAMERA             | handle (image bytes)           |
| `gallery`         | yes                    | none (Photo Picker)| array of handles (images)      |
| `file`            | yes                    | none (SAF)         | handle (file bytes + filename) |
| `location.coarse` | yes                    | ACCESS_COARSE_LOCATION | inline `{lat,lon,acc,precision}` |
| `location.fine`   | yes                    | ACCESS_FINE_LOCATION   | inline `{lat,lon,acc,precision}` |

`location.*` results are tiny and bypass the handle path. The
returned `precision` is `"coarse"` or `"fine"` reflecting what
the OS actually granted — a plugin requesting `fine` whose user
chose "approximate" sees `precision: "coarse"`.

## Binary transport (handles)

**This is the load-bearing design choice both reviewers
flagged.** Camera / gallery / file return arbitrarily-large
bytes that cannot ride the JSON+base64+Binder path (~1 MB
cap, multiplied memory pressure across host + sandbox + JS
layers).

Instead, the host stages the bytes into a cache subdirectory
and returns an opaque handle:

```json
{
  "handle": "cap-7f3a-…",
  "name": "IMG_20260526.jpg",
  "mime": "image/jpeg",
  "sizeBytes": 2_481_392,
  "expiresAtMs": 1748284800000
}
```

The plugin then reads via a new bridge call:

```kotlin
ctx.fileBytes(handle, offset = 0L, length = 65536L): Result<ByteArray>
```

**`fileBytes` semantics (triad 138 C — both reviewers
confirm):** stateless seek-and-read. Each call reads
`[offset, offset + length)` from the staged file; host opens
RandomAccessFile, seeks, reads, closes. No cursor / stream
state is carried between calls.

- `offset < 0` or `length < 0` → `invalid_handle`.
- `length > 256 * 1024` → `invalid_handle`.
- `offset >= sizeBytes` → `{bytes: "", eof: true}`.
- Short read allowed only near EOF (returns whatever fits
  in `[offset, sizeBytes)` with `eof: true`).
- Repeated reads at the same offset return identical bytes
  until release / expiry.

Plugin holds the handle until it explicitly releases via
`ctx.releaseHandle(handle)` or the host garbage-collects
expired handles.

Staging file layout under `cacheDir/plugin-capability/`:
- One subdir per `sandbox_token` so a process-death cleanup
  can wipe orphans by token.
- Filename = `{call_id}.bin`; metadata in a sibling
  `{call_id}.meta.json`.

Caps:
- Max handle size: 16 MB. Larger picks return
  `{"error":"result_too_large"}` before staging.
- Max concurrent handles per plugin: 32.
- Handle TTL: 5 minutes after creation OR plugin unload,
  whichever first.

**TTL clock (triad 138 B):** TTL enforcement uses
`SystemClock.elapsedRealtime()` and an internal
`expiresAtElapsedMs` field. The wall-clock `expiresAtMs`
returned to the plugin is for display only — host cleanup +
validity checks never read it. This survives NTP shifts and
manual timezone changes that would invalidate wall-clock TTLs.

Host cleanup hooks:
- App start: wipe `plugin-capability/` entirely.
- Plugin unload (`onPluginUnloaded`): wipe that token's subdir.
- Handle expiry tick (60s): delete handles past `expiresAtMs`.
- Explicit `releaseHandle(handle)` call: delete that handle.

## Grant model

Capability grants live in a new Room table `plugin_capability_grants`:

```sql
CREATE TABLE plugin_capability_grants (
  plugin_row_id  TEXT NOT NULL,
  capability     TEXT NOT NULL,
  granted_at_ms  INTEGER NOT NULL,
  PRIMARY KEY (plugin_row_id, capability)
);
```

**Two grant-check sequences** keyed on capability category
(triad 138 codex blocker fix — v2's single sequence
contradicted itself by re-checking the grant row before it
existed for first-time gallery/file).

### Category A: UI-consent capabilities (`camera`, `gallery`, `file`)

The OS picker / camera UI itself is the consent surface. No
in-app prompt; the grant row's purpose is purely "remember
that the user has consented at least once for this plugin"
so we can show it in settings + know if it's been revoked.

First invocation:
1. Manifest declares the capability? (existing check)
2. Plugin still installed + session alive? (always)
3. For `camera`: OS permission state.
   - If denied: launch OS runtime permission request via
     ActivityResultRegistry. Continue only on grant; on
     denial return `os_denied`.
4. Launch the picker / camera intent. The continuation is
   the "pending consent" — there is no grant row yet.
5. On result delivery: re-check manifest still declares it
   AND plugin still installed AND OS permission still
   granted. (Do NOT re-check the grant row — it doesn't
   exist yet; that's what we're about to create.)
6. Stage bytes → return handle.
7. Insert the grant row.

Subsequent invocation (grant row exists):
1. Manifest declares + plugin installed (always).
2. OS permission still granted (camera only).
3. Launch the picker / camera intent.
4. On result delivery: re-check manifest + grant row still
   present + OS permission still granted. (Grant row
   present means "user previously consented in settings"; a
   row deletion via the settings sheet between launch and
   result = user revoked mid-flight → return
   `capability_denied`.)
5. Stage bytes → return handle.

### Category B: Service-call capabilities (`location.coarse`, `location.fine`)

No OS picker UI; the host fires a Fused Location Provider
call directly. The in-app prompt is the consent surface.

First invocation:
1. Manifest declares + plugin installed (always).
2. Show in-app `CapabilityGrantDialog`. If denied or
   timed out: return `capability_denied` /
   `capability_prompt_rate_limited` per cooldown.
3. Request OS permission via ActivityResultRegistry. On
   denial: return `os_denied`. Do NOT insert a grant row.
4. On OS grant: insert the grant row.
5. Single-fix the location → return inline result.

Subsequent invocation (grant row exists):
1. Manifest declares + plugin installed (always).
2. Grant row present (settings revoke would have removed
   it) AND OS permission still granted.
3. Single-fix the location → return inline result.

## Continuation lifecycle (codex 137 missing item)

Every Activity-result-requiring capability call carries a
`call_id`. State machine:

```
created
  ↓ (host validates grant + OS perm)
waiting_for_grant      — in-app prompt up
  ↓ (accept)
waiting_for_os_perm    — OS prompt up
  ↓ (accept)
launched               — Activity intent fired / picker open
  ↓ (result delivered) / (cancel via back) / (timeout)
completed / cancelled / timed_out
```

Timeouts:
- `waiting_for_grant` + `waiting_for_os_perm`: 30s. User
  inaction → `cancelled`.
- `launched`: 5 minutes. Activity finished or returns no
  result → `timed_out`.

Cancellation:
- Sandbox-side coroutine cancellation → host marks
  continuation `cancelled` and ignores any later Activity
  result for that call_id.
- Plugin unload → all the token's pending continuations
  marked `cancelled`.
- Sandbox process death → IBinder.DeathRecipient fires →
  same as plugin unload.

Concurrency:
- One global active Activity-result operation at a time
  across all plugins. A second concurrent request returns
  `{"error":"capability_busy"}` immediately. This avoids
  layered Intent stacks and weird Activity-recreation
  behavior.
- `location.*` calls are NOT activity-result-bound and
  may overlap freely.

## Activity-result plumbing

Sandbox processes (`:plugin` JS, per-token `:nativePlugin`
isolated) have no Activity. The host process owns the
foreground Activity, so the bridge call routes to host code:

1. Bridge handler validates grant + OS permission state.
2. Records `pending_capability_call(call_id → continuation)`.
3. Calls into `CapabilityActivityCoordinator` (singleton
   bound to the Application). The coordinator wraps an
   **ActivityResultRegistry** scoped at the Application
   level (NOT a WeakReference — registry survives Activity
   recreation per gemini 137 #1).
4. If no Activity is currently in STARTED state, returns
   `{"error":"no_foreground_activity"}` immediately and
   marks the continuation `cancelled`.
5. Launches the intent via the registry's contract; the
   registered callback resolves the continuation.

The registry is owned by an `ActivityResultRegistryOwner` that
the Application implements; activities re-register their
callbacks on `onCreate` so a config change re-binds without
losing the in-flight call.

Mid-flight revocation check (gemini 137 missing item): after
the Activity result returns but BEFORE staging bytes /
returning to sandbox, re-check the appropriate state for the
capability's category (see "Two grant-check sequences" above).
For Category A first-time calls (gallery/file/camera-first),
re-check manifest declaration + plugin still installed + OS
permission (camera only); the grant row doesn't exist yet so
we explicitly DON'T re-check it. For Category A subsequent
calls + all Category B calls, re-check the grant row + OS
permission. If any check fails, return
`{"error":"capability_denied"}` and delete the result without
exposing it.

## SAF URI expiration handling

For `file` and `gallery` (gemini 137 missing item): the
`content://` URI returned by ActivityResult only has
`FLAG_GRANT_READ_URI_PERMISSION` for the lifetime of the
Activity-result delivery. The host immediately opens the
InputStream and copies bytes into its own staging file
(synchronously, before the Activity-result callback returns).
The URI is dropped; the plugin only ever sees the handle.

## JS bridge shape

Existing entries on `platform.*` already exist as stubs:
- `platform.cameraCapture(opts)` → returns handle metadata.
- `platform.galleryPick(opts)` → returns
  `{items: [{handle, name, mime, sizeBytes, expiresAtMs}, ...]}`.
- `platform.filePick(opts)` → returns handle metadata.
- `platform.locationCurrent(opts)` →
  `{latitude, longitude, accuracyMeters, precision}`.

New JS entries (Phase 12 adds):
- `platform.fileBytes(handle, offset, length)` → returns
  `{bytes: base64, eof: boolean}`.
- `platform.releaseHandle(handle)` → returns `{}`.

The native bridge mirror in `BridgePluginContext` gains
`fileBytes` and `releaseHandle` methods. Existing native
methods `cameraCapture` / `galleryPick` / `filePick` change
return types from `CameraResult`/`GalleryResult`/`FileResult`
(which carried `ByteArray`) to **handle-bearing** variants:

```kotlin
data class CapabilityHandle(
    val handle: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val expiresAtMs: Long,
)

data class CameraResult(val handle: CapabilityHandle)
data class GalleryResult(val items: List<CapabilityHandle>)
data class FileResult(val handle: CapabilityHandle)
```

This **bumps `NATIVE_SDK_ABI` from 1 to 2** since the SDK
contract changes shape. Any Phase 11-era native plugins
need re-publish. The existing `unsupported_sdk_abi` load
failure code already surfaces this; no settings-level
banner is added (triad 138 F per gemini — no plugins in
the wild, wasted UI work).

## Server-side capability validation

The `PluginPublishRequest.capabilities` allowed-literal set
gains `location.coarse` + `location.fine` and drops `location`.

**Both-location rejection (triad 138 G per gemini, codex
divergence accepted as v0.1-simplicity tiebreak):** a
manifest declaring BOTH `location.coarse` and
`location.fine` is rejected at publish with
`location_double_declaration`. The plugin should declare
`location.fine` and inspect the returned `precision` field
to detect OS-downgraded approximate fixes. Re-visit if a
real progressive-permission use case surfaces later.

Photo Picker max items (triad 138 H, both reviewers):
`maxItems == 1` uses `PickVisualMedia`; `maxItems > 1` uses
`PickMultipleVisualMedia`. SDK boundary hard-caps
`maxItems` at 10. Multi-result staging is **atomic** — if
ANY selected item exceeds 16 MB or staging fails, the host
releases all sibling staged files and the whole call
returns the error.

## SDK error taxonomy (codex 137 missing item)

The `error` field in a bridge failure response is one of:

| Code                          | When                                             |
|-------------------------------|--------------------------------------------------|
| `capability_not_declared`     | manifest doesn't list the capability             |
| `capability_denied`           | user declined in-app prompt OR revoked mid-flight |
| `capability_prompt_rate_limited` | session-cooldown active (see below)           |
| `os_denied`                   | runtime OS permission denied                     |
| `no_foreground_activity`      | no STARTED Activity available                    |
| `capability_busy`             | another activity-result call already in flight   |
| `cancelled`                   | plugin cancelled / process died                  |
| `timeout`                     | continuation timed out                            |
| `result_too_large`            | picked file > 16 MB                              |
| `invalid_handle`              | handle unknown or expired                        |
| `io_error`                    | staging copy / read failed                       |

## Grant prompt cooldown (gemini 137 #2)

A denied in-app prompt does NOT immediately silence further
prompts. Instead:
- Same `(plugin_row_id, capability)` re-prompt within the
  current foreground session returns
  `capability_prompt_rate_limited` immediately.
- The cooldown clears on next `onResume` of the host's main
  Activity (gemini's "session-based cooldown").
- Two consecutive denials within a single session permanently
  block prompts for that capability until the user manually
  flips it in settings (Android's "Don't ask again" model).

## Settings-sheet revoke UX (both 137 #9)

Phase 12 ships per-plugin pivot:
`Settings → Plugin X → Capabilities → revoke`.

Each row shows `last_invoked_at_ms` (or "never") and current
grant state. Per-capability pivot (`Settings → Camera → who's
using it`) is deferred.

## Implementation order (Phase 12)

1. ~~Spec digest (v1).~~ Replaced by this v2.
2. Spec v2 (this file) + design triad re-review.
3. Server: rename `location` → `location.coarse`/`location.fine`
   in the `PluginPublishRequest.capabilities` Literal. Manifests
   declaring legacy `location` are rejected with the
   migration note "split into location.coarse / location.fine".
   Migration 0013 if any DB constants reference the name.
4. Room migration: `plugin_capability_grants` table.
5. `CapabilityGrantStore` (DAO + in-memory cache).
6. `CapabilityHandleStore` (DAO-less; file-backed under
   `cacheDir/plugin-capability/<token>/`).
7. `CapabilityActivityCoordinator` (ActivityResultRegistry,
   pending-call map, lifecycle SM, IBinder DeathRecipient
   wiring for sandbox crash).
8. Host UI: `CapabilityGrantDialog` (Compose Material 3).
   Used ONLY for `location.*` (UI-consent capabilities skip
   this).
9. `LocationBridge` — Fused Location Provider single-fix,
   timeout 10s, returns precision actually granted.
10. `FileBridge` — `ACTION_OPEN_DOCUMENT` round-trip, immediate
    content-URI → staging copy, handle returned.
11. `GalleryBridge` — AndroidX `PickVisualMedia` /
    `PickMultipleVisualMedia` unconditionally, immediate copy,
    handle(s) returned.
12. `CameraBridge` — `ACTION_IMAGE_CAPTURE` writing to a
    host-owned FileProvider URI, host reads bytes into
    staging, handle returned; original temp file deleted.
13. `fileBytes` + `releaseHandle` bridge entries on both
    JS and native paths.
14. NATIVE_SDK_ABI bump to 2; SDK-runtime data classes
    swapped to handle-bearing.
15. Audit-log integration (one row per invocation per the
    error taxonomy outcome).
16. Settings sheet: per-plugin grant list + revoke.
17. Tests:
    - lifecycle SM transitions (waiting → launched → completed /
      cancelled / timed_out)
    - mid-flight revocation surfaces `capability_denied`
    - handle TTL expiry → `invalid_handle`
    - >16 MB pick → `result_too_large` without staging
    - sandbox death during launched-state → continuation
      transitions to cancelled
    - rate-limit second prompt in same session → `capability_prompt_rate_limited`
18. Mid-track triad consult.

## Non-goals for Phase 12

- **Background location.** Single foreground fix only.
- **Multi-item camera capture.** Single capture per call.
- **File save** (`platform.fileSave`). Picker is read-only this
  phase; write-out is V3+.
- **PIP / mediaprojection / screen capture.** Out of scope.
- **Audio recording.** Separate dialog+continuous-recording
  protocol; out of scope.
- **Cross-process handle sharing.** Handles are scoped to the
  plugin that received them. Plugin A cannot read Plugin B's
  handle even with knowledge of the string.

## Threat model deltas

- Malicious plugin DoS via dialog spam: mitigated by
  session-cooldown + 2-strike permanent block (see above).
- Camera/gallery/file results are user-controlled bytes:
  bridge passes them via handles; plugin treats as untrusted
  input. Documented in SDK contract.
- Location grants do NOT log coordinates to the audit log
  (privacy). Only invocation + outcome.
- Handle string forgery: handles are 128-bit random UUIDs
  prefixed `cap-`; sandbox lookup requires `(plugin_row_id,
  handle)` match so Plugin A guessing Plugin B's handle still
  fails the per-plugin scoping check.
- MIME spoofing: provider-supplied MIME is recorded but the
  host sniffs the magic bytes on stage and records both
  `claimed_mime` and `detected_mime`. Mismatch is surfaced
  to the plugin but not auto-rejected (some legitimate files
  have ambiguous MIME).
