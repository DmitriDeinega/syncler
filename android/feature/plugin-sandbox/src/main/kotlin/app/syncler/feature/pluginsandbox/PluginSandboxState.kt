package app.syncler.feature.pluginsandbox

/**
 * Phase 10a v4 lifecycle states for a single `sandboxToken`. See
 * `docs/plugin-host-multi-process.md` "Lifecycle + state machine".
 *
 * The string forms match the contract returned by
 * `IPluginSandbox.querySandboxState`.
 */
enum class PluginSandboxState(val wire: String) {
    /** Bundle is staging / WebView is evaluating the JS. */
    LOADING("loading"),

    /** Plugin reported `onPluginReady`; hooks + bridge calls flow. */
    READY("ready"),

    /** `onWebViewError` fired; hooks dropped until unload. */
    ERRORED("errored"),

    /**
     * Host called `unloadPlugin` and sandbox is mid-teardown. Will
     * transition to [UNLOADED] when WebView destroy + JS heap
     * teardown + staged-bundle wipe-ACK complete, at which point the
     * sandbox fires `IPluginHostCallback.onPluginUnloaded`.
     */
    UNLOADING("unloading"),

    /** Cleanup complete; token slot available for re-use semantics. */
    UNLOADED("unloaded"),

    /**
     * Sandbox process died (visible to the HOST only — by definition
     * the sandbox can't report its own death). The host transitions
     * the token here on `ServiceConnection.onServiceDisconnected`.
     * `querySandboxState` returning this from a still-alive sandbox
     * is a bug.
     */
    PROCESS_DEAD("process_dead"),
}

/**
 * Structured load-failure codes the sandbox returns via
 * `IllegalStateException` over Binder on `loadPlugin`. See
 * `docs/plugin-host-multi-process.md` "Error semantics".
 */
object LoadFailureCodes {
    const val PARCEL_MALFORMED = "parcel_malformed"
    const val BUNDLE_HASH_MISMATCH = "bundle_hash_mismatch"
    const val UNSUPPORTED_RENDERER = "unsupported_renderer"
    const val DIAGNOSTIC_FIELD_OVERSIZE = "diagnostic_field_oversize"
    const val CONCURRENT_LOAD_IN_PROGRESS = "concurrent_load_in_progress"
    const val CONCURRENT_UNLOAD_IN_PROGRESS = "concurrent_unload_in_progress"
}
