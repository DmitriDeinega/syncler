package app.syncler.android.pluginhost

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Phase 10b: abstraction over "deliver a bridge-call result back
 * to the plugin's JS callback".
 *
 * - In-process plugins (the legacy WebView-direct path that step 6
 *   will delete) use [WebViewBridgeDelivery], which posts an
 *   `evaluateJavascript` to the main looper.
 * - Sandboxed plugins (the new `:plugin` process) use
 *   `SandboxBridgeDelivery` (in the `sandbox` sub-package),
 *   which forwards through the `SandboxRouter` AIDL.
 *
 * Both implementations are fire-and-forget — the caller doesn't
 * await the actual JS callback firing. Failures get logged but
 * never surfaced (the underlying capability call already
 * completed; lost callback delivery just means the plugin's
 * promise hangs until its own timeout).
 */
interface BridgeDelivery {
    fun deliver(callbackId: String, resultJson: String)
}

/**
 * Legacy in-process delivery: posts an `evaluateJavascript` on
 * the main thread that invokes the plugin's
 * `window.__syncler_internal_callback`. Step 6 will delete this
 * implementation alongside the in-process loader.
 */
class WebViewBridgeDelivery(
    private val webViewProvider: () -> WebView?,
    private val auditLogger: AuditLogger,
    private val pluginId: String,
) : BridgeDelivery {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun deliver(callbackId: String, resultJson: String) {
        val script = "window.__syncler_internal_callback(${JsonEscaping.quote(callbackId)}, $resultJson)"
        mainHandler.post {
            runCatching { webViewProvider()?.evaluateJavascript(script, null) }
                .onFailure { auditLogger.denied(pluginId, "callback_delivery_failed", it.message) }
        }
    }
}
