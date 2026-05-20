package app.syncler.android.pluginhost

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.syncler.android.pluginhost.capabilities.CameraBridge
import app.syncler.android.pluginhost.capabilities.FileBridge
import app.syncler.android.pluginhost.capabilities.GalleryBridge
import app.syncler.android.pluginhost.capabilities.LocationBridge
import app.syncler.android.pluginhost.capabilities.MessageBridge
import app.syncler.android.pluginhost.capabilities.NetworkBridge
import app.syncler.android.pluginhost.capabilities.NotificationBridge
import app.syncler.android.pluginhost.capabilities.StorageBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PluginBridge(
    private val plugin: PluginInstance,
    private val webViewProvider: () -> WebView?,
    private val scope: CoroutineScope,
    private val networkBridge: NetworkBridge,
    private val storageBridge: StorageBridge,
    private val notificationBridge: NotificationBridge,
    private val cameraBridge: CameraBridge,
    private val galleryBridge: GalleryBridge,
    private val fileBridge: FileBridge,
    private val locationBridge: LocationBridge,
    private val messageBridge: MessageBridge,
    private val auditLogger: AuditLogger,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun call(method: String, argsJson: String, callbackId: String) {
        scope.launch {
            val resultJson = runCatching {
                withTimeout(CALL_TIMEOUT_MS) {
                    bridgeSuccessJson(dispatch(method, argsJson))
                }
            }.getOrElse { error ->
                val reason = when (error) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
                    is PluginBridgeException -> error.code
                    else -> "bridge_error"
                }
                auditLogger.denied(plugin.manifest.id, reason, method)
                bridgeErrorJson(reason, error.message ?: reason)
            }
            deliver(callbackId, resultJson)
        }
    }

    private suspend fun dispatch(method: String, argsJson: String): String {
        val capability = requiredCapability(method)
        if (capability != null && capability !in plugin.grantedCapabilities) {
            auditLogger.denied(plugin.manifest.id, "capability_not_granted", capability)
            throw PluginBridgeException("capability_not_granted", "Capability not granted: $capability")
        }

        return when (method) {
            "platform.network.fetch" -> networkBridge.fetch(plugin, argsJson)
            "platform.storage.get" -> storageBridge.get(plugin, argsJson)
            "platform.storage.set" -> storageBridge.set(plugin, argsJson)
            "platform.storage.delete" -> storageBridge.delete(plugin, argsJson)
            "platform.showNotification" -> notificationBridge.show(plugin, argsJson)
            "platform.camera.capture" -> cameraBridge.capture(plugin, argsJson)
            "platform.gallery.pick" -> galleryBridge.pick(plugin, argsJson)
            "platform.file.pick" -> fileBridge.pick(plugin, argsJson)
            "platform.location.current" -> locationBridge.current(plugin, argsJson)
            "platform.message.respond" -> messageBridge.respond(plugin, argsJson)
            "platform.message.dismissBehavior" -> messageBridge.dismissBehavior(plugin, argsJson)
            else -> {
                auditLogger.denied(plugin.manifest.id, "unknown_method", method)
                throw PluginBridgeException("unknown_method", "Unknown bridge method: $method")
            }
        }
    }

    private fun deliver(callbackId: String, resultJson: String) {
        val script = "window.__syncler_internal_callback(${JsonEscaping.quote(callbackId)}, $resultJson)"
        mainHandler.post {
            runCatching { webViewProvider()?.evaluateJavascript(script, null) }
                .onFailure { auditLogger.denied(plugin.manifest.id, "callback_delivery_failed", it.message) }
        }
    }

    companion object {
        const val NATIVE_BRIDGE_NAME = "__syncler_native__"
        const val CALL_TIMEOUT_MS = 30_000L
    }
}

internal fun requiredCapability(method: String): String? = when {
    method.startsWith("platform.network.") -> "network"
    method.startsWith("platform.storage.") -> "storage"
    method.startsWith("platform.camera.") -> "camera"
    method.startsWith("platform.gallery.") -> "gallery"
    method.startsWith("platform.file.") -> "file"
    method.startsWith("platform.location.") -> "location"
    method == "platform.showNotification" -> null
    method.startsWith("platform.message.") -> null
    else -> null
}

internal fun bridgeSuccessJson(valueJson: String): String =
    """{"success":true,"value":$valueJson}"""

internal fun bridgeErrorJson(error: String, message: String): String =
    """{"success":false,"error":${JsonEscaping.quote(error)},"message":${JsonEscaping.quote(message)}}"""

internal class PluginBridgeException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
