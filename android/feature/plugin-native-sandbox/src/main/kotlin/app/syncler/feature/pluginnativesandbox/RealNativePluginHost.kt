package app.syncler.feature.pluginnativesandbox

import android.os.ParcelFileDescriptor
import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.PluginLoadParcel
import app.syncler.plugin.runtime.ActionEvent
import app.syncler.plugin.runtime.InboxEvent
import app.syncler.plugin.runtime.NATIVE_SDK_ABI
import app.syncler.plugin.runtime.PluginContext
import app.syncler.plugin.runtime.SynclerPlugin
import dalvik.system.InMemoryDexClassLoader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Phase 11 native plugin host. One instance lives inside the
 * isolated `:nativePlugin` process per loaded plugin token (the
 * `bindIsolatedService(instanceName=token)` call gives each token
 * its own process — see docs/plugin-host-native-kotlin.md).
 *
 * Responsibilities:
 *  - Read DEX bytes from the host-provided ParcelFileDescriptor.
 *  - Verify SHA-256 against `parcel.bundleHashHex`.
 *  - Enforce 4 MB cap + forbidden-prefix scan (defense-in-depth;
 *    the host already enforced these, but the sandbox repeats
 *    them so a buggy/compromised host can't smuggle a payload).
 *  - Reject ABI mismatch BEFORE loading the DEX (so a plugin
 *    built against a future ABI can never have its static
 *    initializers run in the current sandbox).
 *  - Construct an `InMemoryDexClassLoader` with the SDK runtime
 *    classloader as parent.
 *  - Resolve [PluginLoadParcel.entryClass], instantiate via
 *    no-arg constructor, cast to [SynclerPlugin].
 *  - Run `onInit` under a 10s timeout on the bounded dispatcher.
 *  - On any failure: call [IPluginHostCallback.onWebViewError]
 *    FIRST (so the host learns the structured code), THEN cancel
 *    the init child job. Order matters per triad 135 — reversing
 *    it loses the error code.
 */
internal class RealNativePluginHost(
    private val parcel: PluginLoadParcel,
    private val bundleFd: ParcelFileDescriptor,
    private val hostCallback: IPluginHostCallback,
) : AutoCloseable {

    private val dispatcher = BoundedPluginDispatcher.create(parcel.sandboxToken.toString())
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.dispatcher)

    @Volatile private var plugin: SynclerPlugin? = null
    @Volatile private var pluginContext: PluginContext? = null
    @Volatile private var initJob: Job? = null

    /**
     * Synchronous half of load — runs on the Binder thread that
     * received `loadPlugin`. Throws [IllegalStateException] with
     * a [NativeLoadFailureCodes] message for any validation
     * failure the host should see as a structured error.
     *
     * Async `onInit` runs after this returns; success / failure
     * propagates to the host via [IPluginHostCallback].
     */
    fun startLoad() {
        // Triad 136 fix (codex): close the sandbox-side FD on ALL
        // exit paths, not just the success path. ABI mismatch
        // throws BEFORE the read; readDex throws DEX_TOO_LARGE
        // mid-read; either way the FD would have leaked. try/finally
        // around the entire ownership window guarantees release.
        val dexBytes = try {
            if (parcel.nativeSdkAbi != NATIVE_SDK_ABI) {
                throw IllegalStateException(NativeLoadFailureCodes.UNSUPPORTED_SDK_ABI)
            }
            readDex(bundleFd)
        } finally {
            runCatching { bundleFd.close() }
                .onFailure { Timber.tag(TAG).w(it, "bundleFd close failed") }
        }

        if (dexBytes.size > DEX_MAX_BYTES) {
            throw IllegalStateException(NativeLoadFailureCodes.DEX_TOO_LARGE)
        }

        verifyHash(dexBytes, parcel.bundleHashHex)
        scanForbiddenPrefixes(dexBytes)

        val classLoader = try {
            InMemoryDexClassLoader(
                ByteBuffer.wrap(dexBytes),
                /* parent = */ SynclerPlugin::class.java.classLoader,
            )
        } catch (exc: Throwable) {
            Timber.tag(TAG).e(exc, "InMemoryDexClassLoader construction failed")
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_INVALID)
        }

        val entryClass = try {
            classLoader.loadClass(parcel.entryClass)
        } catch (exc: ClassNotFoundException) {
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_NOT_FOUND)
        } catch (exc: Throwable) {
            Timber.tag(TAG).e(exc, "loadClass(${parcel.entryClass}) failed")
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_INVALID)
        }

        if (!SynclerPlugin::class.java.isAssignableFrom(entryClass)) {
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_INVALID)
        }

        val instance = try {
            entryClass.getDeclaredConstructor().newInstance() as SynclerPlugin
        } catch (exc: NoSuchMethodException) {
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_INVALID)
        } catch (exc: Throwable) {
            Timber.tag(TAG).e(exc, "entry class construction failed")
            throw IllegalStateException(NativeLoadFailureCodes.ENTRY_CLASS_INVALID)
        }
        plugin = instance
        val ctx = BridgePluginContext(
            pluginId = parcel.pluginId,
            grantedCapabilities = parcel.declaredCapabilities.toSet(),
            sandboxToken = parcel.sandboxToken,
            hostCallback = hostCallback,
        )
        pluginContext = ctx

        // Asynchronous onInit. The Binder thread returns the token
        // to the host right after this method returns; the host
        // will see onPluginReady / onWebViewError asynchronously
        // via the callback.
        initJob = scope.launch {
            // Triad 136 fix (codex): runCatching INSIDE
            // withTimeoutOrNull would swallow the
            // TimeoutCancellationException as Result.failure and
            // misreport the structured code as onInit_threw
            // instead of init_timeout. Move runCatching OUTSIDE
            // and re-throw CancellationException so the timeout
            // signal propagates as the null result.
            //
            // Also: the previous `initJob?.cancel()` after report
            // was redundant — withTimeoutOrNull already cancelled
            // the inner block. The whole init flow is just THIS
            // child job; on timeout we let it finish reporting and
            // then return. (Gemini 136 NIT.)
            val callResult: Result<Result<Unit>>? = try {
                withTimeoutOrNull(ON_INIT_TIMEOUT_MILLIS) {
                    runCatching { instance.onInit(ctx) }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            }

            when {
                callResult == null -> {
                    reportError(
                        NativeLoadFailureCodes.INIT_TIMEOUT,
                        "onInit exceeded $ON_INIT_TIMEOUT_MILLIS ms",
                    )
                }
                callResult.isFailure -> {
                    val exc = callResult.exceptionOrNull()
                    reportError("onInit_threw", exc?.message ?: "(no message)")
                }
                callResult.getOrNull()?.isFailure == true -> {
                    val exc = callResult.getOrNull()?.exceptionOrNull()
                    reportError("onInit_failed", exc?.message ?: "(no message)")
                }
                else -> {
                    runCatching { hostCallback.onPluginReady(parcel.sandboxToken) }
                        .onFailure { Timber.tag(TAG).w(it, "onPluginReady callback failed") }
                }
            }
        }
    }

    /**
     * Forward a hook delivered by the host into the plugin. The
     * sandbox routes events on the per-token dispatcher; the
     * surrounding service binds an internal `Channel` to serialize
     * concurrent host coroutines so hooks don't reorder.
     */
    fun submitInbox(event: InboxEvent) {
        val p = plugin ?: return
        val ctx = pluginContext ?: return
        scope.launch {
            val outcome = runCatching { p.onInbox(ctx, event) }
            outcome.onFailure { Timber.tag(TAG).w(it, "onInbox threw") }
        }
    }

    fun submitAction(event: ActionEvent) {
        val p = plugin ?: return
        val ctx = pluginContext ?: return
        scope.launch {
            val outcome = runCatching { p.onAction(ctx, event) }
            outcome.onFailure { Timber.tag(TAG).w(it, "onAction threw") }
        }
    }

    /**
     * Service-internal accessor for the host's bridge context.
     * Used by [PluginNativeSandboxService.deliverBridgeResult] to
     * route incoming results to the right pending continuation.
     * Null until [startLoad] succeeds.
     */
    fun attachedBridgeContext(): BridgePluginContext? =
        pluginContext as? BridgePluginContext

    /**
     * Service-internal accessor for the original host callback,
     * so [PluginNativeSandboxService.unloadPlugin] can fire
     * `onPluginUnloaded` even after [close] runs.
     */
    fun lastCallback(): IPluginHostCallback = hostCallback

    override fun close() {
        scope.cancel()
        dispatcher.close()
        plugin = null
        pluginContext = null
    }

    private fun reportError(code: String, message: String) {
        runCatching {
            hostCallback.onWebViewError(parcel.sandboxToken, code, message)
        }.onFailure { Timber.tag(TAG).w(it, "onWebViewError callback failed code=$code") }
    }

    private fun verifyHash(bytes: ByteArray, expectedHex: String) {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actualHex = actual.joinToString("") { "%02x".format(it) }
        if (actualHex != expectedHex.lowercase()) {
            throw IllegalStateException(NativeLoadFailureCodes.BUNDLE_HASH_MISMATCH)
        }
    }

    private fun scanForbiddenPrefixes(dexBytes: ByteArray) {
        val classNames = try {
            DexClassNameReader.classNames(dexBytes)
        } catch (exc: IllegalArgumentException) {
            // Malformed DEX: treat as forbidden — host already
            // accepted it so this is a defense-in-depth catch.
            throw IllegalStateException(NativeLoadFailureCodes.FORBIDDEN_PACKAGE_PREFIX)
        }
        if (firstForbiddenPrefix(classNames) != null) {
            throw IllegalStateException(NativeLoadFailureCodes.FORBIDDEN_PACKAGE_PREFIX)
        }
    }

    private fun readDex(fd: ParcelFileDescriptor): ByteArray {
        // Read with a cap so a host that hands us a giant fd
        // doesn't blow our heap before the DEX_TOO_LARGE check.
        val cap = DEX_MAX_BYTES + 1
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        FileInputStream(fd.fileDescriptor).use { stream ->
            var total = 0
            while (total < cap) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > DEX_MAX_BYTES) {
                    throw IllegalStateException(NativeLoadFailureCodes.DEX_TOO_LARGE)
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "PluginNativeSandbox"
    }
}
