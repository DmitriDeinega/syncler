package app.syncler.android.pluginhost

import app.syncler.android.pluginhost.sandbox.SandboxHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Phase 10b step 6: in-process WebView gone. A [PluginInstance]
 * now refers to its loaded plugin only through a [SandboxHandle]
 * — the actual JS runs in the `:plugin` subprocess and is
 * addressed via [app.syncler.android.pluginhost.sandbox.SandboxRouter].
 *
 * Both [sandboxHandle] and [bridge] are nullable so unit tests can
 * construct a bare instance for fields-only assertions
 * ([PluginLoaderTest]) without standing up the full sandbox
 * machinery. Production loaders always pass both.
 */
class PluginInstance(
    val manifest: PluginManifest,
    val grantedCapabilities: Set<String>,
    val bundleFilePath: String,
    /**
     * V2 #11 closeout (triad 140 codex #1 FIX): host-trusted
     * `actionId → endpoint` map populated at load time from the
     * server's `PluginLatestResponse.template.actions`. Used by
     * `MessageBridge.respond` so the plugin no longer supplies
     * an endpoint string — the host always uses its own
     * authoritative lookup. Empty for non-template plugins or
     * pre-V2 #11 loaders.
     */
    val actionEndpoints: Map<String, String> = emptyMap(),
    var sandboxHandle: SandboxHandle? = null,
    var bridge: PluginBridge? = null,
) {
    /**
     * Fire-and-forget hook dispatch. Matches the legacy in-process
     * shape (callers don't `await`). Failures are swallowed +
     * logged; the sandbox-side coordinator's per-token serial
     * queue handles ordering / state-gating.
     */
    fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        val handle = sandboxHandle ?: return
        instanceScope.launch {
            runCatching { handle.dispatchHook(hook, payloadJson, callbackId) }
                .onFailure { Timber.tag(TAG).w(it, "dispatchHook threw (token=%d)", handle.sandboxToken) }
        }
    }

    /**
     * Fire-and-forget unload. Sandbox confirms via
     * `onPluginUnloaded`; the registry's lifecycle listener
     * removes this instance once that arrives.
     */
    fun destroy() {
        val handle = sandboxHandle ?: return
        instanceScope.launch {
            runCatching { handle.unload() }
                .onFailure { Timber.tag(TAG).w(it, "unload threw (token=%d)", handle.sandboxToken) }
        }
    }

    private val instanceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "PluginInstance"
    }
}
