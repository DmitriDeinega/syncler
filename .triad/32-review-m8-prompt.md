# Review of M8 (Plugin update lifecycle)

Commits `1fbd9a9` (server) + `5cbf755` (android).

## What landed

**Server**
- `services/plugins.py` — publish (semver-lite + regression check + IntegrityError → 409), get_latest_for_plugin, revoke
- `routers/plugins.py` — POST /publish (sender Ed25519-signed envelope), GET /{id}/latest, POST /{id}/revoke
- `schemas.py` — PluginPublishRequest/Response, PluginLatestResponse with base64 validators
- `tests/test_plugins.py` — publish + fetch round-trip, regression 409, bad sig 401

**Android**
- `core/network/SynclerApi`: `getPluginLatest` + `PluginLatestDto`
- `core/storage/PluginVersionComparator`: semver-lite matching server. Unit tests cover ordering, prerelease, malformed input.
- `core/storage/PendingUpdatesStore`: EncryptedSharedPreferences-backed pending-update records with defer support.

## Deferred to M11
- `PluginUpdateChecker` (WorkManager-scheduled) — schedules daily/foreground polls
- `PluginUpdatesScreen` (Compose) — list + Update/Defer/Remind UI
- Capability-change re-grant flow

## Verify
1. Server semver-lite: does `_parse_version` agree exactly with Android's `PluginVersionComparator.compare`?
2. Server's IntegrityError → VersionRegressionError mapping correct? (UNIQUE(sender_id, version) from M1.3 schema.)
3. /publish envelope canonical bytes match what an SDK would produce? Field order, JSON shape.
4. Android `PluginVersionComparator.parse` regex: identical regex shape as server's?
5. PendingUpdatesStore.defer: monotonic timestamp; does the consuming UI check `remindAfterMs > now()` before re-prompting?

## Output format same as prior rounds.
