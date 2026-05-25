package app.syncler.android.pluginhost

import java.util.concurrent.ConcurrentHashMap

object PluginRegistry {
    private val instances = ConcurrentHashMap<String, PluginInstance>()

    fun put(instance: PluginInstance) {
        instances[instance.manifest.id]?.destroy()
        instances[instance.manifest.id] = instance
    }

    fun get(pluginId: String): PluginInstance? = instances[pluginId]

    /**
     * Host-initiated unload. Removes the instance from the
     * registry AND fires `destroy()` so the sandbox-side
     * coordinator gets the AIDL `unloadPlugin` call. Use this
     * when the host wants to tear down a plugin (settings UI,
     * permission revocation, app shutdown).
     *
     * For sandbox-originated terminal events
     * (`onPluginCrashed` / `onPluginUnloaded`), call
     * [handleSandboxTerminated] instead — that path knows the
     * sandbox is already torn down and doesn't re-acquire the
     * AIDL connection just to issue a redundant unload.
     */
    fun unload(pluginId: String) {
        instances.remove(pluginId)?.destroy()
    }

    /**
     * Sandbox-originated terminal event for [sandboxToken].
     * Phase 10b step 6 generation fence (triad 121 Codex RED):
     * removes the instance from the registry IFF its handle
     * still matches [sandboxToken]. A late callback from a
     * predecessor load won't evict the new instance.
     *
     * The matched instance's handle is nulled so any subsequent
     * `destroy()` (e.g. from `PluginRegistry.clear()` at app
     * shutdown) no-ops the AIDL leg — the sandbox side is
     * already torn down by the time this fires.
     */
    fun handleSandboxTerminated(pluginId: String, sandboxToken: Int) {
        val current = instances[pluginId] ?: return
        if (current.sandboxHandle?.sandboxToken != sandboxToken) return
        // Null the handle FIRST so any racing destroy() is a no-op.
        current.sandboxHandle = null
        instances.remove(pluginId, current)
    }

    fun clear() {
        instances.values.forEach(PluginInstance::destroy)
        instances.clear()
    }
}
