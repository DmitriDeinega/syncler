package app.syncler.android.pluginhost

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import app.syncler.android.pluginhost.capabilities.CameraBridge
import app.syncler.android.pluginhost.capabilities.FileBridge
import app.syncler.android.pluginhost.capabilities.GalleryBridge
import app.syncler.android.pluginhost.capabilities.LocationBridge
import app.syncler.android.pluginhost.capabilities.MessageBridge
import app.syncler.android.pluginhost.capabilities.NetworkBridge
import app.syncler.android.pluginhost.capabilities.NotificationBridge
import app.syncler.android.pluginhost.capabilities.StorageBridge
import app.syncler.android.pluginhost.sandbox.NativePluginSandboxConnection
import app.syncler.android.pluginhost.sandbox.PluginSandboxConnection
import app.syncler.android.pluginhost.sandbox.SandboxBridgeDelivery
import app.syncler.android.pluginhost.sandbox.SandboxBridgeDispatcher
import app.syncler.android.pluginhost.sandbox.SandboxRouter
import app.syncler.core.crypto.toHex
import app.syncler.core.pluginaidl.PluginLoadParcel
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class PluginLoader(
    private val httpClient: OkHttpClient,
    private val manifestAdapter: JsonAdapter<PluginManifest> = defaultMoshi.adapter(PluginManifest::class.java),
    private val rawManifestAdapter: JsonAdapter<Map<String, Any?>> = defaultMoshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    ),
    private val verifier: PluginSignatureVerifier,
    private val permissionReader: (String) -> Set<String>,
    private val bundleStore: PluginBundleStore,
    private val instanceFactory: PluginInstanceFactory,
    private val auditLogger: AuditLogger = AuditLogger(),
) {

    suspend fun load(manifestUrl: String, expectedSenderPublicKey: ByteArray): Result<PluginInstance> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireHttps(manifestUrl)
                val manifestJson = fetchNoCache(manifestUrl)
                val rawManifest = rawManifestAdapter.fromJson(manifestJson)
                    ?: throw IllegalArgumentException("manifest JSON is empty")
                verifier.verify(rawManifest, expectedSenderPublicKey).getOrThrow()

                val manifest = manifestAdapter.fromJson(manifestJson)
                    ?: throw IllegalArgumentException("manifest JSON is empty")
                val bundleUrl = manifest.bundleUrlOrNull(rawManifest)
                    ?: throw IllegalArgumentException("manifest signed_bundle_url is missing")
                if (bundleUrl.startsWith("http://") || bundleUrl.startsWith("https://")) {
                    requireHttps(bundleUrl)
                }
                val resolvedBundleUrl = manifestUrl.toHttpUrl().resolve(bundleUrl)?.toString()
                    ?: throw IllegalArgumentException("signed_bundle_url is invalid")
                val bundleBytes = fetchBytesNoCache(resolvedBundleUrl)
                val actualHash = MessageDigest.getInstance("SHA-256").digest(bundleBytes).toHex()
                if (!actualHash.equals(manifest.bundleHash, ignoreCase = true)) {
                    auditLogger.record(manifest.id, "bundle_hash_mismatch", actualHash)
                    throw SecurityException("plugin bundle hash mismatch")
                }

                val stored = bundleStore.write(manifest.id, bundleBytes)
                val grantedCapabilities = permissionReader(manifest.id).intersect(manifest.declaredCapabilities.toSet())
                instanceFactory.create(manifest, grantedCapabilities, stored.absolutePath, bundleBytes, manifestJson)
                    .also(PluginRegistry::put)
            }.onFailure {
                auditLogger.record(null, "plugin_load_failed", it.message)
            }
        }

    private fun fetchNoCache(url: String): String = fetchBytesNoCache(url).toString(Charsets.UTF_8)

    private fun fetchBytesNoCache(url: String): ByteArray {
        requireHttps(url)
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} fetching $url")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun requireHttps(url: String) {
        // Debug builds accept any HTTP origin so devs can host bundles on their
        // LAN/dev box without TLS. Release builds require HTTPS unconditionally.
        require(url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))) {
            "plugin URLs must use HTTPS (got $url)"
        }
    }

    companion object {
        private val defaultMoshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        private fun buildPluginHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectionSpecs(
                if (BuildConfig.DEBUG) {
                    listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(ConnectionSpec.MODERN_TLS)
                },
            )
            .build()

        fun android(
            context: Context,
            scope: CoroutineScope,
            // V3 #16: opt-in sink for incoming card.patch
            // frames on the live channel. The app composition
            // root passes InboxRepository::applyLivePatch
            // here; tests / minimal builds use NoOp.
            livePatchSink: app.syncler.core.network.LivePatchSink =
                app.syncler.core.network.LivePatchSink.NoOp,
            // Triad 158 bug 2 FIX: take the host's existing
            // Session so the LiveBridge's deviceJwtProvider
            // resolves to the real device JWT instead of the
            // throwing placeholder. Optional with a default
            // of null so headless / minimal builds (and
            // tests) keep working — the placeholder behavior
            // is preserved only when no Session is wired.
            session: app.syncler.core.auth.Session? = null,
        ): PluginLoader {
            val appContext = context.applicationContext
            val auditLogger = AuditLogger(appContext)
            // OkHttp's default connectionSpecs is [MODERN_TLS, COMPATIBLE_TLS]
            // — neither permits cleartext, so http:// URLs fail at the
            // connection layer even when requireHttps() lets them through
            // for debug builds. Mirror NetworkBridge / InboxRepository:
            // release stays HTTPS-only; debug allows LAN HTTP for dev boxes.
            val networkBridge = NetworkBridge(buildPluginHttpClient(), auditLogger)
            val sandboxConnection = PluginSandboxConnection(appContext)
            val bridgeDispatcher = SandboxBridgeDispatcher()
            // Phase 11: native Kotlin plugin connection is API 29+
            // (bindIsolatedService instanceName overload). On older
            // devices the router rejects native_kotlin loads at runtime
            // with `native_only_api_29`.
            val nativeConnection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                NativePluginSandboxConnection(appContext)
            } else {
                null
            }
            val sandboxRouter = SandboxRouter(
                connection = sandboxConnection,
                bridgeDispatcher = bridgeDispatcher,
                nativeConnection = nativeConnection,
            )
            // Phase 12: capability infrastructure. The CapabilityActivityCoordinator
            // requires an Application; if context isn't one, fall back to a
            // null coordinator and the bridges will fail with no_foreground_activity
            // at call time. (Service-style contexts are an edge case in v0.1.)
            // Triad 143 C4 FIX: use the process-singleton so
            // Settings-side revokes invalidate the bridges'
            // cache too. Previously this call site and the
            // Settings VM constructed independent instances
            // with independent caches.
            val capGrantStore = app.syncler.android.pluginhost.capabilities.CapabilityGrantStore.shared(appContext)
            val capHandleStore = app.syncler.android.pluginhost.capabilities.CapabilityHandleStore(appContext)
            val capPrompter = app.syncler.android.pluginhost.capabilities.CapabilityGrantPrompter()
            val capCoordinator = (appContext as? android.app.Application)?.let {
                app.syncler.android.pluginhost.capabilities.CapabilityActivityCoordinator(it).also { c -> c.attach() }
            }
            val capAuditDao = capGrantStore.auditDaoForTest()
            // Phase 12: app-start handle wipe so any orphan
            // staging files from a previous process death don't
            // linger. Idempotent + cheap.
            capHandleStore.wipeAll()

            return PluginLoader(
                httpClient = buildPluginHttpClient(),
                verifier = PluginSignatureVerifier(auditLogger),
                permissionReader = PluginPermissionStore(appContext)::grantedCapabilities,
                bundleStore = AndroidEncryptedBundleStore(appContext),
                instanceFactory = SandboxedPluginInstanceFactory(
                    scope = scope,
                    auditLogger = auditLogger,
                    sandboxRouter = sandboxRouter,
                    bridgeDispatcher = bridgeDispatcher,
                    networkBridge = networkBridge,
                    storageBridge = StorageBridge(appContext, auditLogger),
                    notificationBridge = NotificationBridge(appContext),
                    cameraBridge = CameraBridge(
                        context = appContext,
                        coordinator = capCoordinator,
                        grantStore = capGrantStore,
                        handleStore = capHandleStore,
                        auditLogger = capAuditDao,
                    ),
                    galleryBridge = GalleryBridge(
                        context = appContext,
                        coordinator = capCoordinator,
                        grantStore = capGrantStore,
                        handleStore = capHandleStore,
                        auditLogger = capAuditDao,
                    ),
                    fileBridge = FileBridge(
                        context = appContext,
                        coordinator = capCoordinator,
                        grantStore = capGrantStore,
                        handleStore = capHandleStore,
                        auditLogger = capAuditDao,
                    ),
                    locationBridge = LocationBridge(
                        context = appContext,
                        coordinator = capCoordinator,
                        grantStore = capGrantStore,
                        prompter = capPrompter,
                        auditLogger = capAuditDao,
                    ),
                    messageBridge = MessageBridge(auditLogger),
                    capabilityHandleStore = capHandleStore,
                    liveBridge = app.syncler.android.pluginhost.capabilities.LiveBridge(
                        clientFactory = object : app.syncler.android.pluginhost.capabilities.LiveChannelClientFactory {
                            override fun build(
                                plugin: app.syncler.android.pluginhost.PluginInstance,
                            ): app.syncler.android.pluginhost.live.LiveChannelClient {
                                // Triad 144 codex FIX: route by the
                                // server row UUID, NOT manifest.id.
                                // PluginInstance carries the UUID
                                // separately from V3 #14 onward.
                                require(plugin.pluginRowId.isNotEmpty()) {
                                    "live channel requires pluginRowId on PluginInstance"
                                }
                                // Triad 158 bug 2 FIX: real device-JWT
                                // provider now resolves through the
                                // host's Session. When no session is
                                // wired (test / headless build), keep
                                // the loud placeholder so the wiring
                                // gap can't slip past in production.
                                // Triad 159 codex FIX: use the same
                                // SERVER_BASE_URL the REST + SSE
                                // clients use — the previous hard-
                                // coded "https://syncler.local"
                                // dead-ended any real WS connect.
                                return app.syncler.android.pluginhost.live.LiveChannelClient(
                                    baseUrl = app.syncler.core.network.BuildConfig.SERVER_BASE_URL,
                                    pluginRowId = plugin.pluginRowId,
                                    deviceJwtProvider = {
                                        if (session == null) {
                                            auditLogger.record(
                                                plugin.manifest.id,
                                                "live_no_session",
                                                "no Session wired into PluginLoader.android(); live disabled",
                                            )
                                            timber.log.Timber.tag("LiveBridge").e(
                                                "deviceJwtProvider: no Session wired — live channel unusable",
                                            )
                                            throw app.syncler.android.pluginhost.live.LiveChannelException(
                                                "no_session",
                                                "session not wired into PluginLoader.android()",
                                            )
                                        }
                                        session.currentToken()
                                            ?: throw app.syncler.android.pluginhost.live.LiveChannelException(
                                                "no_session",
                                                "no device JWT available (locked / signed out)",
                                            )
                                    },
                                    httpClient = buildPluginHttpClient(),
                                    auditLogger = auditLogger,
                                )
                            }
                        },
                        auditLogger = auditLogger,
                        livePatchSink = livePatchSink,
                    ),
                ),
                auditLogger = auditLogger,
            )
        }
    }
}

