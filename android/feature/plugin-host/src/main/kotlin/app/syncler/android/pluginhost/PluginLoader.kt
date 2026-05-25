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
                    auditLogger.denied(manifest.id, "bundle_hash_mismatch", actualHash)
                    throw SecurityException("plugin bundle hash mismatch")
                }

                val stored = bundleStore.write(manifest.id, bundleBytes)
                val grantedCapabilities = permissionReader(manifest.id).intersect(manifest.declaredCapabilities.toSet())
                instanceFactory.create(manifest, grantedCapabilities, stored.absolutePath, bundleBytes)
                    .also(PluginRegistry::put)
            }.onFailure {
                auditLogger.denied(null, "plugin_load_failed", it.message)
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

        fun android(context: Context, scope: CoroutineScope): PluginLoader {
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
            val sandboxRouter = SandboxRouter(sandboxConnection, bridgeDispatcher)
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
                    cameraBridge = CameraBridge(),
                    galleryBridge = GalleryBridge(),
                    fileBridge = FileBridge(),
                    locationBridge = LocationBridge(appContext),
                    messageBridge = MessageBridge(auditLogger),
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
) : PluginInstanceFactory {

    override suspend fun create(
        manifest: PluginManifest,
        grantedCapabilities: Set<String>,
        bundleFilePath: String,
        bundleBytes: ByteArray,
    ): PluginInstance {
        val sandboxToken = sandboxRouter.allocateToken()
        val instance = PluginInstance(
            manifest = manifest,
            grantedCapabilities = grantedCapabilities,
            bundleFilePath = bundleFilePath,
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
            auditLogger = auditLogger,
        )
        bridgeDispatcher.registerBridge(sandboxToken, bridge)
        bridgeDispatcher.registerLifecycleListener(
            sandboxToken,
            RegistryUnloadListener(manifest.id),
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
            diagnosticManifestJson = "",
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
     */
    private inner class RegistryUnloadListener(
        private val pluginId: String,
    ) : SandboxBridgeDispatcher.LifecycleListener {
        override fun onPluginReady() = Unit
        override fun onWebViewError(code: String, message: String) {
            auditLogger.denied(pluginId, "webview_error_$code", message)
        }
        override fun onPluginCrashed(reason: String) {
            auditLogger.denied(pluginId, "plugin_crashed", reason)
            PluginRegistry.unload(pluginId)
        }
        override fun onPluginUnloaded() {
            PluginRegistry.unload(pluginId)
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}
