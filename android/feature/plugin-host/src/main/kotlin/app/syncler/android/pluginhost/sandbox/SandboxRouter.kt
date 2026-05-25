package app.syncler.android.pluginhost.sandbox

import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.PluginLoadParcel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Host-side counterpart to the sandbox's `PluginTokenCoordinator`.
 *
 * - Allocates `sandboxToken` (process-local monotonic int).
 * - Receives `IPluginHostCallback` calls from the sandbox and
 *   routes them to the appropriate per-token handler.
 * - Owns the host-side pending-callback map for bridge calls so
 *   capability results land back at the right `callbackId`.
 * - Provides the high-level entry points
 *   [loadPlugin] / [unload] / [dispatchHook] /
 *   [deliverBridgeResult] that the rest of the host calls — they
 *   round-trip through [PluginSandboxConnection] to the sandbox
 *   AIDL.
 *
 * Step 4 ships scaffolding only — step 5 wires it into
 * `PluginBridge` and the e2e test. The class is thread-safe via
 * [tokenMutex] + atomic counters; AIDL callbacks land on Binder
 * threads and dispatch to `bridgeDispatcher` on its own coroutine
 * scope so the Binder thread doesn't block on capability work.
 */
class SandboxRouter(
    private val connection: PluginSandboxConnection,
    private val bridgeDispatcher: BridgeDispatcher,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val nextToken = AtomicInteger(1)
    private val handles = java.util.concurrent.ConcurrentHashMap<Int, SandboxHandle>()

    init {
        connection.onSandboxDeath = ::handleSandboxDeath
    }

    /**
     * Allocate a fresh `sandboxToken`. Use this BEFORE staging the
     * bundle so the staged path can include it. The token isn't
     * registered with [PluginSandboxConnection] until [loadPlugin]
     * succeeds — caller can throw away the value without leaking
     * state.
     */
    fun allocateToken(): Int = nextToken.getAndIncrement()

    /**
     * Bind the sandbox (if not bound), invoke `loadPlugin`, and
     * return a [SandboxHandle] the caller uses for hook dispatch
     * and unload. Suspends until the sandbox acknowledges
     * load — on failure the AIDL `IllegalStateException` (with a
     * structured failure code) propagates.
     */
    suspend fun loadPlugin(parcel: PluginLoadParcel): SandboxHandle {
        val sandbox = connection.acquire()
        try {
            val callback = PluginHostCallbackStub(parcel.sandboxToken)
            val returnedToken = sandbox.loadPlugin(parcel, callback)
            check(returnedToken == parcel.sandboxToken) {
                "sandbox returned token=$returnedToken but parcel.sandboxToken=" +
                    "${parcel.sandboxToken} — fatal sandbox bug"
            }
            val handle = SandboxHandle(
                sandboxToken = parcel.sandboxToken,
                pluginId = parcel.pluginId,
                router = this,
            )
            handles[parcel.sandboxToken] = handle
            return handle
        } catch (exc: Throwable) {
            // bindService succeeded but loadPlugin failed — release
            // the connection ref so we don't leak.
            connection.release()
            throw exc
        }
    }

    /**
     * Begin unload. Sandbox fires `onPluginUnloaded` when the
     * teardown completes; the host then releases the connection
     * ref via [releaseHandle].
     */
    suspend fun unload(handle: SandboxHandle) {
        val sandbox = connection.acquire()
        try {
            sandbox.unloadPlugin(handle.sandboxToken)
        } finally {
            // We just paid an extra acquire to do the call; release
            // it. The release that pairs with loadPlugin's acquire
            // fires when onPluginUnloaded comes back.
            connection.release()
        }
    }

    suspend fun dispatchHook(
        handle: SandboxHandle,
        hook: String,
        payloadJson: String,
        callbackId: String,
    ) {
        val sandbox = connection.acquire()
        try {
            sandbox.dispatchHook(handle.sandboxToken, hook, payloadJson, callbackId)
        } finally {
            connection.release()
        }
    }

    suspend fun deliverBridgeResult(
        sandboxToken: Int,
        callbackId: String,
        resultJson: String,
    ) {
        val sandbox = connection.acquire()
        try {
            sandbox.deliverBridgeResult(sandboxToken, callbackId, resultJson)
        } finally {
            connection.release()
        }
    }

    private fun releaseHandle(sandboxToken: Int) {
        handles.remove(sandboxToken)
        scope.launch { connection.release() }
    }

    private fun handleSandboxDeath() {
        // Snapshot + clear, then notify each handle's bridge
        // dispatcher with a synthetic "plugin_crashed" so it can
        // drain pending-callback maps.
        val dead = handles.values.toList()
        handles.clear()
        dead.forEach { handle ->
            scope.launch {
                bridgeDispatcher.onPluginCrashed(handle.sandboxToken, "process_died")
            }
        }
    }

    /**
     * AIDL stub the sandbox calls into. Routes every event to
     * [bridgeDispatcher] or to the per-handle bookkeeping.
     */
    private inner class PluginHostCallbackStub(
        private val expectedToken: Int,
    ) : IPluginHostCallback.Stub() {

        override fun bridgeCall(
            sandboxToken: Int,
            method: String,
            argsJson: String,
            callbackId: String,
        ) {
            // Generation-fence — drop calls whose token doesn't
            // match an active handle (e.g. stale callbacks from a
            // sandboxed plugin that we've already unloaded).
            if (!handles.containsKey(sandboxToken)) {
                Timber.tag(TAG).w(
                    "bridgeCall for stale token=%d (expected=%d) — dropping",
                    sandboxToken, expectedToken,
                )
                return
            }
            scope.launch {
                bridgeDispatcher.bridgeCall(sandboxToken, method, argsJson, callbackId)
            }
        }

        override fun onWebViewError(sandboxToken: Int, code: String, message: String) {
            scope.launch { bridgeDispatcher.onWebViewError(sandboxToken, code, message) }
        }

        override fun onPluginReady(sandboxToken: Int) {
            scope.launch { bridgeDispatcher.onPluginReady(sandboxToken) }
        }

        override fun onPluginCrashed(sandboxToken: Int, reason: String) {
            scope.launch { bridgeDispatcher.onPluginCrashed(sandboxToken, reason) }
            // Drop the handle proactively — onPluginCrashed implies
            // the sandbox isn't going to fire onPluginUnloaded.
            releaseHandle(sandboxToken)
        }

        override fun onPluginUnloaded(sandboxToken: Int) {
            scope.launch { bridgeDispatcher.onPluginUnloaded(sandboxToken) }
            releaseHandle(sandboxToken)
        }
    }

    companion object {
        private const val TAG = "SandboxRouter"
    }
}

