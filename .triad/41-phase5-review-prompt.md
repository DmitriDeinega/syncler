# Review 41 — Phase 5: host-side metadata search

Shipped commit `M11.5` (head). The last UX piece from the consultation
35 plan. Inbox top-app-bar has a Search icon → search mode → text field
that filters items by substring match against host-side metadata
(sender name, hostPreview.title/subtitle/summary, and the
hostPreview.searchText tokens senders pre-indexed in M11.1).

Plugin-scoped content search (a per-plugin search bar that calls the
plugin's backend) is deliberately deferred to V1.5 — see the original
search section in consultation 35.

## What to pressure-test

### Match semantics

1. **Case-insensitive substring.** `matchesQuery` lowercases both
   query and corpus before `contains`. Edge: locale-sensitive
   lowercase (Turkish dotless-i, etc.) might do unexpected things on
   non-Latin scripts. Acceptable V1, or worth `lowercase(Locale.ROOT)`?

2. **Token participation.** `hostPreview.searchText` tokens are
   individually `lowercase().contains(q)`. So `searchText = ["AAPL"]`
   matches `q = "appl"` (which is probably intended, "apple" matches
   AAPL by accident). Is that right, or should searchText match be
   token-equality (substring inside any token, but the *query* must be
   present as a whole word)?

3. **Empty / whitespace query.** `q.trim().lowercase()` — blank
   returns true (everyone matches), so the UI shows the unfiltered
   set. Correct.

### State semantics

4. **`searchActive` is local UI state.** Lives in `remember
   { mutableStateOf(false) }` inside `InboxScreen`. The actual
   `searchQuery` lives in the ViewModel. So if the user switches to
   Senders tab and back, `searchActive` resets to false but
   `searchQuery` persists. That's an inconsistency — when the user
   returns to Inbox, search results filter the list but there's no
   visible search bar. Recommend either hoisting `searchActive` into
   the ViewModel or auto-clearing the query when `searchActive` goes
   false.

5. **Search field auto-focus / IME.** Currently `TextField` will get
   focus on first composition. Does it auto-summon the IME, or does
   the user have to tap the field? Material 3 default is "no IME until
   focused" — opening search but not autocallong the keyboard is
   a minor UX papercut. Worth requesting focus in a LaunchedEffect?

### Interaction with other filters

6. **Search composes with archive filter** (active inbox excludes
   archived). Per design — archived cards are out of the active feed
   regardless of search. Should searching also surface archived items
   ("search everywhere")? Or is Archive being a separate space the
   right metaphor?

7. **Search composes with date buckets.** Filtered items still bucket
   into Today / Yesterday / Earlier sections. If all results are in
   "Earlier", the user sees one section header — fine UX or noisy?

### Tests

8. The 6 new tests in `InboxSearchTest`:
   - blank → match all
   - title substring (incl. case-insensitive)
   - subtitle+summary participation
   - searchText tokens
   - sender-name participation
   - hostPreview-null fallback (still searchable by sender)

   Coverage I might've missed:
   - Special characters in query (quotes, slashes, regex chars) —
     `contains` is plain string, no regex parsing, so should be safe.
   - Unicode + emoji query.

## What's NOT in scope

- Plugin-scoped content search (V1.5)
- Search history / suggestions
- Highlight matching substrings in row text
- Search across archived items (deliberately separate space)
- Persisted query across process restart

## Output

This was meant to be the last UX phase before V1 polish + final
summary. If you greenlight, the next step is writing the final summary
+ V1.5 sign-off list for the user. If you find something that needs
fixing, name it specifically.
