package app.syncler.feature.pluginsandbox

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import app.syncler.core.pluginaidl.PluginLoadParcel
import java.io.ByteArrayInputStream
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
     * Phase 10b step 5d/5e: bridge WebView lifecycle into the
     * coordinator and mirror the legacy `PluginWebViewClient`
     * isolation guarantees byte-for-byte.
     *
     * - [onPageFinished] is the implicit ready marker. By the
     *   time it fires, `loadDataWithBaseURL` has parsed the HTML
     *   and executed the SDK shell + bundle inline `<script>`
     *   tags, so `__syncler_internal_dispatch` is installed and
     *   the next `dispatchHook` is safe to queue.
     * - [shouldInterceptRequest] blocks every WebView-originated
     *   network request other than the synthetic initial self-
     *   load at [PluginHtmlShell.INITIAL_URL]. Without this,
     *   plugin JS can issue arbitrary `<img>` / `<script>` /
     *   `fetch()` loads that bypass the audited
     *   `platform.network.fetch` capability. Codex triad 119
     *   blocker.
     * - [onPageStarted] short-circuits any non-initial main-frame
     *   navigation attempt before the WebView starts the load.
     *   Belt-and-suspenders with [shouldOverrideUrlLoading] which
     *   intercepts user-driven nav.
     * - [onRenderProcessGone] surfaces a WebView renderer crash
     *   as a per-token `reportError` so the host gets the
     *   structured `onWebViewError` callback instead of waiting
     *   for service death.
     */
    private inner class ReadinessWebViewClient : WebViewClient() {
        @Volatile
        private var reported: Boolean = false

        // Tracks whether `https://plugin.local/` has already been
        // served as the initial self-load. Any subsequent attempt
        // (even to the same URL) is blocked — a plugin can't
        // reload itself.
        @Volatile
        private var initialSelfLoadSeen: Boolean = false

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

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url ?: return blocked("missing_url")
            return if (isAllowedInitialSelfLoad(url)) {
                // `null` = "I don't want to intercept; let the
                // WebView's own loader handle it" — which for the
                // synthetic `loadDataWithBaseURL` base URL is just
                // the HTML shell we already supplied.
                null
            } else {
                blocked(url.toString())
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != PluginHtmlShell.INITIAL_URL) {
                view?.stopLoading()
                hostSignalsRef?.reportError(
                    "webview_navigation_blocked",
                    url.orEmpty(),
                )
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest?,
        ): Boolean = true

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // `didCrash() == true` means the renderer crashed
            // (sandbox killed it / native crash). `false` means
            // Android reclaimed it under memory pressure. Both
            // shapes destroy the WebView, so the host needs a
            // per-token error regardless.
            val reason = if (detail.didCrash()) "renderer_crash" else "renderer_killed"
            hostSignalsRef?.reportError(reason, "")
            // Returning `true` tells WebView we've handled the
            // crash; without this the host process gets killed
            // too. The legacy in-process client does the same.
            return true
        }

        private fun isAllowedInitialSelfLoad(url: Uri): Boolean {
            val allowed = url.toString() == PluginHtmlShell.INITIAL_URL && !initialSelfLoadSeen
            if (allowed) initialSelfLoadSeen = true
            return allowed
        }

        private fun blocked(detail: String): WebResourceResponse {
            Timber.tag(TAG).w("webview_request_blocked %s", detail)
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Forbidden",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)),
            )
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
