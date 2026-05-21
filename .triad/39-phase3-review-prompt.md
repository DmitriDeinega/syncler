# Review 39 — Phase 3: bottom nav + date buckets + archive

Shipped commit `M11.3` (head). Three-tab Material 3 bottom navigation
(Inbox / Senders / Settings), date-bucketed inbox (Today / Yesterday /
Earlier), archive sub-screen + per-item archive action.

## What to pressure-test

### Navigation

1. **Three-tab structure.** Inbox / Senders / Settings. Archive is a
   sub-screen of Inbox (overlay), not its own tab. Phase-2 reviewers
   were split: Codex preferred Inbox / Archive / Devices as the three
   tabs; Gemini preferred Inbox / Plugins / Settings. I went with
   Gemini's because (a) Archive will be empty 99% of the time for a
   normal user — a permanent tab slot is wasteful, (b) Settings
   subsumes the "manage devices, log out" affordances that don't need
   their own tab. Is hiding Archive behind a top-app-bar icon the
   right call, or does it lose discoverability?

2. **Tab state on rotation / process restart.** `MainViewModel._screen`
   is a `MutableStateFlow` in the ViewModel — survives rotation. On
   process-restart-from-cold the user lands on Inbox (the default). Is
   that the right behavior, or should we persist the last tab?

3. **Sub-screen back navigation.** Detail and Archive sub-screens wire
   `BackHandler { onBack() }` to pop back to inbox. Bottom nav is still
   visible during sub-screens (it's at the Scaffold level), so the user
   can also jump to Senders/Settings without going back first. Is that
   the right UX — or should sub-screens hide the bottom bar?

### Inbox

4. **Date bucket boundaries.** Uses `Instant.parse(sentAt).atZone(
   ZoneId.systemDefault()).toLocalDate()` to bucket. Edge case: a
   message sent at 23:55 UTC by a server in UTC, viewed by a user in
   GMT-5, would project to a local time of 18:55 the SAME local date,
   landing in "Today" or "Yesterday" depending on when "now" is. The
   `today` reference is `LocalDate.now()` (device local). Is the
   timezone math right? I want a critical read here.

5. **`remember(items, today)`** — the bucketed map recomputes when
   either changes. But `LocalDate.now()` is captured at composition
   time, so a user who leaves the app open across midnight would see
   the same buckets until a recomposition forces it (e.g., a refresh).
   For V1 acceptable — refresh fires every 15s — but flag if there's
   a cleaner pattern.

6. **Archive list ordering.** [ArchiveScreen](android/feature/inbox/src/main/kotlin/app/syncler/feature/inbox/InboxScreen.kt)
   renders archived items in the same order as the inbox (newest
   first via the repository's `sortedByDescending { sentAt }`). Is
   there value in ordering by archived-at instead?

### Archive behavior

7. **Archive flow.** Detail screen's archive action calls
   `viewModel.archive(id)` then `onBack()`. The item disappears from
   the inbox list (active items filter out archived) but stays in the
   `items` flow (so opening it from Archive still works). On the next
   `refresh()`, the inbox poll could re-fetch the same archived
   message if the server hasn't expired it yet — and our local cache
   merges on id, so it doesn't duplicate. But the user state's
   `archivedMessages` is the source of truth for "is this archived" —
   if the user clears app data, archive state is lost UNTIL the next
   `pull()` from the M7 CAS blob. Correct?

8. **Archived → unarchive.** No way to unarchive in V1. Is that an
   acceptable omission, or should we surface it in the Archive screen?

9. **Server-side dismiss vs local-only archive.** The previous-phase
   reviews discussed `DISMISS_ALL` and the server's
   `/v1/messages/{id}/dismiss` endpoint. We are NOT calling that from
   archive — archive is purely a client-side filter via the
   M7-synced state blob. Is that the right separation, or should
   archive also push a server-side dismiss?

### Settings

10. **DevicesViewModel.refresh on every Settings open.** `LaunchedEffect(Unit)`
    means refresh fires once on first composition. Switching tabs (Inbox →
    Settings → Inbox → Settings) won't re-refresh — the second composition
    keeps the same effect key. Is that the right behavior, or should
    settings auto-refresh on tab focus?

11. **Logout from Settings.** The logout button calls
    `mainViewModel.logout()` which `authRepository.logout()` →
    `session.logout()` → state flow emits locked → AuthScreen.
    UserStateRepository observes the lock transition via Phase 2's
    observer and clears itself. End-to-end correct?

### Scope I deliberately deferred to V1.5

- Swipe-to-archive gesture (M3 SwipeToDismissBox)
- Mark-unread / unarchive UI
- Auto-expand the bottom nav when no item is selected (collapse on detail)
- Per-tab back-stack with system-back ordering

## Output

Standard format. Specific line:column citations preferred. If the
phase-3 UX is OK as-is, say so explicitly; I'll move to Phase 4
(bundle-by-hash retention + revocation classification) on green light.
