package app.syncler.feature.pluginsandbox

import app.syncler.core.pluginaidl.PluginLoadParcel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Per-token coordinator. Holds the [PluginSandboxState], the per-
 * token serial Channel (so concurrent host-side oneway calls land
 * in order), and the [PluginWebViewHost] instance.
 *
 * The coordinator itself is thread-safe via the [stateLock]
 * monitor. Hook + bridge-result delivery hops through [eventChannel]
 * so the WebView host sees them sequentially even when multiple
 * Binder threads call us at once.
 *
 * Lifecycle: created on [loadPlugin], destroyed on [unload] OR
 * `process death` (no in-process signal for that — see
 * [PluginSandboxService] for the host-side handling).
 *
 * See `docs/plugin-host-multi-process.md` "Lifecycle + state
 * machine" + "Threading".
 */
class PluginTokenCoordinator(
    val sandboxToken: Int,
    val pluginId: String,
    private val callback: PluginHostCallbackLocal,
    private val webViewHost: PluginWebViewHost,
    parentScope: CoroutineScope,
) {
    private val stateLock = Any()

    @Volatile
    private var _state: PluginSandboxState = PluginSandboxState.LOADING

    val state: PluginSandboxState get() = _state

    /**
     * Per-token serial queue. CAPACITY=64 — generous; if the queue
     * backs up that far we have a plugin that can't keep up and
     * the host should slow down anyway. Channel.UNLIMITED is
     * tempting but lets a runaway host OOM the sandbox.
     */
    private val eventChannel = Channel<Event>(capacity = 64)

    private val tokenScope: CoroutineScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + Dispatchers.Default,
    )

    private val pumpJob: Job = tokenScope.launch {
        for (event in eventChannel) {
            when (event) {
                is Event.Hook -> webViewHost.dispatchHook(
                    event.hook, event.payloadJson, event.callbackId,
                )
                is Event.BridgeResult -> webViewHost.deliverBridgeResult(
                    event.callbackId, event.resultJson,
                )
            }
        }
    }

    /**
     * Kick off the WebView load. Caller is the AIDL `loadPlugin`
     * entry point. May throw with a [LoadFailureCodes] string.
     */
    fun startLoad(parcel: PluginLoadParcel) {
        synchronized(stateLock) {
            check(_state == PluginSandboxState.LOADING) {
                "startLoad called in state $_state — bug"
            }
        }
        webViewHost.startLoad(parcel, ::onBridgeCallFromWebView)
    }

    private fun onBridgeCallFromWebView(method: String, argsJson: String, callbackId: String) {
        // No-state-gate here intentionally — the WebView may emit
        // a bridge call between our state transition to UNLOADING
        // and the actual WebView teardown. We deliver up to the
        // host; the host's pending-callback map decides whether to
        // satisfy or drop. The adapter in PluginSandboxService
        // swallows any RemoteException from a dead host.
        callback.bridgeCall(sandboxToken, method, argsJson, callbackId)
    }

    /**
     * Sandbox-side equivalent of `onPluginReady` — called by the
     * WebView host once the JS bundle is parsed + the plugin's
     * `init` hook returned. Transitions [LOADING] -> [READY] and
     * notifies the host via `onPluginReady(sandboxToken)`.
     */
    fun reportReady() {
        val didTransition = synchronized(stateLock) {
            if (_state == PluginSandboxState.LOADING) {
                _state = PluginSandboxState.READY
                true
            } else false
        }
        if (didTransition) {
            callback.onPluginReady(sandboxToken)
        }
    }

    /** WebView host reports an unrecoverable runtime error. */
    fun reportError(code: String, message: String) {
        synchronized(stateLock) {
            if (_state == PluginSandboxState.LOADING ||
                _state == PluginSandboxState.READY
            ) {
                _state = PluginSandboxState.ERRORED
            }
        }
        callback.onWebViewError(sandboxToken, code, message)
    }

    /**
     * Host called `dispatchHook`. Enqueue for the pump unless the
     * token is past `READY`. Out-of-window calls are warn-logged
     * and dropped per the state-machine table.
     */
    fun submitHook(hook: String, payloadJson: String, callbackId: String) {
        val accept = synchronized(stateLock) {
            _state == PluginSandboxState.LOADING || _state == PluginSandboxState.READY
        }
        if (!accept) {
            Timber.tag(TAG).w(
                "dispatchHook dropped in state %s (token=%d hook=%s)",
                _state, sandboxToken, hook,
            )
            return
        }
        val sent = eventChannel.trySend(Event.Hook(hook, payloadJson, callbackId))
        if (!sent.isSuccess) {
            Timber.tag(TAG).w(
                "dispatchHook channel full (token=%d hook=%s) — dropping",
                sandboxToken, hook,
            )
        }
    }

    /** Host called `deliverBridgeResult`. Same accept rules as hooks. */
    fun submitBridgeResult(callbackId: String, resultJson: String) {
        val accept = synchronized(stateLock) {
            _state == PluginSandboxState.LOADING || _state == PluginSandboxState.READY
        }
        if (!accept) {
            Timber.tag(TAG).w(
                "deliverBridgeResult dropped in state %s (token=%d callback=%s)",
                _state, sandboxToken, callbackId,
            )
            return
        }
        val sent = eventChannel.trySend(Event.BridgeResult(callbackId, resultJson))
        if (!sent.isSuccess) {
            Timber.tag(TAG).w(
                "deliverBridgeResult channel full (token=%d callback=%s) — dropping",
                sandboxToken, callbackId,
            )
        }
    }

    /**
     * Begin teardown. Transitions to [UNLOADING], cancels the pump,
     * destroys the WebView, fires `onPluginUnloaded(sandboxToken)`,
     * transitions to [UNLOADED]. Idempotent.
     */
    fun unload() {
        val shouldRun = synchronized(stateLock) {
            when (_state) {
                PluginSandboxState.UNLOADING,
                PluginSandboxState.UNLOADED -> false
                else -> {
                    _state = PluginSandboxState.UNLOADING
                    true
                }
            }
        }
        if (!shouldRun) return

        // Stop accepting new events; let the pump drain anything
        // in-flight before we destroy the WebView.
        eventChannel.close()

        // Best-effort: wait briefly for the pump to drain. If a
        // hook is mid-evaluateJavascript, we accept it'll finish
        // racing the WebView destroy — webViewHost.destroy is the
        // canonical "JS is gone" signal.
        runCatching { webViewHost.destroy() }
            .onFailure { Timber.tag(TAG).w(it, "webViewHost.destroy threw (token=%d)", sandboxToken) }

        synchronized(stateLock) {
            _state = PluginSandboxState.UNLOADED
        }
        callback.onPluginUnloaded(sandboxToken)

        // Release coroutines.
        tokenScope.cancel()
    }

    private sealed interface Event {
        data class Hook(val hook: String, val payloadJson: String, val callbackId: String) : Event
        data class BridgeResult(val callbackId: String, val resultJson: String) : Event
    }

    companion object {
        private const val TAG = "PluginSandbox"
    }
}

/**
 * Process-wide token registry. Used by [PluginSandboxService] to
 * look up the right coordinator per AIDL transaction. Concurrent-
 * map keyed by [Int] token; coordinator lifecycle owns its own
 * thread-safety.
 */
class PluginTokenRegistry {
    private val coordinators = ConcurrentHashMap<Int, PluginTokenCoordinator>()

    fun put(coordinator: PluginTokenCoordinator) {
        coordinators[coordinator.sandboxToken] = coordinator
    }

    operator fun get(sandboxToken: Int): PluginTokenCoordinator? =
        coordinators[sandboxToken]

    fun remove(sandboxToken: Int): PluginTokenCoordinator? =
        coordinators.remove(sandboxToken)

    fun activeReadyOrLoadingCount(): Int = coordinators.values.count { c ->
        c.state == PluginSandboxState.READY || c.state == PluginSandboxState.LOADING
    }

    fun all(): Collection<PluginTokenCoordinator> = coordinators.values.toList()
}
