package app.syncler.core.pluginaidl;

// Phase 10 — subprocess (:plugin) -> host. Implemented by the host's
// PluginRegistry; passed to the sandbox via IPluginSandbox.loadPlugin.
// EVERY method carries sandboxToken so the host can route per-plugin
// without trusting bare IBinder identity in the shared :plugin
// process. See docs/plugin-host-multi-process.md.
interface IPluginHostCallback {

    // Plugin's JavascriptInterface fired `__syncler_internal.call(...)`.
    // The host's PluginBridge dispatches `method`/`argsJson` to the
    // declared capability; result lands back via
    // IPluginSandbox.deliverBridgeResult(sandboxToken, callbackId, ...).
    oneway void bridgeCall(int sandboxToken, String method, String argsJson, String callbackId);

    // Lifecycle / diagnostics.
    oneway void onWebViewError(int sandboxToken, String code, String message);
    oneway void onPluginReady(int sandboxToken);
    oneway void onPluginCrashed(int sandboxToken, String reason);

    // v4 cleanup ACK — fired once when the sandbox finishes tearing
    // down an unloaded plugin (the unloading -> unloaded transition).
    // Authoritative for staged-bundle wipe + for accepting a
    // subsequent loadPlugin(pluginId) without
    // `concurrent_unload_in_progress` rejection.
    oneway void onPluginUnloaded(int sandboxToken);
}