/**
 * Handle the host uses to address a loaded plugin in the sandbox.
 * Opaque from outside the router; the bridge holds one per active
 * plugin instance.
 */
class SandboxHandle internal constructor(
    val sandboxToken: Int,
    val pluginId: String,
    private val router: SandboxRouter,
) {
    suspend fun unload() = router.unload(this)
    suspend fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        router.dispatchHook(this, hook, payloadJson, callbackId)
    }
    suspend fun deliverBridgeResult(callbackId: String, resultJson: String) {
        router.deliverBridgeResult(sandboxToken, callbackId, resultJson)
    }
}

/**
 * The dispatcher contract the [SandboxRouter] uses to surface
 * sandbox events into host code. Step 5 will provide a real
 * implementation that adapts to the existing `PluginBridge`
 * capability dispatch + the `PluginInstance` lifecycle hooks.
 */
interface BridgeDispatcher {
    suspend fun bridgeCall(sandboxToken: Int, method: String, argsJson: String, callbackId: String)
    suspend fun onWebViewError(sandboxToken: Int, code: String, message: String)
    suspend fun onPluginReady(sandboxToken: Int)
    suspend fun onPluginCrashed(sandboxToken: Int, reason: String)
    suspend fun onPluginUnloaded(sandboxToken: Int)
}
