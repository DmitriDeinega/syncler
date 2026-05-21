# M11 — final summary

Five UX phases, each built and triad-reviewed before the next began.
Direction set by consultations 35 (open-ended UX) + 36 (hostPreview
contract); execution + reviews 37–41.

## What you'll see on the phone

- **Bottom nav: Inbox / Senders / Settings.** No more piles of buttons
  in the inbox header.
- **Inbox** is a list of native Compose rows. No more 280dp clipped
  WebViews in the feed. Each row shows sender, title, subtitle,
  summary — drawn from the structured `hostPreview` block senders now
  embed in the payload. Bold-title + dot for unread. Tap → detail.
- **Detail view** is a full-height WebView that runs the plugin's
  `render()`, action buttons wired through the network bridge. Back
  arrow returns. Top-bar actions for archive / unarchive.
- **Date buckets**: rows group into Today / Yesterday / Earlier.
- **Search**: magnifying glass in the inbox top-bar. Substring match
  over sender, title, subtitle, summary, and the `searchText` tokens
  senders pre-indexed. Case-insensitive, Locale.ROOT, IME pops
  immediately, system back closes.
- **Archive** sub-screen accessed via the archive icon. Cross-device
  archive state lives in the M7 CAS blob; archive on phone → tablet
  reflects on next pull.
- **Read state** syncs across all your devices the same way. Marked
  on detail-open only (scanning the list doesn't punish unread).
- **Per-user state isolation**: log out → all read marks / archives /
  paired-sender keys / bundle cache wiped before the next user signs in.

## What broke and got fixed during reviews

- **Phase 1** (hostPreview + preview/detail split): Android validator
  was too lenient (silently accepting wrong types). Total-size cap
  missing on Android. Python/TS byte counts diverged on non-ASCII.
  Sample plugin code called `platform.showNotification` which the V1
  bridge rejects.
- **Phase 2** (read state + M7 sync): @Singleton repository leaked
  user A's state into user B's session AND would have pushed it to
  B's server account. Concurrent `markRead` was non-atomic. Failed
  pushes never retried. Timestamp string-comparison was wrong order
  for sub-second timestamps. Future schema_version would silently
  corrupt unknown fields.
- **Phase 3** (bottom nav + buckets + archive): sub-screens drew
  under the bottom nav. Inbox sort used the same wrong string
  comparison. Archive kdoc claimed "keep past server expiry" but the
  implementation has no local body store.
- **Phase 4** (bundle-by-hash + revocation): bundle resolution still
  drifted on plugin update because we kept calling `/latest`.
  Revocation reason was recorded but undiscoverable by devices.
  Re-revoke could downgrade compromised → superseded. Test asserted
  HTTP 204 without verifying persistence.
- **Phase 5** (search): `searchActive` was local Compose state while
  `searchQuery` was in the ViewModel — switching tabs left an
  invisible filter. No IME auto-focus. Locale-sensitive lowercase.

Every one of these is fixed in the corresponding M11.x commit, with
new tests where the contract was load-bearing.

## Tests

| Layer | Tests |
|---|---|
| sdk-python | 11 (HostPreview validation) |
| sdk-plugin (TS / vitest) | 26 (12 HostPreview + manifest + base-plugin + network + storage) |
| server (pytest) | 10 plugin + 8 messages + 2 auth + 1 retention + others — 51 total, 48 pass; 3 pre-existing retention fixture failures unrelated to M11 |
| Android (JVM unit) | 16 HostPreviewParser + 8 StateMerger + 6 InboxSearch + 11 crypto + 4 PluginVersionComparator + 2 AuthRepository + 1 Session = 48 |

## Commit ladder

| Commit | Phase |
|---|---|
| `M11.1` base | hostPreview contract + preview/detail split |
| `M11.1` fix-ups | Review 37 — Android validator parity, ensure_ascii=False, docs |
| `M11.2` base | read state + M7 CAS sync |
| `M11.2` fix-ups | Review 38 — session-clearable observer, atomic mutations, retry flush, Instant timestamp compare |
| `M11.3` base | bottom nav + date buckets + archive |
| `M11.3` fix-ups | Review 39 — sub-screen modifier, Instant sort, archive kdoc honesty, unarchive |
| `M11.4` base | bundle-by-hash + revocation reason recording |
| `M11.4` fix-ups | Review 40 — `/by-id` endpoint, monotonic reason promotion, compromised refuse-to-execute UX |
| `M11.5` base | host-side search |
| `M11.5` fix-ups | Review 41 — hoist searchActive to VM, FocusRequester, Locale.ROOT, BackHandler |

## V1.5 sign-off list

Things deliberately deferred. Each is called out in code or doc
comments at the relevant site.

**Plugins**
- Plugin-scoped content search via `Capability.SEARCH` and a new
  `'search'` dispatch hook.
- Plugin-owned "Dashboard" surface (`Capability.DASHBOARD`) for
  domain-specific UIs beyond per-card render.
- Full platform.* bridge in inbox-rendered cards: storage, camera,
  gallery, file, location, notifications all still reject. Lottery's
  "Played" button (network.fetch) is the only capability currently
  reachable from a card.
- Host invocation of `onMessage` hook (currently never called — OS
  notification path lives in V1.5).

**Storage / retention**
- Disk persistence for the bundle-by-hash cache. Currently in-memory;
  every app restart triggers re-fetch.
- Per-message bundle hash persisted across restart so historical
  resolution survives without re-fetch.
- Real "archive past server expiry" — store the encrypted message
  body locally for archived items so they survive the server's 30-day
  retention cap. (Current archive is a hide-from-active filter only.)

**Sync / multi-device**
- Per-key timestamps + tombstones on `userScopedStorage` so plugin
  storage can sync cross-device safely.
- Mark-unread feature (with the tombstone work above).
- Persisted bottom-nav tab across process restart via
  `SavedStateHandle`.

**Search / discovery**
- Match highlighting in inbox rows.
- Option to search inside archived items.
- Search history / suggestions.

**Security / hardening**
- Bundle signature re-verification on download (currently only the
  publish-time Ed25519 over manifest+hash is trusted; SHA-256 hash
  check confirms CDN integrity but not authenticity-per-fetch).
- Biometric / device-PIN unlock so the user doesn't re-type their
  password on every cold start (the question they asked earlier
  about "why do I always log in").
- Auto-archive / GC for tombstone bloat.

**UX polish**
- Swipe-to-archive gesture (Material3 SwipeToDismissBox).
- Stacking same-plugin cards within a short time window
  (Gemini's idea from consultation 35).
- Auto-expand the bottom nav on detail screens (Codex's question
  from review 39).

## Honest call-outs

- **Lottery-Claude's bundle.** The integration guide is up to date
  but lottery-Claude's existing live bundle may not include a
  `hostPreview` block — those messages will show "New message from
  Pais Lotto" in the row until they republish with the structured
  metadata.
- **Phone state.** The latest APK has all five phases. ADB
  reconnect → push when you return. No new server-side changes
  require a restart beyond what was done earlier.
- **`/health`** still reports `Syncler Server DEVELOPMENT` and
  `0.0.0.0:8001`.

Welcome back.
