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

    /**
     * V2 #11 + triad 142 closeout #2: route a user-triggered
     * action (notification tap, inbox action button, settings
     * UI button) into the plugin's `onAction` hook.
     *
     * Triad 142 codex #2 + gemini #2 FIX: returns a structured
     * outcome so the caller can fall back to fire-and-forget
     * POST only when the plugin truly cannot dispatch (codex
     * "load-or-dispatch first, fallback only after explicit
     * failure").
     */
    fun dispatchAction(
        pluginId: String,
        actionId: String,
        payloadJson: String,
    ): ActionDispatchOutcome {
        val instance = instances[pluginId]
            ?: return ActionDispatchOutcome.PLUGIN_NOT_LOADED
        instance.dispatchHook("onAction", payloadJson, actionId)
        return ActionDispatchOutcome.DISPATCHED
    }
}

/**
 * Outcome of [PluginRegistry.dispatchAction]. Callers MUST
 * branch on this to decide whether to fall back to a direct
 * sender POST (`TemplateActionRunner.post`) when dispatch
 * didn't reach the plugin.
 */
enum class ActionDispatchOutcome {
    /** Plugin was loaded and the hook was queued. */
    DISPATCHED,
    /** Plugin not currently in the registry. Caller should
     *  consider load-and-retry before falling back. */
    PLUGIN_NOT_LOADED,
}
