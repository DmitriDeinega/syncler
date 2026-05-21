# Review 44 — Phase 7: multi-select + group-by-sender

Shipped commit `M11.7` (head). Closes the last two items from the user
feedback that drove M11.6/M11.7: multi-select via long-press with
bulk Archive / Delete, and a top-app-bar toggle between chronological
(date buckets) and grouped-by-sender (collapsible sections).

## What to pressure-test

### Multi-select

1. **Selection state and back nav.** Long-press → enters selection
   mode. Tap toggles in/out. Cancel button OR system back clears the
   selection (gated `BackHandler(enabled = selectionMode)`). When the
   user enters selection mode then taps Senders tab on the bottom
   nav and returns, selection persists (ViewModel-scoped). Is that
   the right behavior or should switching tabs clear?

2. **Selection vs the other top-bar modes.** Top-bar precedence in
   `InboxScreen` is `when { selectionMode -> ...; searchActive ->
   ...; else -> normal }`. If the user enters search, types a query,
   then long-presses a filtered row, selection takes over and the
   search bar disappears. Returning from selection via the close
   button drops back to the normal top-bar, not back into search.
   Is that right, or should selection over search return to search?

3. **Bulk push performance.** `markManyArchived` / `markManyDeleted`
   each do one `mutateLocal` + one `push()`. With 50 selected items
   the push payload still grows by 50 entries (each ~80 bytes
   serialized). Within the 2KB cap-free area for that list. Fine.

4. **Confirmation dialog only on delete, not on archive.** Archive
   is reversible (via the detail-view unarchive icon); delete is
   not. So no confirm for archive, yes for delete. Matches Gmail/
   Apple Mail behavior. Confirm OK?

5. **Selected-row visual.** `secondaryContainer` surface tint +
   selection-check icon in the leading slot. The unread dot is
   suppressed while selected (the user knows about the read state
   via the title weight; the dot would compete with the check icon
   for the same 8dp slot). Acceptable, or should the dot show
   alongside the check?

### Group-by-sender

6. **Group ordering.** Sections sort by max(sentAt) within the
   group — most-recently-active sender at the top. Items inside
   each section are newest-first. Edge case: if a sender's
   most-recent item is older than another sender's first item,
   the order can feel inconsistent ("why is Lottery at the top
   when its only card was 3 days ago and TradingBot just sent one
   2 minutes ago?"). The implementation IS doing the right thing
   (TradingBot would be on top in that scenario); the call-out is
   that the user may not initially understand the section sort.
   Worth a hint in the section header or an alternate sort
   (alphabetical by sender name)?

7. **Collapse state.** `_collapsedSenders: Set<String>` lives in
   the VM. Cleared automatically when the user logs out (via
   the `UserStateRepository.clearInternal` observer? actually no —
   the collapsed set is purely UI state in InboxViewModel and
   doesn't follow the user). New user logs in → empty set. Fine
   for V1.

8. **Empty/loading/search interactions with group mode.** Search
   is applied BEFORE grouping (filter then group). If the user
   has BySender selected and searches, results are grouped by
   sender within the search results — sections might have only
   1 item each. Acceptable, or should search force chronological?

### Tests

9. **No new unit tests in this commit.** The bulk repository
   helpers are thin wrappers around the already-tested single
   helpers, and the grouping logic is a transient `groupBy +
   sortBy` in a Composable that's hard to test without
   Compose-test infrastructure. Is that scope cut acceptable, or
   would you want a test for `GroupedBySenderList`'s sort order
   specifically?

### Accidentally-shipped files

10. The M11.6 commit accidentally captured `android/.gradle-user-
    home/wrapper/dists/...zip.{lck,part}` from Codex's sandbox
    Gradle attempt. M11.6 amend removed them and added
    `.gradle-user-home/` to `.gitignore`. Spot check the
    repository for any other inadvertent additions.

## What's NOT in scope

- Persisted group-mode / collapsed-senders across process restart
- Per-sender mute (different from archive — "stop showing me cards
  from this sender entirely")
- Search-while-grouped UI polish (search-filter-results-then-group
  is the current behavior; an opt-out to flatten when searching
  might be nice)
- Swipe-to-archive gesture for the chronological view

## Output

This is the last user-feedback-driven UX phase. If you greenlight,
the next thing is just polish + final triad summary across
M11.6 + M11.7 before this lands as a single rolled-up commit
in the history.
