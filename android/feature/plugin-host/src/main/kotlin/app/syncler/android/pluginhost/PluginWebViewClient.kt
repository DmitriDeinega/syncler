package app.syncler.android.pluginhost

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class PluginWebViewClient(
    private val pluginId: String,
    private val auditLogger: AuditLogger,
    private val onRenderProcessGone: (String) -> Unit,
) : WebViewClient() {
    private var initialSelfLoadSeen = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return block("missing_url")
        return if (isAllowedInitialSelfLoad(url)) {
            null
        } else {
            block(url.toString())
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != INITIAL_URL) {
            auditLogger.denied(pluginId, "webview_navigation_blocked", url)
            view?.stopLoading()
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        auditLogger.denied(pluginId, "render_process_gone", if (Build.VERSION.SDK_INT >= 26) "crashed=${detail.didCrash()}" else null)
        onRenderProcessGone(pluginId)
        return true
    }

    private fun isAllowedInitialSelfLoad(url: Uri): Boolean {
        val allowed = url.toString() == INITIAL_URL && !initialSelfLoadSeen
        if (allowed) initialSelfLoadSeen = true
        return allowed
    }

    private fun block(detail: String): WebResourceResponse {
        auditLogger.denied(pluginId, "webview_request_blocked", detail)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    companion object {
        const val INITIAL_URL = "file:///plugin/index.html"
    }
}