interface PluginBundleStore {
    fun write(pluginId: String, bundleBytes: ByteArray): File
}

class AndroidEncryptedBundleStore(context: Context) : PluginBundleStore {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    override fun write(pluginId: String, bundleBytes: ByteArray): File {
        val pluginDir = File(appContext.noBackupFilesDir, "pluginhost/bundles/${sanitize(pluginId)}").apply { mkdirs() }
        val encryptedFile = File(pluginDir, "plugin.bundle.js.enc")
        if (encryptedFile.exists()) encryptedFile.delete()
        EncryptedFile.Builder(
            appContext,
            encryptedFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build().openFileOutput().use { output ->
            output.write(bundleBytes)
        }
        return encryptedFile
    }

    private fun sanitize(pluginId: String): String =
        pluginId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

interface PluginInstanceFactory {
    suspend fun create(
        manifest: PluginManifest,
        grantedCapabilities: Set<String>,
        bundleFilePath: String,
        bundleBytes: ByteArray,
        manifestJson: String,
        /**
         * V3 #14 (triad 144 codex FIX): server-assigned row
         * UUID. Required for the live-channel WS endpoint;
         * empty string when the caller doesn't have it (live
         * connect will refuse).
         */
        pluginRowId: String = "",
    ): PluginInstance
}

/**
 * Phase 10b step 6 production factory: stages the bundle, mints a
 * `sandboxToken`, registers the per-token [PluginBridge] +
 * lifecycle listener with [SandboxBridgeDispatcher], then asks
 * [SandboxRouter] to fire the AIDL `loadPlugin`. The returned
 * [PluginInstance] holds the resulting [SandboxHandle] for
 * dispatch / teardown — the actual JS lives in `:plugin`.
 */
class SandboxedPluginInstanceFactory(
    private val scope: CoroutineScope,
    private val auditLogger: AuditLogger,
    private val sandboxRouter: SandboxRouter,
    private val bridgeDispatcher: SandboxBridgeDispatcher,
    private val networkBridge: NetworkBridge,
    private val storageBridge: StorageBridge,
    private val notificationBridge: NotificationBridge,
    private val cameraBridge: CameraBridge,
    private val galleryBridge: GalleryBridge,
    private val fileBridge: FileBridge,
    private val locationBridge: LocationBridge,
    private val messageBridge: MessageBridge,
    private val capabilityHandleStore: app.syncler.android.pluginhost.capabilities.CapabilityHandleStore? = null,
    /** V3 #14/#15 — process-singleton `platform.live.*` dispatcher. */
    private val liveBridge: app.syncler.android.pluginhost.capabilities.LiveBridge,
) : PluginInstanceFactory {

    override suspend fun create(
        manifest: PluginManifest,
        grantedCapabilities: Set<String>,
        bundleFilePath: String,
        bundleBytes: ByteArray,
        manifestJson: String,
        pluginRowId: String,
    ): PluginInstance {
        val sandboxToken = sandboxRouter.allocateToken()
        val instance = PluginInstance(
            manifest = manifest,
            grantedCapabilities = grantedCapabilities,
            bundleFilePath = bundleFilePath,
            pluginRowId = pluginRowId,
        )
        val delivery = SandboxBridgeDelivery(
            sandboxToken = sandboxToken,
            routerProvider = { sandboxRouter },
            scope = scope,
        )
        val bridge = PluginBridge(
            plugin = instance,
            delivery = delivery,
            scope = scope,
            networkBridge = networkBridge,
            storageBridge = storageBridge,
            notificationBridge = notificationBridge,
            cameraBridge = cameraBridge,
            galleryBridge = galleryBridge,
            fileBridge = fileBridge,
            locationBridge = locationBridge,
            messageBridge = messageBridge,
            capabilityHandleStore = capabilityHandleStore,
            liveBridge = liveBridge,
            auditLogger = auditLogger,
        )
        bridgeDispatcher.registerBridge(sandboxToken, bridge)
        bridgeDispatcher.registerLifecycleListener(
            sandboxToken,
            RegistryUnloadListener(manifest.id, sandboxToken),
        )

        val bundleHashHex = MessageDigest.getInstance("SHA-256")
            .digest(bundleBytes)
            .toHex()
        val parcel = PluginLoadParcel(
            sandboxToken = sandboxToken,
            pluginId = manifest.id,
            pluginIdentifier = manifest.id,
            version = manifest.version,
            renderer = "script",
            bundleFilePath = bundleFilePath,
            bundleHashHex = bundleHashHex,
            declaredCapabilities = manifest.declaredCapabilities,
            declaredEndpoints = manifest.declaredEndpoints,
            dismissBehavior = manifest.dismissBehavior.orEmpty(),
            timeoutMillis = DEFAULT_TIMEOUT_MS,
            // 64KB cap mirrors PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP.
            // The wire layer truncates too; this is belt-and-suspenders so we
            // don't ship a giant payload that won't fit in the Binder
            // transaction.
            diagnosticManifestJson = manifestJson.take(PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP),
        )

        val handle = try {
            sandboxRouter.loadPlugin(parcel)
        } catch (exc: Throwable) {
            // Sandbox refused the load. Tear down the bridge
            // registration so the next attempt with the same
            // pluginId gets a clean slot.
            bridgeDispatcher.unregisterBridge(sandboxToken)
            throw exc
        }
        // Reflect the handle into the same instance the bridge
        // was constructed with — we deliberately keep one
        // [PluginInstance] alive for the load lifecycle so
        // bridge.plugin and the registry entry are identical.
        instance.sandboxHandle = handle
        instance.bridge = bridge
        return instance
    }

    /**
     * Lifecycle listener that removes the plugin from
     * [PluginRegistry] when the sandbox reports crashed or
     * unloaded. Without this the host registry would hold a
     * dangling instance whose sandbox-side coordinator has
     * already gone away.
     *
     * Triad 121 generation fence: routes through
     * [PluginRegistry.handleSandboxTerminated] keyed by
     * [sandboxToken], not [PluginRegistry.unload] keyed only by
     * `pluginId`. On reload of the same plugin, a late callback
     * from the predecessor token finds the registry's current
     * entry pointing at the new token and skips eviction.
     *
     * Also skips the AIDL `unloadPlugin` round-trip — the
     * sandbox is already torn down by the time we get here, so
     * re-acquiring the connection just to send a redundant
     * unload would waste a bind + ref-count.
     */
    private inner class RegistryUnloadListener(
        private val pluginId: String,
        private val sandboxToken: Int,
    ) : SandboxBridgeDispatcher.LifecycleListener {
        override fun onPluginReady() = Unit
        override fun onWebViewError(code: String, message: String) {
            auditLogger.record(pluginId, "webview_error_$code", message)
        }
        override fun onPluginCrashed(reason: String) {
            auditLogger.record(pluginId, "plugin_crashed", reason)
            wipeCapabilityHandles(sandboxToken)
            liveBridge.closeForPlugin(pluginId)
            PluginRegistry.handleSandboxTerminated(pluginId, sandboxToken)
        }
        override fun onPluginUnloaded() {
            // Triad 139 codex #6 fix: wipe the token's staging
            // dir so handles from this plugin don't outlive the
            // plugin process.
            wipeCapabilityHandles(sandboxToken)
            // Triad 144 codex FIX: close the live WS client +
            // kill the reconnect loop on unload. Without this,
            // a plugin teardown leaves the WS alive in
            // perpetuity, retrying forever.
            liveBridge.closeForPlugin(pluginId)
            PluginRegistry.handleSandboxTerminated(pluginId, sandboxToken)
        }
    }

    private fun wipeCapabilityHandles(sandboxToken: Int) {
        val store = capabilityHandleStore ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { store.wipeForToken(sandboxToken) }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}
