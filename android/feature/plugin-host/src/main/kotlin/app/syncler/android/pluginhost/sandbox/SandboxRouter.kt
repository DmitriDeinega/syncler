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
        // Triad 117: pre-register the handle BEFORE the AIDL call so
        // a synchronous-or-oneway callback firing mid-load doesn't
        // arrive at the stub with no handle visible. On failure we
        // remove the handle in the catch — `releaseHandle` is
        // idempotent so a late `onPluginUnloaded` from the sandbox's
        // synchronous load-fail teardown won't double-release.
        val handle = SandboxHandle(
            sandboxToken = parcel.sandboxToken,
            pluginId = parcel.pluginId,
            router = this,
        )
        handles[parcel.sandboxToken] = handle
        try {
            val callback = PluginHostCallbackStub(parcel.sandboxToken)
            // Phase 11: `:plugin` is the JS subprocess and is NOT isolated,
            // so it can still read `parcel.bundleFilePath` directly from
            // /data/data/.../files/.... The native (DEX) loader goes
            // through a different sandbox (PluginNativeSandboxService)
            // which DOES need the FD. Pass null here.
            val returnedToken = sandbox.loadPlugin(parcel, callback, /* bundleFd = */ null)
            check(returnedToken == parcel.sandboxToken) {
                "sandbox returned token=$returnedToken but parcel.sandboxToken=" +
                    "${parcel.sandboxToken} — fatal sandbox bug"
            }
            return handle
        } catch (exc: Throwable) {
            // bindService succeeded but loadPlugin failed — undo
            // the pre-registration + release the connection ref.
            // If the sandbox's load-fail teardown fires a oneway
            // onPluginUnloaded after this, releaseHandle's
            // remove-returns-null guard skips the spurious release.
            handles.remove(parcel.sandboxToken)
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

    /**
     * Idempotent release. Triad 117 finding (Codex #3, Gemini #3):
     * only release when the handle is actually present in the map.
     * A stray `onPluginUnloaded` for an unknown token (e.g. from
     * the sandbox's sync load-fail teardown after the host catch
     * block already cleaned up) must not over-release the
     * connection ref count.
     *
     * Returns `true` if this call won the race for the token —
     * lets the caller gate any *additional* per-token work
     * (lifecycle dispatch, log lines) on the same atomic remove.
     * Triad 118 finding: the release was idempotent but the
     * lifecycle dispatch wasn't, so a load-fail teardown could
     * still emit bogus crash/unload signals to the dispatcher.
     */
    private fun releaseHandle(sandboxToken: Int): Boolean {
        val removed = handles.remove(sandboxToken)
        return if (removed != null) {
            scope.launch { connection.release() }
            true
        } else {
            Timber.tag(TAG).w(
                "releaseHandle for unknown token=%d — skipping release (idempotent guard)",
                sandboxToken,
            )
            false
        }
    }

    /**
     * Sandbox process died — `ServiceConnection.onServiceDisconnected`
     * fired. Triad 117 (Codex #2, Gemini CRITICAL): every active
     * handle leaked an `acquire()` that will never be balanced by
     * the (now-impossible) `onPluginUnloaded`. We MUST release each
     * one explicitly here, otherwise the ref count stays > 0 forever
     * and the idle-unbind path can never fire on a future
     * load → unload cycle.
     *
     * Triad 118 finding (Codex #1): the previous snapshot+clear
     * dance wasn't atomic against in-flight `onPluginUnloaded` /
     * `onPluginCrashed` from coroutines that had already started.
     * If both paths called `release()` for the same token, the ref
     * count over-decremented. Per-token `handles.remove(token) !=
     * null` gating closes the race — only whoever wins the remove
     * does the release + lifecycle dispatch.
     */
    private fun handleSandboxDeath() {
        // Snapshot keys (cheap copy) then iterate. The atomic
        // remove gates both the release AND the dispatch, so a
        // racing onPluginUnloaded that lost the remove emits
        // nothing additional.
        val tokens = handles.keys.toList()
        tokens.forEach { token ->
            val removed = handles.remove(token) ?: return@forEach
            scope.launch {
                bridgeDispatcher.onPluginCrashed(removed.sandboxToken, "process_died")
                connection.release()
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
            if (!handles.containsKey(sandboxToken)) {
                Timber.tag(TAG).w(
                    "onWebViewError for stale token=%d — dropping",
                    sandboxToken,
                )
                return
            }
            scope.launch { bridgeDispatcher.onWebViewError(sandboxToken, code, message) }
        }

        override fun onPluginReady(sandboxToken: Int) {
            if (!handles.containsKey(sandboxToken)) {
                Timber.tag(TAG).w(
                    "onPluginReady for stale token=%d — dropping",
                    sandboxToken,
                )
                return
            }
            scope.launch { bridgeDispatcher.onPluginReady(sandboxToken) }
        }

        override fun onPluginCrashed(sandboxToken: Int, reason: String) {
            // Triad 118 (Codex #2): gate dispatch on releaseHandle
            // winning the atomic remove. A stale crash from a
            // load-fail teardown (after the host catch already
            // cleaned up) loses the remove and we emit nothing —
            // otherwise the dispatcher would see a bogus crash
            // signal for a plugin it already cleaned up.
            if (releaseHandle(sandboxToken)) {
                scope.launch { bridgeDispatcher.onPluginCrashed(sandboxToken, reason) }
            }
        }

        override fun onPluginUnloaded(sandboxToken: Int) {
            // Same generation fence as onPluginCrashed.
            if (releaseHandle(sandboxToken)) {
                scope.launch { bridgeDispatcher.onPluginUnloaded(sandboxToken) }
            }
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
