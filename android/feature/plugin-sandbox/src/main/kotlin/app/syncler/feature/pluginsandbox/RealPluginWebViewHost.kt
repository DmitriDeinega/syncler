package app.syncler.feature.pluginsandbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import app.syncler.core.pluginaidl.PluginLoadParcel
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/**
 * Real `PluginWebViewHost` — runs in the `:plugin` subprocess
 * with a per-token Android `WebView`. Phase 10b step 5.
 *
 * Constructed on demand by [RealPluginWebViewHostFactory] which
 * the `PluginSandboxService` wires in for production builds.
 * Unit tests use the recording `FakePluginWebViewHost` in the
 * sandbox test source set instead.
 *
 * Threading: WebView is single-threaded — every API call
 * happens on the main looper. AIDL Binder threads land in the
 * coordinator's serial channel, the coordinator dispatches to
 * this class, and this class marshals to the main thread via
 * [mainHandler]. The exception is [JavascriptInterface] methods
 * which Android invokes on a private JS-bridge thread; we hop
 * back onto the main thread via [HostSignals.bridgeCall] →
 * [SandboxRouter] only after the bytes leave the WebView.
 */
class RealPluginWebViewHost(
    private val appContext: Context,
) : PluginWebViewHost {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var hostSignalsRef: HostSignals? = null

    override fun startLoad(parcel: PluginLoadParcel, hostSignals: HostSignals) {
        // Defense-in-depth (Phase 10a §10.x): re-verify the bundle
        // hash sandbox-side. The host already checked, but a TOCTOU
        // between host stage and sandbox read would slip through
        // without this.
        val bundleFile = File(parcel.bundleFilePath)
        if (!bundleFile.exists()) {
            throw IllegalStateException(LoadFailureCodes.PARCEL_MALFORMED)
        }
        val bundleBytes = bundleFile.readBytes()
        val actualHashHex = sha256Hex(bundleBytes)
        if (!actualHashHex.equals(parcel.bundleHashHex, ignoreCase = true)) {
            Timber.tag(TAG).e(
                "bundle hash mismatch token=%d expected=%s actual=%s",
                parcel.sandboxToken, parcel.bundleHashHex, actualHashHex,
            )
            throw IllegalStateException(LoadFailureCodes.BUNDLE_HASH_MISMATCH)
        }
        if (parcel.renderer != "script") {
            // Step 5 ships the script renderer only. Template plugins
            // are rendered host-side and don't need the sandbox.
            throw IllegalStateException(LoadFailureCodes.UNSUPPORTED_RENDERER)
        }

        hostSignalsRef = hostSignals
        val htmlShell = PluginHtmlShell.render(bundleBytes.toString(Charsets.UTF_8))

        runOnMain {
            val view = WebView(appContext)
            harden(view.settings)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(view.settings, true)
            }
            view.webViewClient = ReadinessWebViewClient()
            view.addJavascriptInterface(
                JsBridge(parcel.sandboxToken),
                PluginHtmlShell.NATIVE_BRIDGE_NAME,
            )
            view.loadDataWithBaseURL(
                PluginHtmlShell.INITIAL_URL,
                htmlShell,
                "text/html",
                "utf-8",
                null,
            )
            webView = view
        }
    }

    /**
     * Phase 10b step 5d: bridge WebView page-load lifecycle into
     * the coordinator's `reportReady` / `reportError` signals.
     * `onPageFinished` is the implicit ready marker — at that
     * point `loadDataWithBaseURL` has parsed the HTML, the SDK
     * shell + bundle script tags have both executed, and
     * subsequent `dispatchHook` calls can rely on
     * `__syncler_internal_dispatch` being installed.
     *
     * Fires once per load via the [reported] guard. A second
     * `onPageFinished` (e.g., from in-bundle SPA navigation —
     * blocked by [shouldOverrideUrlLoading] anyway) would be a
     * no-op rather than a duplicate `onPluginReady`.
     */
    private inner class ReadinessWebViewClient : WebViewClient() {
        @Volatile
        private var reported: Boolean = false

        override fun onPageFinished(view: WebView, url: String?) {
            if (reported) return
            reported = true
            hostSignalsRef?.reportReady()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            // Only surface main-frame errors as plugin failures —
            // subresource 404s are routine and the bundle should
            // handle them. URL match is the cheapest gate.
            if (request?.isForMainFrame != true) return
            val code = error?.errorCode?.toString() ?: "webview_error"
            val message = error?.description?.toString() ?: ""
            hostSignalsRef?.reportError(code, message)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest?,
        ): Boolean {
            // Plugin pages are not allowed to navigate. Block
            // everything except the initial `plugin.local/`.
            return request?.url?.toString() != PluginHtmlShell.INITIAL_URL
        }
    }

    override fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        val script = buildString {
            append("window.__syncler_internal_dispatch(")
            append(quoteJsString(hook))
            append(",[")
            // payloadJson is already a JSON-encoded value — we pass
            // it as a single positional arg. The sdk-side dispatcher
            // accepts an args array; wrap accordingly.
            append(payloadJson)
            append("],")
            append(quoteJsString(callbackId))
            append(")")
        }
        evalJs(script)
    }

    override fun deliverBridgeResult(callbackId: String, resultJson: String) {
        val script = "window.__syncler_internal_callback(" +
            quoteJsString(callbackId) + "," + resultJson + ")"
        evalJs(script)
    }

    override fun destroy() {
        hostSignalsRef = null
        runOnMain {
            runCatching {
                webView?.removeJavascriptInterface(PluginHtmlShell.NATIVE_BRIDGE_NAME)
                webView?.stopLoading()
                webView?.destroy()
            }.onFailure { Timber.tag(TAG).w(it, "webView destroy threw") }
            webView = null
        }
    }

    /** Hardening — matches `PluginLoader.harden` byte-for-byte. */
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

    private fun evalJs(script: String) {
        runOnMain {
            val view = webView ?: return@runOnMain
            runCatching { view.evaluateJavascript(script, null) }
                .onFailure { Timber.tag(TAG).w(it, "evaluateJavascript threw") }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * JS-bridge object the plugin's wrapper calls via
     * `window.__syncler_native__.call(method, argsJson, callbackId)`.
     * Android invokes [call] on a private bridge thread — we
     * forward to [HostSignals.bridgeCall] which routes back to
     * the host via the AIDL callback.
     */
    private inner class JsBridge(private val sandboxToken: Int) {
        @JavascriptInterface
        fun call(method: String?, argsJson: String?, callbackId: String?) {
            val signals = hostSignalsRef
            if (signals == null) {
                Timber.tag(TAG).w(
                    "JS bridge call after destroy (token=%d method=%s) — dropping",
                    sandboxToken, method,
                )
                return
            }
            signals.bridgeCall(
                method = method.orEmpty(),
                argsJson = argsJson.orEmpty(),
                callbackId = callbackId.orEmpty(),
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun quoteJsString(s: String): String {
        val escaped = buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    companion object {
        private const val TAG = "PluginWebViewHost"
    }
}

/**
 * Production factory wired into `PluginSandboxService` —
 * produces one [RealPluginWebViewHost] per `loadPlugin` call,
 * each holding its own WebView in the `:plugin` process.
 */
class RealPluginWebViewHostFactory(
    private val appContext: Context,
) : PluginWebViewHostFactory {
    override fun create(parcel: PluginLoadParcel): PluginWebViewHost =
        RealPluginWebViewHost(appContext)
}
