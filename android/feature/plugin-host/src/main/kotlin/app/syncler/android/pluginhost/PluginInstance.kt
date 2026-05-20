package app.syncler.android.pluginhost

import android.webkit.WebView

class PluginInstance(
    val manifest: PluginManifest,
    val grantedCapabilities: Set<String>,
    val bundleFilePath: String,
    val webView: WebView? = null,
    var bridge: PluginBridge? = null,
) {
    fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        val script = "__syncler_internal_dispatch(" +
            "${JsonEscaping.quote(hook)},[$payloadJson],${JsonEscaping.quote(callbackId)})"
        val target = webView ?: return
        target.post { target.evaluateJavascript(script, null) }
    }

    fun destroy() {
        runCatching {
            webView?.removeJavascriptInterface(PluginBridge.NATIVE_BRIDGE_NAME)
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}
