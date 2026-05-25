package app.syncler.android.pluginhost

/**
 * Phase 10b step 6: only the sandbox-process delivery survives.
 * `PluginBridge` runs in the host process; capability results
 * cross back to the plugin's JS via this abstraction, which is
 * a [app.syncler.android.pluginhost.sandbox.SandboxBridgeDelivery]
 * in production. Tests substitute a recording impl.
 *
 * Fire-and-forget — the caller doesn't await the actual JS
 * callback firing. The impl logs delivery failures but never
 * surfaces them (the underlying capability call already
 * completed; lost callback delivery means the plugin's promise
 * hangs until its own timeout).
 */
interface BridgeDelivery {
    fun deliver(callbackId: String, resultJson: String)
}
