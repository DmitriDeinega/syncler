# Consultation 35 — Inbox/app UX direction post-M11

End-to-end dogfood works: paired sender publishes a plugin bundle, sender pushes a card, phone polls, decrypts, fetches+verifies the bundle by SHA-256, renders the plugin's HTML in a per-card WebView, action buttons hit the sender's callback through a hardened native bridge. The Android UX is currently a placeholder built only to prove the pipeline:

- Single screen: vertical `LazyColumn` of cards
- Each card: fixed-height `AndroidView(WebView)` at **280 dp**, sender name + sent-at timestamp in the header
- Top row of buttons: Pair sender / Devices / Refresh / Log out
- No concept of read/unread, no grouping, no search, no detail view, no per-plugin organization
- Cards above the fold get clipped; rich plugins (20 lines of detail + buttons + summary) are unusable

The user wants the next iteration to be the *actual* UX. This is a **design consultation**, not a code review. Think broadly. **Do not anchor on the specific suggestions one of the Claudes already wrote** (it suggested auto-expand vs detail view vs max-height+scroll — those are three of many possible answers); pretend you haven't seen them and generate your own.

## The design space — make sure your answer covers these

Some of these may be wrong questions; if you think so, say so and propose a better framing.

1. **Card presentation.** Fixed-height vs auto-grow vs max-height+scroll vs preview+detail. Who owns height — the host or the plugin (via manifest hint, via measurement-on-render, via a separate `previewHeight` field, via the plugin telling the host "I'm done rendering and I'm N pixels tall")?

2. **Read/unread state.** Where does it live (server with delivery_status — partially wired — or client-local)? What's the visual treatment? When does a card transition from unread to read? Should it sync across the user's devices (the multi-device CAS sync from M7 exists but isn't wired into UI)?

3. **Organization / hierarchy.** Flat list, grouped by plugin (collapsible sections), bottom-nav tabs per plugin, date buckets (Today / Yesterday / Earlier), pinned/starred, plugin-owned mini-tabs. Trade-offs?

4. **Search.** The user's intuition was "maybe search is implemented BY the plugin — the host shows a search UI only if the plugin opts in, the host forwards the query to the plugin, the plugin queries against its backend, plugin renders results." Pressure-test this. Alternatives: host-side metadata search (sender, plugin id, sent-at) vs plugin-side full search vs hybrid. What's the right boundary?

5. **Plugin role expansion.** V1 plugins own the card render only. Future possibilities: plugins own the detail view, plugins own a dedicated section/tab, plugins can register additional surfaces (settings, history view, dashboards). What's the smallest expansion that makes the inbox usable for rich plugins, and where do we draw the line so plugins don't take over the chrome?

6. **Lifecycle.** Server retention cap is 30 days. Client can persist longer (the encrypted body + plugin bundle are reproducible from the inbox + plugin manifest). Dismissal protocol exists (DISMISS_ALL etc.) but isn't wired to UI. Archive vs delete? Auto-expire?

7. **App chrome.** Bottom-nav, top-app-bar, FAB? Should the chrome itself be plugin-aware (e.g., a "compose" affordance only makes sense for plugins that opt in)?

8. **Edge cases that need first-class treatment.** Empty inbox, fresh-pair "what do I do now", plugin fetch failed, plugin render threw, plugin bundle revoked since the message was stored, payload doesn't validate against current plugin version.

## What to deliver

Write a 1500–2500-word response that includes:

- **Your independent take** on the right direction (don't just enumerate options — pick a coherent stance and defend it)
- **The single most important call** you'd make first if the user could only ship one change next
- **The two or three things you'd defer** even though they sound appealing, and why
- **Where the lottery sender's needs differ from a future trading-bot / notification / chat-style sender** (the plugin space is intentionally open — your UX has to serve all of them, not just lottery)
- **The plugin contract changes** the SDK would need to support your direction (new manifest fields? new dispatch hooks? new platform APIs?) — be specific
- **Anything in our current architecture that quietly precludes your direction** that we'd need to change

Tone: opinionated. The user wants discussion, not a shopping list. If you think the host should NOT own grouping and search at all, say that and explain. If you think the plugin should be allowed to take over half the chrome, defend it.

## Context you have

Existing reviews in `.triad/` 27–34 give the architectural priors. Notable constraints:

- Server is content-blind. Anything the host UI does with card *content* has to happen client-side, post-decrypt.
- Multi-device sync exists (M7 — encrypted user-state blob with CAS). Read/unread state could ride on it.
- Plugins run in isolated WebViews with a narrow native bridge (`platform.network.fetch` is the only thing wired in inbox mode right now).
- The phone polls every 15s. No FCM-equipped device required (we decoupled storage from push this session).
- WebView per card is the rendering model; if your direction needs a different model, name it.

Skip pleasantries. Go.
