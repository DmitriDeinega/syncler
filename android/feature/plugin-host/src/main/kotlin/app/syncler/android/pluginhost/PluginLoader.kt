package app.syncler.android.pluginhost

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import androidx.webkit.WebViewFeature
import app.syncler.android.pluginhost.capabilities.CameraBridge
import app.syncler.android.pluginhost.capabilities.FileBridge
import app.syncler.android.pluginhost.capabilities.GalleryBridge
import app.syncler.android.pluginhost.capabilities.LocationBridge
import app.syncler.android.pluginhost.capabilities.MessageBridge
import app.syncler.android.pluginhost.capabilities.NetworkBridge
import app.syncler.android.pluginhost.capabilities.NotificationBridge
import app.syncler.android.pluginhost.capabilities.StorageBridge
import app.syncler.core.crypto.toHex
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
                val manifestJson = fetchNoCache(manifestUrl)
                val rawManifest = rawManifestAdapter.fromJson(manifestJson)
                    ?: throw IllegalArgumentException("manifest JSON is empty")
                verifier.verify(rawManifest, expectedSenderPublicKey).getOrThrow()

                val manifest = manifestAdapter.fromJson(manifestJson)
                    ?: throw IllegalArgumentException("manifest JSON is empty")
                val bundleUrl = manifest.bundleUrlOrNull(rawManifest)
                    ?: throw IllegalArgumentException("manifest signed_bundle_url is missing")
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
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} fetching $url")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        private val defaultMoshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        fun android(context: Context, scope: CoroutineScope): PluginLoader {
            val auditLogger = AuditLogger(context)
            val networkBridge = NetworkBridge(OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(), auditLogger)
            return PluginLoader(
                httpClient = OkHttpClient.Builder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(),
                verifier = PluginSignatureVerifier(auditLogger),
                permissionReader = PluginPermissionStore(context)::grantedCapabilities,
                bundleStore = AndroidEncryptedBundleStore(context),
                instanceFactory = AndroidPluginInstanceFactory(
                    context = context,
                    scope = scope,
                    auditLogger = auditLogger,
                    networkBridge = networkBridge,
                    storageBridge = StorageBridge(context, auditLogger),
                    notificationBridge = NotificationBridge(context),
                    cameraBridge = CameraBridge(),
                    galleryBridge = GalleryBridge(),
                    fileBridge = FileBridge(),
                    locationBridge = LocationBridge(context),
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

class AndroidPluginInstanceFactory(
    private val context: Context,
    private val scope: CoroutineScope,
    private val auditLogger: AuditLogger,
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
    ): PluginInstance = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext)
        val instance = PluginInstance(manifest, grantedCapabilities, bundleFilePath, webView)
        val bridge = PluginBridge(
            plugin = instance,
            webViewProvider = { webView },
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
        harden(webView.settings)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }
        webView.webViewClient = PluginWebViewClient(manifest.id, auditLogger) { pluginId ->
            PluginRegistry.unload(pluginId)
        }
        webView.addJavascriptInterface(bridge, PluginBridge.NATIVE_BRIDGE_NAME)
        val htmlShell = PluginHtmlShell.render(bundleBytes.toString(Charsets.UTF_8))
        webView.loadDataWithBaseURL(PluginWebViewClient.INITIAL_URL, htmlShell, "text/html", "utf-8", null)
        instance.bridge = bridge
        instance
    }

    private fun harden(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
    }
}

private object PluginHtmlShell {
    fun render(bundleJs: String): String {
        val escapedBundle = bundleJs.replace("</script", "<\\/script", ignoreCase = true)
        return """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body>
            <script>
            (() => {
              const callbacks = new Map();
              let nextCallbackId = 1;
              window.__syncler_internal_callback = (callbackId, result) => {
                const callback = callbacks.get(callbackId);
                if (!callback) return;
                callbacks.delete(callbackId);
                if (result && result.error) callback.reject(result); else callback.resolve(result);
              };
              const nativeCall = (method, args) => new Promise((resolve, reject) => {
                const callbackId = String(nextCallbackId++);
                callbacks.set(callbackId, { resolve, reject });
                window.__syncler_native__.call(method, JSON.stringify(args || {}), callbackId);
              });
              const asResponse = async (payload) => new Response(payload.body || "", {
                status: payload.status || 200,
                headers: payload.headers || {}
              });
              window.platform = {
                __version__: "1.0.0",
                showNotification: (opts) => nativeCall("platform.showNotification", opts),
                storage: {
                  get: (key, opts) => nativeCall("platform.storage.get", { key, opts }).then((r) => r.value ?? null),
                  set: (key, value, opts) => nativeCall("platform.storage.set", { key, value, opts }),
                  delete: (key, opts) => nativeCall("platform.storage.delete", { key, opts })
                },
                network: {
                  fetch: (url, init) => nativeCall("platform.network.fetch", { url, init }).then(asResponse)
                },
                camera: { capture: (opts) => nativeCall("platform.camera.capture", opts) },
                gallery: { pick: (opts) => nativeCall("platform.gallery.pick", opts) },
                file: { pick: (opts) => nativeCall("platform.file.pick", opts) },
                location: { current: (opts) => nativeCall("platform.location.current", opts) },
                message: {
                  respond: (actionId, payload) => nativeCall("platform.message.respond", { actionId, payload }),
                  dismissBehavior: (behavior) => nativeCall("platform.message.dismissBehavior", { behavior })
                }
              };
            })();
            </script>
            <script>$escapedBundle</script>
            <script>
            (() => {
              const sdkDispatch = window.__syncler_internal_dispatch;
              window.__syncler_internal_dispatch = (hook, args, callbackId) => {
                const promise = sdkDispatch ? Promise.resolve(sdkDispatch(hook, args || [])) : Promise.reject(new Error("plugin dispatcher unavailable"));
                if (callbackId) {
                  promise.then(
                    (value) => window.__syncler_internal_callback(callbackId, { value }),
                    (error) => window.__syncler_internal_callback(callbackId, { error: "plugin_dispatch_failed", message: String(error && error.message || error) })
                  );
                }
                return promise;
              };
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
