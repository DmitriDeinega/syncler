# Review of M7 (Multi-device sync — encrypted state + CAS)

Commits `bec5185` (server) + `46327dd` (android).

## What landed

**Server**
- `services/state.py` — get_state + upsert_state_cas (atomic UPDATE WHERE version = expected)
- `routers/state.py` — GET /v1/state, PUT /v1/state {expected_state_version, new_encrypted_blob} → 409 with current state on conflict
- `schemas.py` — StateGetResponse, StatePutRequest, StatePutResponse, StateConflictBody
- `tests/test_state.py` — empty read, 2 writes versioning correctly, stale 409, auth required

**Android**
- `core/storage/EncryptedUserState.kt` — structured local model with schema_version + installed_plugins + dismissed_messages + plugin_settings + user_scoped_storage; JSON round-trip
- `core/storage/StateMerger.kt` — three-way merge: installed_plugins by id-newest-wins, dismissed_messages union, plugin_settings by id-newest-wins, user_scoped_storage remote-wins
- `core/network/SynclerApi` — getUserState + putUserState DTOs
- `core/storage/test/StateMergerTest.kt` — 4 tests covering merge classes + JSON round-trip

## NOT yet built (intentional deferral)
- UserStateSyncer (the pull-merge-CAS-push loop) — needs master key context which is currently scoped to the Session. Wired in M8 alongside the plugin update UX which triggers state changes.
- DismissEventHandler in :core:push for Gap 1 fan-out
- Per-key user_scoped_storage timestamps (V1.5)

## Verify

1. Server CAS race: the atomic UPDATE WHERE version=expected returns no rows on conflict; we then re-read and raise. Is there any window where two concurrent PUTs could both think they succeeded?
2. StateMerger.userScopedStorage remote-wins: appropriate V1 simplification, or does this lose data dangerously?
3. EncryptedUserState.fromJson handles missing fields (.optJSONArray/.optJSONObject) — schema-version-0 (older blob) parses safely?
4. State endpoint returns base64-encoded blob; tests pass blob as `_b64(bytes)`. Confirm no encoding mismatch.
5. PUT empty blob (length 0) — currently `minimum=1` in schema. Is 0-length a valid state? Probably no, since users always have at least the schema_version field, but worth confirming.
6. Server 409 returns the conflict body inline in `detail`. FastAPI's HTTPException stringifies dict detail. Is the client-side parsing path well-defined?

## Output

Same format. Look for issues affecting:
- Sync correctness under concurrency
- Merge data loss
- JSON schema migration safety
- 409 body parseability
