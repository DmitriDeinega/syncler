package app.syncler.feature.pluginsandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.IPluginSandbox
import app.syncler.core.pluginaidl.PluginLoadParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * Phase 10 multi-process plugin host: the `:plugin` subprocess
 * service. Declared in this module's AndroidManifest with
 * `android:process=":plugin"`.
 *
 * The service binds host clients via [IPluginSandbox.Stub], routes
 * AIDL calls to the right [PluginTokenCoordinator], and owns the
 * shared [PluginTokenRegistry]. Per-token work happens on
 * coroutines owned by each coordinator; the service's own scope
 * is short-lived bookkeeping only.
 *
 * Step 2 ships the wiring + state machine. The actual
 * [PluginWebViewHost] implementation is supplied by
 * [PluginWebViewHostFactory], which today returns a no-op stub —
 * step 5 of Phase 10b will land the real WebView implementation
 * once connectedAndroidTest is wired up.
 */
class PluginSandboxService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = PluginTokenRegistry()
    // Phase 10b step 5: the production factory builds real
    // WebView-backed sandbox hosts using this Service as Context.
    // Tests can override via WebViewHostFactoryOverride (step 5's
    // connectedAndroidTest harness).
    private val webViewHostFactory: PluginWebViewHostFactory by lazy {
        WebViewHostFactoryOverride.get() ?: RealPluginWebViewHostFactory(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Process is going away. Tear down every active token so
        // the host's onPluginUnloaded fires for each. (The host
        // also handles ServiceConnection.onServiceDisconnected as
        // the authoritative crash path; this is a best-effort
        // graceful cleanup.)
        registry.all().forEach { coordinator ->
            runCatching { coordinator.unload() }
                .onFailure { Timber.tag(TAG).w(it, "graceful unload failed") }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private val binder = object : IPluginSandbox.Stub() {

        override fun loadPlugin(
            request: PluginLoadParcel,
            callback: IPluginHostCallback,
        ): Int {
            validateParcel(request)
            // Same-token re-load is a host bug — we treat it as
            // CONCURRENT_LOAD_IN_PROGRESS to match the state-machine
            // table's "loadPlugin while loading" row.
            registry[request.sandboxToken]?.let { existing ->
                Timber.tag(TAG).w(
                    "loadPlugin token=%d already in state %s",
                    request.sandboxToken, existing.state,
                )
                throw IllegalStateException(LoadFailureCodes.CONCURRENT_LOAD_IN_PROGRESS)
            }

            val host = webViewHostFactory.create(request)
            val coordinator = PluginTokenCoordinator(
                sandboxToken = request.sandboxToken,
                pluginId = request.pluginId,
                callback = adaptCallback(callback),
                webViewHost = host,
                parentScope = serviceScope,
            )
            registry.put(coordinator)
            try {
                coordinator.startLoad(request)
            } catch (exc: Exception) {
                // Load threw — coordinator never reaches READY; we
                // drop it from the registry and rethrow over Binder.
                registry.remove(request.sandboxToken)
                runCatching { coordinator.unload() }
                throw exc
            }
            return request.sandboxToken
        }

        override fun unloadPlugin(sandboxToken: Int) {
            val coordinator = registry[sandboxToken]
            if (coordinator == null) {
                Timber.tag(TAG).w("unloadPlugin unknown token %d", sandboxToken)
                return
            }
            // Run on the coordinator's own dispatch (well, just
            // here on the Binder thread — unload is mostly state
            // updates + a WebView destroy that the impl can
            // marshal to main if needed).
            coordinator.unload()
            registry.remove(sandboxToken)
            maybeWipeProcessWebViewState()
        }

        override fun dispatchHook(
            sandboxToken: Int,
            hook: String,
            payloadJson: String,
            callbackId: String,
        ) {
            val coordinator = registry[sandboxToken] ?: run {
                Timber.tag(TAG).w("dispatchHook unknown token %d", sandboxToken)
                return
            }
            coordinator.submitHook(hook, payloadJson, callbackId)
        }

        override fun deliverBridgeResult(
            sandboxToken: Int,
            callbackId: String,
            resultJson: String,
        ) {
            val coordinator = registry[sandboxToken] ?: run {
                Timber.tag(TAG).w("deliverBridgeResult unknown token %d", sandboxToken)
                return
            }
            coordinator.submitBridgeResult(callbackId, resultJson)
        }

        override fun querySandboxState(sandboxToken: Int): String {
            val coordinator = registry[sandboxToken]
            return coordinator?.state?.wire ?: "unknown"
        }
    }

    /**
     * Validate [PluginLoadParcel] shape before instantiating a
     * coordinator. Per Phase 10a v4 error semantics, throws
     * [IllegalStateException] with a [LoadFailureCodes] code.
     *
     * Bundle hash re-verification + renderer support check happen
     * inside the [PluginWebViewHost.startLoad] impl (where they
     * have access to the actual file + the renderer registry).
     */
    private fun validateParcel(request: PluginLoadParcel) {
        if (request.pluginId.isBlank() || request.bundleFilePath.isBlank() ||
            request.bundleHashHex.isBlank()
        ) {
            throw IllegalStateException(LoadFailureCodes.PARCEL_MALFORMED)
        }
        if (request.diagnosticManifestJson.length > PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP) {
            throw IllegalStateException(LoadFailureCodes.DIAGNOSTIC_FIELD_OVERSIZE)
        }
    }

    /**
     * Process-global WebView state wipe per Phase 10a v4: ONLY
     * fired when zero tokens are in `READY`/`LOADING`. We don't
     * actually call `WebStorage.deleteAllData()` /
     * `CookieManager.removeAllCookies()` here in step 2 — those
     * need the real WebView impl. Stub left as a hook.
     */
    private fun maybeWipeProcessWebViewState() {
        if (registry.activeReadyOrLoadingCount() == 0) {
            Timber.tag(TAG).i(
                "no active plugins remain — WebView state wipe hook fires (step 5 impl)",
            )
            // step 5: WebStorage.getInstance().deleteAllData()
            //         CookieManager.getInstance().removeAllCookies(null)
        }
    }

    /**
     * Adapt an incoming AIDL [IPluginHostCallback] (a binder proxy
     * to the host process) into the plain Kotlin
     * [PluginHostCallbackLocal] the coordinator uses. Keeps AIDL
     * types out of the coordinator's signature so unit tests can
     * substitute a fake without touching `android.os.Binder`.
     *
     * `RemoteException` from any callback method gets swallowed
     * + logged — the host process may be dead, in which case our
     * service's `onDestroy` will fire shortly and clean up.
     */
    private fun adaptCallback(callback: IPluginHostCallback): PluginHostCallbackLocal =
        object : PluginHostCallbackLocal {
            override fun bridgeCall(sandboxToken: Int, method: String, argsJson: String, callbackId: String) {
                runCatching { callback.bridgeCall(sandboxToken, method, argsJson, callbackId) }
                    .onFailure { Timber.tag(TAG).w(it, "host callback.bridgeCall failed") }
            }
            override fun onWebViewError(sandboxToken: Int, code: String, message: String) {
                runCatching { callback.onWebViewError(sandboxToken, code, message) }
                    .onFailure { Timber.tag(TAG).w(it, "host callback.onWebViewError failed") }
            }
            override fun onPluginReady(sandboxToken: Int) {
                runCatching { callback.onPluginReady(sandboxToken) }
                    .onFailure { Timber.tag(TAG).w(it, "host callback.onPluginReady failed") }
            }
            override fun onPluginCrashed(sandboxToken: Int, reason: String) {
                runCatching { callback.onPluginCrashed(sandboxToken, reason) }
                    .onFailure { Timber.tag(TAG).w(it, "host callback.onPluginCrashed failed") }
            }
            override fun onPluginUnloaded(sandboxToken: Int) {
                runCatching { callback.onPluginUnloaded(sandboxToken) }
                    .onFailure { Timber.tag(TAG).w(it, "host callback.onPluginUnloaded failed") }
            }
        }

    companion object {
        private const val TAG = "PluginSandbox"
    }
}

/**
 * Factory the service uses to mint [PluginWebViewHost] per load.
 * Step 2 ships a no-op factory; step 5 will swap in the real
 * `WebView`-touching impl.
 */
fun interface PluginWebViewHostFactory {
    fun create(parcel: PluginLoadParcel): PluginWebViewHost
}

internal object NoopPluginWebViewHostFactory : PluginWebViewHostFactory {
    override fun create(parcel: PluginLoadParcel): PluginWebViewHost =
        NoopPluginWebViewHost
}

private object NoopPluginWebViewHost : PluginWebViewHost {
    override fun startLoad(parcel: PluginLoadParcel, bridgeBroker: BridgeBroker) {
        // No-op — the real impl evaluates JS in a WebView. Tests
        // substitute a recording double.
    }

    override fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        // No-op.
    }

    override fun deliverBridgeResult(callbackId: String, resultJson: String) {
        // No-op.
    }

    override fun destroy() {
        // No-op.
    }
}

/**
 * Step 5 test hook: connectedAndroidTest sets a recording factory
 * BEFORE binding the service so the e2e test can assert on
 * sandbox-side behavior without instantiating a real WebView (the
 * test harness can't always start a WebView on an emulator).
 *
 * Setting the override after the service has been instantiated has
 * no effect — the factory is resolved on first use via the
 * service's `lazy` delegate. Tests that need to swap mid-run
 * should kill the `:plugin` process between scenarios.
 */
object WebViewHostFactoryOverride {
    @Volatile
    private var override: PluginWebViewHostFactory? = null

    fun set(factory: PluginWebViewHostFactory?) {
        override = factory
    }

    internal fun get(): PluginWebViewHostFactory? = override
}
