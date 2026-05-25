package app.syncler.android.pluginhost.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.syncler.core.pluginaidl.IPluginSandbox
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Phase 10b step 3 — single source of truth for the host's binding
 * to `PluginSandboxService` in the `:plugin` subprocess.
 *
 * Responsibilities:
 *
 * - Bind the sandbox service on first load via
 *   `bindService(intent, this, BIND_AUTO_CREATE)`.
 * - Hold the `IPluginSandbox` reference for callers.
 * - Track an active-plugin reference count. When it goes 0→1, bind;
 *   when it drops 1→0, schedule a 30 s `unbindService` so the OS can
 *   reap `:plugin` if no plugins are loaded. A subsequent acquire
 *   inside the window cancels the unbind.
 * - On `onServiceDisconnected` (sandbox crash) fire
 *   [onSandboxDeath] so [PluginRegistry] can notify every active
 *   token's `onPluginCrashed` handler.
 *
 * The bind/unbind dance is mutex-serialized so concurrent
 * `acquire()`/`release()` from multiple plugin loads can't
 * interleave a stray unbind between a 0→1 and the next operation.
 *
 * See `docs/plugin-host-multi-process.md` "Lifecycle / Connection
 * teardown".
 */
/**
 * Construct exactly one of these per `Application`. Hilt-style
 * `@Singleton` / `@Inject` aren't wired in this module yet
 * (Phase 0/1 plugin-host wiring predates Hilt here), so the host
 * `Application` (or the app-level DI graph that depends on this
 * module) is responsible for instance-singleton enforcement.
 */
class PluginSandboxConnection(
    private val appContext: Context,
) {
    private val mutex = Mutex()
    private val refCount = AtomicInteger(0)
    private var sandbox: IPluginSandbox? = null

    /**
     * Set externally by [PluginRegistry] (or whoever owns
     * lifecycle); fired on `ServiceConnection.onServiceDisconnected`
     * so the registry can mark every active token as `process_dead`
     * and fire `onPluginCrashed("process_died")`.
     */
    @Volatile
    var onSandboxDeath: () -> Unit = {}

    private val idleUnbindHandler = Handler(Looper.getMainLooper())
    private var idleUnbindRunnable: Runnable? = null

    private var connectInflight: CompletableDeferred<IPluginSandbox>? = null

    /**
     * Acquire a reference to the bound sandbox. Binds if necessary
     * and suspends until [onServiceConnected] fires. The caller is
     * responsible for matching every successful [acquire] with a
     * later [release] when the plugin is unloaded.
     *
     * Throws [SandboxBindFailedException] if `bindService` returns
     * false (manifest misconfig, missing package, etc.) — that's a
     * programmer error, not a runtime condition.
     */
    suspend fun acquire(): IPluginSandbox = mutex.withLock {
        val now = refCount.incrementAndGet()
        // Cancel any pending idle unbind — we have a live caller again.
        cancelIdleUnbind()
        val existing = sandbox
        if (existing != null) {
            return@withLock existing
        }
        // Need to bind. If a previous coroutine is already binding,
        // wait on the in-flight deferred.
        val inflight = connectInflight
        if (inflight != null) {
            return@withLock inflight.await().also { refCount.set(now) }
        }
        val deferred = CompletableDeferred<IPluginSandbox>()
        connectInflight = deferred
        val intent = Intent().apply {
            setClassName(
                appContext.packageName,
                "app.syncler.feature.pluginsandbox.PluginSandboxService",
            )
        }
        val ok = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!ok) {
            connectInflight = null
            refCount.decrementAndGet()
            appContext.unbindService(serviceConnection)
            throw SandboxBindFailedException(
                "bindService returned false — :plugin process / manifest misconfigured",
            )
        }
        deferred.await()
    }

    /**
     * Release one reference. When the count hits zero schedules an
     * idle-unbind on the main thread 30 s in the future. A
     * subsequent [acquire] within the window cancels the unbind via
     * [cancelIdleUnbind].
     */
    suspend fun release() = mutex.withLock {
        val now = refCount.decrementAndGet()
        if (now < 0) {
            Timber.tag(TAG).w("release() pushed refCount below zero — caller bug; clamping")
            refCount.set(0)
        }
        if (now <= 0) {
            scheduleIdleUnbind()
        }
    }

    /** Snapshot the current ref count. Diagnostic. */
    fun activeReferenceCount(): Int = refCount.get()

    private fun scheduleIdleUnbind() {
        cancelIdleUnbind()
        val runnable = Runnable {
            // Re-check on the main thread.
            if (refCount.get() <= 0 && sandbox != null) {
                Timber.tag(TAG).i("idle window elapsed — unbinding :plugin")
                runCatching { appContext.unbindService(serviceConnection) }
                    .onFailure { Timber.tag(TAG).w(it, "unbindService failed (already unbound?)") }
                sandbox = null
            }
            idleUnbindRunnable = null
        }
        idleUnbindRunnable = runnable
        idleUnbindHandler.postDelayed(runnable, IDLE_UNBIND_MILLIS)
    }

    private fun cancelIdleUnbind() {
        idleUnbindRunnable?.let {
            idleUnbindHandler.removeCallbacks(it)
            idleUnbindRunnable = null
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = IPluginSandbox.Stub.asInterface(service)
            sandbox = binder
            connectInflight?.complete(binder)
            connectInflight = null
            Timber.tag(TAG).i("sandbox connected (token count=%d)", refCount.get())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.tag(TAG).w("sandbox disconnected — firing onSandboxDeath")
            sandbox = null
            connectInflight?.completeExceptionally(
                SandboxBindFailedException("sandbox disconnected during bind"),
            )
            connectInflight = null
            runCatching { onSandboxDeath() }
                .onFailure { Timber.tag(TAG).w(it, "onSandboxDeath callback threw") }
        }
    }

    companion object {
        private const val TAG = "PluginSandboxConn"

        /**
         * Idle window before unbinding the sandbox. Phase 10a v3:
         * 30 s. Tuned to be long enough that a "user navigates away
         * and right back" doesn't pay the cold-bind cost twice, but
         * short enough that an actually-idle sandbox releases the
         * process to the OS reasonably quickly.
         */
        const val IDLE_UNBIND_MILLIS = 30_000L
    }
}

class SandboxBindFailedException(message: String) : IllegalStateException(message)
