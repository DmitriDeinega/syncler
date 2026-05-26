package app.syncler.feature.pluginnativesandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.IPluginSandbox
import app.syncler.core.pluginaidl.PluginLoadParcel
import app.syncler.plugin.runtime.ActionEvent
import app.syncler.plugin.runtime.InboxEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * Phase 11 native plugin sandbox service. Runs in
 * `:nativePlugin` with `android:isolatedProcess="true"` so each
 * binding gets a synthesized UID — see
 * docs/plugin-host-native-kotlin.md "Process model".
 *
 * The host binds via `Context.bindIsolatedService(intent,
 * Service.BIND_AUTO_CREATE, instanceName=token.toString(), ...)`,
 * which forces ONE isolated process per native plugin token. Two
 * plugins never share heap, ClassLoader, or coroutine scope. A
 * plugin crashing affects only that token.
 *
 * Within an isolated process there is only ever ONE token loaded
 * (the host binds fresh for each plugin). The state machine is
 * therefore simpler than [app.syncler.feature.pluginsandbox.PluginSandboxService]:
 * `loadPlugin` is a one-shot per process lifetime.
 */
class PluginNativeSandboxService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var host: RealNativePluginHost? = null
    @Volatile private var loadedToken: Int? = null
    @Volatile private var bridgeContext: BridgePluginContext? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { host?.close() }
            .onFailure { Timber.tag(TAG).w(it, "host close failed in onDestroy") }
        host = null
        loadedToken = null
        bridgeContext = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private val binder = object : IPluginSandbox.Stub() {

        override fun loadPlugin(
            request: PluginLoadParcel,
            callback: IPluginHostCallback,
            bundleFd: ParcelFileDescriptor?,
        ): Int {
            // Triad 136 fix (codex): close orphan FD on every
            // early-exit path so a buggy host doesn't leak FDs into
            // the isolated process's table. Each `throw` below
            // means RealNativePluginHost never gets ownership, so
            // we close here. (Helper closes silently if bundleFd
            // is already null.)
            try {
                validateParcel(request)
                if (request.renderer != "native_kotlin") {
                    throw IllegalStateException(NativeLoadFailureCodes.UNSUPPORTED_RENDERER)
                }
                if (bundleFd == null) {
                    throw IllegalStateException(NativeLoadFailureCodes.MISSING_BUNDLE_FD)
                }
                if (loadedToken != null) {
                    // Same isolated process is meant to host exactly
                    // one plugin (per Phase 11a v4). A second
                    // loadPlugin into the same process is a host bug.
                    throw IllegalStateException(NativeLoadFailureCodes.CONCURRENT_LOAD_IN_PROGRESS)
                }
            } catch (exc: Throwable) {
                closeFdSilently(bundleFd)
                throw exc
            }

            // Bundle FD non-null after validation block above —
            // smart-cast doesn't reach across the try boundary so we
            // assert explicitly.
            val verifiedFd = requireNotNull(bundleFd)
            val newHost = RealNativePluginHost(request, verifiedFd, callback)
            host = newHost
            loadedToken = request.sandboxToken
            try {
                newHost.startLoad()
            } catch (exc: Exception) {
                runCatching { newHost.close() }
                host = null
                loadedToken = null
                throw exc
            }
            // BridgePluginContext capture for deliverBridgeResult
            // routing — RealNativePluginHost holds the canonical
            // instance; the binder just needs its handle.
            bridgeContext = newHost.attachedBridgeContext()
            return request.sandboxToken
        }

        override fun unloadPlugin(sandboxToken: Int) {
            val token = loadedToken
            if (token == null || token != sandboxToken) {
                Timber.tag(TAG).w("unloadPlugin token=%d not loaded", sandboxToken)
                return
            }
            val h = host
            host = null
            loadedToken = null
            bridgeContext = null
            runCatching { h?.close() }
                .onFailure { Timber.tag(TAG).w(it, "host close threw") }
            // Phase 11 v4: report onPluginUnloaded so the host's
            // ServiceConnection ref-count releases. The host then
            // unbinds, Android kills the isolated process.
            // We rely on the host's `expectedToken` pinning to
            // attribute this callback safely.
            val pluginCb = h?.lastCallback()
            runCatching { pluginCb?.onPluginUnloaded(sandboxToken) }
                .onFailure { Timber.tag(TAG).w(it, "onPluginUnloaded callback failed") }
            // The isolated process exits on the host's unbind;
            // stopSelf is a defensive nudge in case the host
            // hasn't yet called unbindService.
            stopSelf()
        }

        override fun dispatchHook(
            sandboxToken: Int,
            hook: String,
            payloadJson: String,
            callbackId: String,
        ) {
            val token = loadedToken
            if (token == null || token != sandboxToken) {
                Timber.tag(TAG).w("dispatchHook token=%d not loaded", sandboxToken)
                return
            }
            val h = host ?: return
            when (hook) {
                "onInbox" -> h.submitInbox(
                    InboxEvent(
                        messageId = callbackId,
                        payloadJson = payloadJson,
                        receivedAtMillis = System.currentTimeMillis(),
                    ),
                )
                "onAction" -> h.submitAction(
                    ActionEvent(actionId = callbackId, payloadJson = payloadJson),
                )
                else -> Timber.tag(TAG).w("unknown hook=%s for native plugin", hook)
            }
        }

        override fun deliverBridgeResult(
            sandboxToken: Int,
            callbackId: String,
            resultJson: String,
        ) {
            val token = loadedToken
            if (token == null || token != sandboxToken) {
                Timber.tag(TAG).w("deliverBridgeResult token=%d not loaded", sandboxToken)
                return
            }
            bridgeContext?.onBridgeResult(callbackId, resultJson)
        }

        override fun querySandboxState(sandboxToken: Int): String {
            val token = loadedToken ?: return "unknown"
            if (token != sandboxToken) return "unknown"
            return if (host != null) "ready" else "unloaded"
        }
    }

    private fun validateParcel(request: PluginLoadParcel) {
        if (request.pluginId.isBlank() ||
            request.bundleHashHex.isBlank() ||
            request.entryClass.isBlank()
        ) {
            throw IllegalStateException(NativeLoadFailureCodes.PARCEL_MALFORMED)
        }
        if (request.diagnosticManifestJson.length > PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP) {
            throw IllegalStateException(NativeLoadFailureCodes.DIAGNOSTIC_FIELD_OVERSIZE)
        }
    }

    private fun closeFdSilently(fd: ParcelFileDescriptor?) {
        if (fd == null) return
        runCatching { fd.close() }
            .onFailure { Timber.tag(TAG).w(it, "orphan bundleFd close failed") }
    }

    companion object {
        private const val TAG = "PluginNativeSandbox"
    }
}
