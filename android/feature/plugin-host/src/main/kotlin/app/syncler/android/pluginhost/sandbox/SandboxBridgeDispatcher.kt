package app.syncler.android.pluginhost.sandbox

import app.syncler.android.pluginhost.BridgeDelivery
import app.syncler.android.pluginhost.PluginBridge
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Host-side adapter from sandbox-originated events to the
 * existing `PluginBridge` capability dispatch. Phase 10b step 5b.
 *
 * `SandboxRouter` calls into this dispatcher whenever the sandbox
 * emits a `bridgeCall` or a lifecycle event. The dispatcher
 * routes via the per-token bridge map (one [PluginBridge] per
 * active plugin) — capability dispatch happens in the host
 * process exactly the same way the in-process path did.
 *
 * Bridge results flow back to the sandbox via
 * [SandboxBridgeDelivery], which the per-plugin [PluginBridge]
 * was constructed with.
 */
class SandboxBridgeDispatcher : BridgeDispatcher {

    private val bridges = ConcurrentHashMap<Int, PluginBridge>()
    private val lifecycleListeners = ConcurrentHashMap<Int, LifecycleListener>()

    /**
     * Register the [PluginBridge] for a freshly-loaded sandbox
     * token. Caller (the rewired loader) MUST do this before
     * the sandbox's `onPluginReady` arrives — race-free because
     * we register synchronously before `SandboxRouter.loadPlugin`
     * returns (the host-side loader controls the order).
     */
    fun registerBridge(sandboxToken: Int, bridge: PluginBridge) {
        bridges[sandboxToken] = bridge
    }

    /** Remove the bridge mapping for [sandboxToken]. Idempotent. */
    fun unregisterBridge(sandboxToken: Int) {
        bridges.remove(sandboxToken)
        lifecycleListeners.remove(sandboxToken)
    }

    /**
     * Hook a per-token lifecycle listener (e.g. the
     * `PluginRegistry`'s "this plugin is now ready" /
     * "this plugin crashed" handlers). Optional — null entry
     * means we just no-op on lifecycle events for that token.
     */
    fun registerLifecycleListener(sandboxToken: Int, listener: LifecycleListener) {
        lifecycleListeners[sandboxToken] = listener
    }

    override suspend fun bridgeCall(
        sandboxToken: Int,
        method: String,
        argsJson: String,
        callbackId: String,
    ) {
        val bridge = bridges[sandboxToken] ?: run {
            Timber.tag(TAG).w(
                "bridgeCall for unknown token=%d method=%s — dropping",
                sandboxToken, method,
            )
            return
        }
        // PluginBridge.call is the same entry point the in-process
        // JavascriptInterface used. It runs the capability logic on
        // its own CoroutineScope and delivers the result via the
        // BridgeDelivery the bridge was constructed with — which is
        // a SandboxBridgeDelivery wired to this sandboxToken.
        bridge.call(method, argsJson, callbackId)
    }

    override suspend fun onWebViewError(sandboxToken: Int, code: String, message: String) {
        lifecycleListeners[sandboxToken]?.onWebViewError(code, message)
    }

    override suspend fun onPluginReady(sandboxToken: Int) {
        lifecycleListeners[sandboxToken]?.onPluginReady()
    }

    override suspend fun onPluginCrashed(sandboxToken: Int, reason: String) {
        lifecycleListeners[sandboxToken]?.onPluginCrashed(reason)
        unregisterBridge(sandboxToken)
    }

    override suspend fun onPluginUnloaded(sandboxToken: Int) {
        lifecycleListeners[sandboxToken]?.onPluginUnloaded()
        unregisterBridge(sandboxToken)
    }

    interface LifecycleListener {
        fun onPluginReady()
        fun onWebViewError(code: String, message: String)
        fun onPluginCrashed(reason: String)
        fun onPluginUnloaded()
    }

    companion object {
        private const val TAG = "SandboxBridgeDispatch"
    }
}

/**
 * [BridgeDelivery] that ships a capability result back to a
 * sandboxed plugin via `SandboxRouter.deliverBridgeResult`. The
 * per-plugin [PluginBridge] is constructed with one of these so
 * its `deliver(callbackId, json)` path no longer touches a
 * WebView directly.
 */
class SandboxBridgeDelivery(
    private val sandboxToken: Int,
    private val routerProvider: () -> SandboxRouter?,
    private val scope: CoroutineScope,
) : BridgeDelivery {
    override fun deliver(callbackId: String, resultJson: String) {
        val router = routerProvider() ?: run {
            Timber.tag(TAG).w(
                "deliver for token=%d callback=%s — router gone, dropping",
                sandboxToken, callbackId,
            )
            return
        }
        // Fire-and-forget — the BridgeDelivery contract is no-await.
        // Launch on the SandboxBridgeDispatcher's scope so a slow
        // AIDL call doesn't pin the coroutine the bridge's
        // capability work ran on.
        scope.launch {
            runCatching {
                router.deliverBridgeResult(sandboxToken, callbackId, resultJson)
            }.onFailure {
                Timber.tag(TAG).w(it, "deliverBridgeResult threw (token=%d)", sandboxToken)
            }
        }
    }

    companion object {
        private const val TAG = "SandboxBridgeDelivery"
    }
}
