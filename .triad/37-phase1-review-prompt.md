# Review 37 — Phase 1: hostPreview contract + preview/detail split

Just shipped commit `M11.1` (head). Replaces the WebView-per-card inbox with native Compose rows; plugin `render()` reserved for tap-to-detail view. `hostPreview` block in the encrypted payload carries the row metadata.

Contract was locked in consultation 36; this review is about the implementation.

## What to pressure-test

### Contract enforcement

1. **Byte-cap parity across the three SDK/host validators.**
   - sdk-python: `syncler/preview.py::validate_host_preview`
   - sdk-plugin: `src/preview.ts::validateHostPreview`
   - Android: `feature/inbox/InboxRepository.kt::parseHostPreview`
   Limits should be identical. UTF-8 byte counting (not char counting). Required-title gating identical. SearchText array shape identical. Total-cap behavior identical.
   - Walk through one obviously-wrong shape (e.g. a string instead of an array for searchText) and confirm all three reject it with a sensible error.

2. **The Android validator is the only one that doesn't raise — it returns null with a warn log.** That's deliberate (a malformed payload from a third party should never crash the inbox). But verify the three side-effects of falling back are sound: row falls back to "New message from {sender}", plugin still loads + renders fine in detail view (the malformed hostPreview is just ignored, the rest of payload is intact), no other state set.

3. **Total-cap test in both SDKs** uses an "unknown extra field" — the per-field caps alone sum to ~1.5KB plus JSON overhead, well under 2KB. Is that intentional or should the field caps be tighter so they could actually exceed the total individually? Codex's earlier note: "Larger previews are rejected or ignored." Current behavior is: ≤ 2KB total wire, ~1.5KB possible via documented fields alone, remaining ~500B serves as headroom for unknown extension fields. Is that the right framing?

### Preview/detail split

4. **WebView lifecycle in the detail screen.** Detail is a fresh `AndroidView(WebView)` per item open. Closing via back arrow OR system back triggers `BackHandler` → `closeDetail()` → composition exits → `onRelease` fires → bridge `destroy()`, JS interface removed, WebView destroyed. Confirm: no leak path remains? In particular if the user opens detail → presses back fast → opens detail again immediately, do we leak the first WebView?

5. **`InboxViewModel.selectedId` vs `items` race.** Selection is a separate StateFlow; if a poll-refresh removes the selected message (e.g., it expired or pairing was revoked), `firstOrNull { it.id == id }` returns null and the screen flips back to the list. Is that the correct behavior or should the detail screen "stick" until the user backs out?

6. **No mark-read in this phase.** Cards stay visually identical between unseen and seen. The product was promised "unread dot, heavier title" in the previous review (#35). I deliberately deferred to Phase 2 (M7 CAS sync). Is that the right phase boundary, or does the lack of any visual unread state make the preview/detail flow feel incomplete?

### Fallback / failure paths

7. **`hostPreview` parse failure → fallback row** is logged via Timber at warn level. But the user has no UI affordance to tell that something went wrong. Should the row visually mark "this message has malformed metadata" so a power user notices? Trade-off: surfacing it could feel alarmist for senders who are simply on an old SDK.

8. **Plugin bundle not yet fetched** (`bundleJs == null` in the InboxItem): the detail screen currently shows "Plugin not loaded — encrypted payload below" with the raw JSON. The list row still shows the hostPreview row correctly. Is that the right behavior, or should the row hide until the bundle is ready?

### Doc consistency

9. **`integration-guide.md` §2** now documents the hostPreview block. Walk the doc → contract → implementation chain and flag any place where the doc promises something the implementation doesn't deliver, or vice versa.

10. **§3 example** has `onMessage` calling `platform.showNotification` — but the V1 inbox-mode bridge rejects `showNotification` (CardBridge dispatches only `platform.network.fetch`). The example will fail at runtime if onMessage is invoked. Should the example be revised, or onMessage's stub-rejection documented somewhere?

## Output

Standard format. Be specific — line:column citations preferred. If the answer to a question is "this is fine", say so explicitly and move on; I want to know what to fix vs what to ignore.

## What's NOT in scope

- Read/unread visual treatment (Phase 2)
- Bottom nav, date buckets, archive (Phase 3)
- Bundle-by-hash retention (Phase 4)
- Host-side search (Phase 5)
- Plugin dashboards, plugin-scoped search (V1.5)

Don't relitigate the contract design (it was locked in #36).
