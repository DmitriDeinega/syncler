# Syncler examples

Two reference examples, one per layer:

- [`trading-bot/`](trading-bot/) — **sender side**. A Python backend using `sdk-python` to register, pair, and push messages to a user's phone every 30 minutes. Doesn't ship a plugin; pair it with the minimal plugin below for a full round-trip.
- [`../sdk-plugin/examples/minimal/`](../sdk-plugin/examples/minimal/) — **plugin side**. The smallest WebView script-renderer plugin: one `BasePlugin` subclass, one `render()` returning HTML, the `registerPlugin(...)` call. Source + build script + unsigned manifest. Run the sign-bundle tool to populate `bundleHash` and `signature` before publishing.

A full round-trip example (`trading-bot` sender wired to a `trading-bot` plugin, both sharing a `state.json`) is tracked on the V1.5 DX roadmap.

For the protocol and integration guide: [`../docs/integration-guide.md`](../docs/integration-guide.md).
