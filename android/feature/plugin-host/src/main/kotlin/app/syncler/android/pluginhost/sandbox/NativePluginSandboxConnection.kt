package app.syncler.android.pluginhost.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import app.syncler.core.pluginaidl.IPluginSandbox
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Phase 11 — per-token connection manager for the native Kotlin
 * sandbox.
 *
 * Unlike [PluginSandboxConnection] (which refcounts a single
 * shared bind to `:plugin`), the native sandbox uses
 * `Context.bindIsolatedService(instanceName=token.toString())` to
 * spawn ONE isolated process per native plugin token. Each native
 * load creates a new [ServiceConnection], so this class tracks
 * them in a map keyed by `sandboxToken`.
 *
 * Process death: when the OS reaps an isolated process (plugin
 * crashed, OOM'd, etc.), `ServiceConnection.onServiceDisconnected`
 * fires for ONLY that token's binding. Sibling native plugins are
 * unaffected — this is the per-token death guarantee the JS path
 * (shared process) can't make.
 *
 * API gate: `bindIsolatedService(intent, flags, executor,
 * instanceName, connection)` was added in API 29. The router
 * rejects native_kotlin publishes on lower API levels with
 * `native_only_api_29`.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class NativePluginSandboxConnection(
    private val appContext: Context,
) {

    private val perTokenConnections = ConcurrentHashMap<Int, PerTokenConnection>()

    @Volatile
    var onTokenDeath: (Int) -> Unit = {}

    /**
     * Bind a fresh isolated process for [sandboxToken] and return
     * the AIDL handle. Suspends until `onServiceConnected` fires.
     * Throws [SandboxBindFailedException] if `bindIsolatedService`
     * returns false (manifest misconfig, missing `:nativePlugin`
     * service, missing permission).
     *
     * One binding per token — calling this twice for the same
     * token is a host bug; the second call throws
     * [IllegalStateException].
     */
    suspend fun bindForToken(sandboxToken: Int): IPluginSandbox {
        if (perTokenConnections.containsKey(sandboxToken)) {
            error("native sandbox already bound for token=$sandboxToken")
        }
        val deferred = CompletableDeferred<IPluginSandbox>()
        val perToken = PerTokenConnection(sandboxToken, deferred)
        perTokenConnections[sandboxToken] = perToken
        val intent = Intent().apply {
            setClassName(
                appContext.packageName,
                "app.syncler.feature.pluginnativesandbox.PluginNativeSandboxService",
            )
        }
        val ok = appContext.bindIsolatedService(
            intent,
            Context.BIND_AUTO_CREATE,
            sandboxToken.toString(),
            { runnable -> runnable.run() }, // direct executor — bindings are infrequent
            perToken,
        )
        if (!ok) {
            perTokenConnections.remove(sandboxToken)
            runCatching { appContext.unbindService(perToken) }
            throw SandboxBindFailedException(
                "bindIsolatedService returned false for token=$sandboxToken " +
                    "— :nativePlugin process / manifest misconfigured",
            )
        }
        return deferred.await()
    }

    /**
     * Unbind a token's isolated process. The OS reaps the
     * synthesized UID once the last binding to the instance
     * releases. Idempotent — calling twice or after onTokenDeath
     * is a no-op.
     */
    fun unbindForToken(sandboxToken: Int) {
        val conn = perTokenConnections.remove(sandboxToken) ?: return
        runCatching { appContext.unbindService(conn) }
            .onFailure { Timber.tag(TAG).w(it, "unbindService(token=%d) threw", sandboxToken) }
    }

    private inner class PerTokenConnection(
        private val sandboxToken: Int,
        private val deferred: CompletableDeferred<IPluginSandbox>,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = IPluginSandbox.Stub.asInterface(service)
            deferred.complete(binder)
            Timber.tag(TAG).i("native sandbox connected token=%d", sandboxToken)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.tag(TAG).w("native sandbox disconnected token=%d", sandboxToken)
            deferred.completeExceptionally(
                SandboxBindFailedException("native sandbox token=$sandboxToken disconnected"),
            )
            perTokenConnections.remove(sandboxToken)
            runCatching { onTokenDeath(sandboxToken) }
                .onFailure { Timber.tag(TAG).w(it, "onTokenDeath threw token=%d", sandboxToken) }
        }
    }

    companion object {
        private const val TAG = "NativeSandboxConn"
    }
}
