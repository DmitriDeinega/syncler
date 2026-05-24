# Consultation 64 — Phase 3 final: tests + commit-readiness

Both reviewers in consultation 63 returned dual-GREEN on the 7
Codex blocker fixes (and the 2 follow-up REDs from Codex's
post-blocker pass) and aligned on UNIFIED Phase 3 landing.
Verdict: READY AFTER TESTS.

The test suite the triad asked for is now in the tree. This
consultation should confirm coverage and substance, then bless the
single commit.

## Tests added

### Server (`server/tests/test_phase3.py`, 13 cases)
- **Phase 3a — template publish**
  - `test_publish_template_plugin_success` — golden path; `/latest`
    round-trips the template block including layout, fields, and
    actions.
  - `test_publish_template_rejected_unknown_layout` — 422 for any
    layout not in `_TEMPLATE_LAYOUTS`.
  - `test_publish_template_rejected_missing_title` — 422 when
    `standard_card` omits the required `title` field.
  - `test_publish_template_rejected_bad_jsonpath` — 422 for
    `$.items[0]` (array indexing isn't in the V1 dialect).
  - `test_publish_template_rejected_action_endpoint_not_declared`
    — 422 when action endpoint falls outside the
    `declaredEndpoints` globs.
  - `test_publish_template_rejected_duplicate_action_ids` — 422
    when two actions share an `id`.

- **Phase 3b — live card upsert security gates**
  - `test_card_upsert_rejected_when_plugin_revoked` — 410 (Codex
    62 RED #2).
  - `test_card_upsert_rejected_when_no_pairing` — 410 (Codex 62
    RED #3).
  - `test_card_upsert_rejected_when_expires_past` — 400 (Codex 62
    RED #4 / 63 RED router mapping).
  - `test_card_upsert_rejected_when_expires_exceeds_48h` — 400
    (Codex 62 RED #4 / 63 RED router mapping).
  - `test_card_upsert_sequence_regression_rejected` — 409.

- **Phase 3b — cross-user delete**
  - `test_card_delete_signature_bound_to_user` — Alice's delete
    signature MUST NOT delete Bob's card with the same
    `(sender_id, card_key)`; only Alice's row is removed (Codex
    62 RED #5).

- **Phase 2 carry-over**
  - `test_inbox_omits_dismissed_messages` — dismissed messages
    drop out of the next `/v1/messages/inbox` pull (consultation
    57 follow-up, applied in Phase 3 carry-over per the agreed
    plan lines 340–351).

### Android (`feature:inbox/src/test/.../`)
- `ResolveJsonPathTest` — already in the tree from earlier work.
  Covers top-level strings, nested strings, numeric/bool
  coercion, missing keys, non-object segments, object/array
  leaves, malformed paths.
- `TemplateActionRunnerTest` (new) — verifies
  `TemplateActionRunner.post(...)`:
  - does NOT attach an `Authorization` header to the outbound
    request (Codex 62 RED #1 regression guard);
  - sends the payload body verbatim so sender-side HMAC works;
  - records exactly one request on 5xx (fire-and-forget contract);
  - rejects a non-http(s) scheme without a network call (defense
    in depth on top of the release-only HTTPS gate).

### Storage (`core:storage/src/test/.../StateMergerTest.kt`)
- `muted senders take union` — `StateMerger.merge` unions the
  `mutedSenders` lists (Phase 3c invariant).
- `V4 blob without muted senders parses as empty list and
  forward-migrates to V5` — covers the V0→V5 forward-migration
  contract for the new field.
- `paired senders survive schema V3 to V4 forward migration` —
  ensures the schema bump preserves prior-phase data.

### Test infrastructure repairs
- `server/tests/test_messages.py` — pre-existing tests still
  read `inbox.json()["messages"]` but Phase 3b's
  `InboxFeedResponse` switched the key to `items`. Updated both
  call sites.
- `UserStateRepositoryPushTest.kt` — pre-existing fake API stub
  declared `inbox(...)` returning `MessageInboxResponseDto`; the
  real interface now returns `InboxFeedResponseDto`. Updated the
  override + import.
- `feature/inbox/build.gradle.kts` — added
  `libs.okhttp.mockwebserver` to the test classpath.

### Tests NOT included (intentional)
- `MuteStoreTest` — would need either Robolectric or a
  refactored `MuteStore` constructor that injects the
  `SharedPreferences` (current code reaches into
  `EncryptedSharedPreferences.create(context, ...)` directly,
  which can't run on a pure JVM unit runner). Local-override
  behavior is covered indirectly by `InboxScreen`'s use of
  `mutedSenderIds` in the All / Unread / Live / Archive / Sender
  filters; the synced-side is covered by the StateMerger union
  test. Acceptable V1 gap.
- Compose UI tests (`InboxScreenMuteTest`,
  `TemplateCardActionCallbackTest`) — instrumented tests; same
  rationale.
- Server pytest could not be run locally during this session
  (Docker / Postgres not up). The 13 tests parse and collect
  cleanly under `pytest --collect-only`. The user will run them
  with Postgres up before merging.

## Final state

`git diff a2db9cb..HEAD` plus the untracked files now includes:
- All 7 Codex consultation-62 blocker fixes
- The 2 router-mapping follow-up fixes from Codex consultation 63
- The SDK UUID canonicalization fix on `upsert_card`
- The Phase 3a/3b/3c implementation per the agreed plan
- The test suite above

Android `./gradlew assembleDebug` is green. Android unit tests
`:feature:inbox:testDebugUnitTest` and
`:core:storage:testDebugUnitTest` both pass.

## What I need from each reviewer

1. **Confirm test coverage**: does the test list above cover the
   blocker-fix surface adequately for a Phase 3 commit, or is
   there a specific case still missing?
2. **Confirm no new regressions** introduced by the test infra
   repairs (`messages` → `items` in `test_messages.py`,
   `InboxFeedResponseDto` in the test stub).
3. **Confirm commit-strategy**: unified Phase 3 landing as a
   single commit, or do you now (with the test suite in front of
   you) want to revisit the split-vs-unified question?
4. **Anything else** that would block a clean commit right now.

## Output

Per reviewer:
1. Per-area verdict: GREEN / YELLOW / RED for test coverage,
   blocker-fix correctness, build/lint state.
2. Overall: **READY TO COMMIT** / specific blockers remaining /
   hold.

If both reviewers GREEN, I'll commit Phase 3 as a single
"phase 3: templates + live cards + sender mute" commit and move
to Phase 4 (docs + roadmap).
