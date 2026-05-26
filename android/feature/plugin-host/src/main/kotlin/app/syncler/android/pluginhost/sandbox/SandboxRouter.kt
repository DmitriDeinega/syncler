package app.syncler.android.pluginhost.sandbox

import android.os.Build
import android.os.ParcelFileDescriptor
import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.IPluginSandbox
import app.syncler.core.pluginaidl.PluginLoadParcel
import java.io.File
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
    /**
     * Phase 11: native plugin connection. Null when the host is
     * running on an API level below 29 (bindIsolatedService gate)
     * — native_kotlin publishes are rejected with
     * `native_only_api_29` at load time instead. Caller is
     * responsible for the null vs. non-null decision; the router
     * does not gate construction.
     */
    private val nativeConnection: NativePluginSandboxConnection? = null,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val nextToken = AtomicInteger(1)
    private val handles = java.util.concurrent.ConcurrentHashMap<Int, SandboxHandle>()

    /** Phase 11: per-token IPluginSandbox cache for native plugins. */
    private val nativeSandboxes = java.util.concurrent.ConcurrentHashMap<Int, IPluginSandbox>()

    init {
        connection.onSandboxDeath = ::handleSandboxDeath
        nativeConnection?.onTokenDeath = ::handleNativeTokenDeath
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
     *
     * Phase 11: routes by `parcel.renderer`. `script` goes to the
     * shared `:plugin` connection (one process, refcounted bind).
     * `native_kotlin` goes to [NativePluginSandboxConnection]
     * which spawns one isolated process per token via
     * `bindIsolatedService(instanceName=token)`. Native loads on
     * API < 29 fail synchronously with `native_only_api_29`
     * before any bind is attempted.
     */
    suspend fun loadPlugin(parcel: PluginLoadParcel): SandboxHandle =
        when (parcel.renderer) {
            "native_kotlin" -> loadNative(parcel)
            "script_fast" -> {
                // V2 #13: server accepts script_fast for the
                // publish pipeline, but the Android engine is
                // V0.2 work. Reject the load here so the host's
                // existing error-code UX surfaces the gap
                // instead of silently falling through to the
                // WebView path (which would misinterpret the
                // bundle).
                throw IllegalStateException(SCRIPT_FAST_NOT_AVAILABLE)
            }
            else -> loadJs(parcel)
        }

    private suspend fun loadJs(parcel: PluginLoadParcel): SandboxHandle {
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
            isNative = false,
        )
        handles[parcel.sandboxToken] = handle
        try {
            val callback = PluginHostCallbackStub(parcel.sandboxToken)
            // `:plugin` is the JS subprocess and is NOT isolated, so
            // it reads `parcel.bundleFilePath` directly. bundleFd is
            // exclusively for the native (isolated UID) path.
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

    private suspend fun loadNative(parcel: PluginLoadParcel): SandboxHandle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // bindIsolatedService(instanceName=...) is API 29+. On
            // older devices the manifest's isolatedProcess=true
            // still works as a hard process separation, but we
            // can't get per-token isolation, which is the whole
            // point. Reject the load loudly.
            throw IllegalStateException(NATIVE_ONLY_API_29)
        }
        val native = nativeConnection
            ?: throw IllegalStateException(NATIVE_ONLY_API_29)

        // The isolated sandbox UID can't read host /data/data/...,
        // so the host opens the staged DEX file here (host UID has
        // access) and passes the resulting PFD over Binder.
        val bundleFile = File(parcel.bundleFilePath)
        val bundleFd = try {
            ParcelFileDescriptor.open(bundleFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (exc: Throwable) {
            Timber.tag(TAG).e(exc, "failed to open bundleFd path=%s", parcel.bundleFilePath)
            throw IllegalStateException("missing_bundle_fd")
        }

        val handle = SandboxHandle(
            sandboxToken = parcel.sandboxToken,
            pluginId = parcel.pluginId,
            router = this,
            isNative = true,
        )
        handles[parcel.sandboxToken] = handle
        try {
            val sandbox = native.bindForToken(parcel.sandboxToken)
            nativeSandboxes[parcel.sandboxToken] = sandbox
            val callback = PluginHostCallbackStub(parcel.sandboxToken)
            val returnedToken = sandbox.loadPlugin(parcel, callback, bundleFd)
            check(returnedToken == parcel.sandboxToken) {
                "native sandbox returned token=$returnedToken but parcel.sandboxToken=" +
                    "${parcel.sandboxToken} — fatal sandbox bug"
            }
            return handle
        } catch (exc: Throwable) {
            handles.remove(parcel.sandboxToken)
            nativeSandboxes.remove(parcel.sandboxToken)
            native.unbindForToken(parcel.sandboxToken)
            throw exc
        } finally {
            // Triad 136 fix (codex + gemini): close host FD in both
            // paths. Binder dup'd it into the sandbox process at IPC
            // time, and loadPlugin is synchronous — the sandbox has
            // already read its dup before returning. Leaving the host
            // dup to GC was an FD-table-pressure footgun under
            // moderate plugin churn.
            runCatching { bundleFd.close() }
                .onFailure { Timber.tag(TAG).w(it, "host bundleFd close failed") }
        }
    }

    /**
     * Begin unload. Sandbox fires `onPluginUnloaded` when the
     * teardown completes; the host then releases the connection
     * ref via [releaseHandle].
     *
     * Phase 11: native and JS paths use the same callback signal
     * but different connection types.
     */
    suspend fun unload(handle: SandboxHandle) {
        if (handle.isNative) {
            val sandbox = nativeSandboxes[handle.sandboxToken] ?: return
            sandbox.unloadPlugin(handle.sandboxToken)
            return
        }
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
        if (handle.isNative) {
            val sandbox = nativeSandboxes[handle.sandboxToken] ?: return
            sandbox.dispatchHook(handle.sandboxToken, hook, payloadJson, callbackId)
            return
        }
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
        val handle = handles[sandboxToken]
        if (handle?.isNative == true) {
            val sandbox = nativeSandboxes[sandboxToken] ?: return
            sandbox.deliverBridgeResult(sandboxToken, callbackId, resultJson)
            return
        }
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
            if (removed.isNative) {
                // Native: unbind the per-token isolated process.
                // The OS reaps the synthesized UID on last unbind.
                nativeSandboxes.remove(sandboxToken)
                nativeConnection?.unbindForToken(sandboxToken)
            } else {
                scope.launch { connection.release() }
            }
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
     * Phase 11: a single native plugin's isolated process died.
     * Unlike [handleSandboxDeath] (which reaps EVERY active handle
     * because the shared `:plugin` process is gone), this only
     * affects ONE token — siblings keep running in their own
     * processes. That per-token isolation is the point.
     */
    private fun handleNativeTokenDeath(sandboxToken: Int) {
        val removed = handles.remove(sandboxToken) ?: return
        nativeSandboxes.remove(sandboxToken)
        scope.launch {
            bridgeDispatcher.onPluginCrashed(removed.sandboxToken, "process_died")
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
        // Phase 11 scope tweak: this fires ONLY for the shared
        // `:plugin` (JS) process. Native plugins each get their
        // own isolated process and report via
        // [handleNativeTokenDeath]; we must NOT reap native
        // handles here (their processes are still alive).
        val tokens = handles.entries
            .filter { !it.value.isNative }
            .map { it.key }
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

        /**
         * Phase 11 host-side error code: native_kotlin renderer
         * needs API 29+ (bindIsolatedService instanceName overload).
         * This is host-emitted only — the sandbox never returns it.
         * Lives in the host module so callers can match on the
         * constant rather than the literal.
         */
        const val NATIVE_ONLY_API_29: String = "native_only_api_29"

        /**
         * V2 #13 host-side error code: the publish pipeline accepts
         * `script_fast` plugins but the Android execution engine
         * (QuickJS/Javy) ships in V0.2. The sandbox emits this
         * code when asked to load one in the interim.
         */
        const val SCRIPT_FAST_NOT_AVAILABLE: String = "script_fast_not_available"
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
    /**
     * Phase 11: `true` if this handle is for a native_kotlin
     * plugin (bound via NativePluginSandboxConnection, lives in
     * its own isolated process). `false` for JS plugins
     * (PluginSandboxConnection, shared :plugin process).
     */
    val isNative: Boolean = false,
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
