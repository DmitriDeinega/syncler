package app.syncler.android.pluginhost

import app.syncler.android.pluginhost.capabilities.CameraBridge
import app.syncler.android.pluginhost.capabilities.CapabilityHandleStore
import app.syncler.android.pluginhost.capabilities.FileBridge
import app.syncler.android.pluginhost.capabilities.GalleryBridge
import app.syncler.android.pluginhost.capabilities.JsonBridgeCodec
import app.syncler.android.pluginhost.capabilities.LocationBridge
import app.syncler.android.pluginhost.capabilities.MessageBridge
import app.syncler.android.pluginhost.capabilities.NetworkBridge
import app.syncler.android.pluginhost.capabilities.NotificationBridge
import app.syncler.android.pluginhost.capabilities.StorageBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Phase 10b step 6: in-process WebView path deleted. `PluginBridge`
 * is no longer a `@JavascriptInterface` — it's a plain capability
 * dispatcher. The sandbox subprocess's WebView holds the bridge,
 * which routes bridge calls back to the host via AIDL; the host's
 * `SandboxBridgeDispatcher` then invokes [call] on the appropriate
 * `PluginBridge` instance keyed by `sandboxToken`. The result is
 * delivered back to the JS via [BridgeDelivery] — which is a
 * `SandboxBridgeDelivery` in production.
 */
class PluginBridge(
    private val plugin: PluginInstance,
    private val delivery: BridgeDelivery,
    private val scope: CoroutineScope,
    private val networkBridge: NetworkBridge,
    private val storageBridge: StorageBridge,
    private val notificationBridge: NotificationBridge,
    private val cameraBridge: CameraBridge,
    private val galleryBridge: GalleryBridge,
    private val fileBridge: FileBridge,
    private val locationBridge: LocationBridge,
    private val messageBridge: MessageBridge,
    /**
     * Phase 12 (V2 #10): the handle store backing
     * `platform.fileBytes` + `platform.releaseHandle`. Nullable
     * for backwards compat with older instance-factory call sites
     * that haven't been updated; calls return invalid_handle if
     * null.
     */
    private val capabilityHandleStore: CapabilityHandleStore? = null,
    private val auditLogger: AuditLogger,
) {
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
                auditLogger.record(plugin.manifest.id, reason, method)
                bridgeErrorJson(reason, error.message ?: reason)
            }
            delivery.deliver(callbackId, resultJson)
        }
    }

    private suspend fun dispatch(method: String, argsJson: String): String {
        // Triad 139 codex #3 fix: skip the pre-dispatch gate for
        // Phase 12 capabilities (camera/gallery/file/location).
        // Those bridges do their own grant check + per-invocation
        // prompt internally; gating on plugin.grantedCapabilities
        // here would short-circuit the runtime grant flow.
        // Network + storage + non-capability calls keep the
        // legacy pre-dispatch gate.
        val requiresPhase12RuntimeGrant = method.startsWith("platform.camera.") ||
            method.startsWith("platform.gallery.") ||
            method.startsWith("platform.file.") ||
            method.startsWith("platform.location.")
        if (!requiresPhase12RuntimeGrant) {
            val capability = requiredCapability(method)
            if (capability != null && capability !in plugin.grantedCapabilities) {
                auditLogger.record(plugin.manifest.id, "capability_not_granted", capability)
                throw PluginBridgeException("capability_not_granted", "Capability not granted: $capability")
            }
        } else {
            // Phase 12 capabilities: only check that the plugin
            // declared SOME variant in the manifest set (the
            // bridges do the fine-grained check internally).
            val anyMatch = when {
                method.startsWith("platform.location.") ->
                    "location.coarse" in plugin.grantedCapabilities ||
                        "location.fine" in plugin.grantedCapabilities
                method.startsWith("platform.camera.") -> "camera" in plugin.grantedCapabilities
                method.startsWith("platform.gallery.") -> "gallery" in plugin.grantedCapabilities
                method.startsWith("platform.file.") -> "file" in plugin.grantedCapabilities
                else -> true
            }
            if (!anyMatch) {
                auditLogger.record(plugin.manifest.id, "capability_not_granted", method)
                throw PluginBridgeException(
                    "capability_not_granted",
                    "Capability not declared in manifest",
                )
            }
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
            "platform.fileBytes" -> readHandleBytes(argsJson)
            "platform.releaseHandle" -> releaseCapHandle(argsJson)
            else -> {
                auditLogger.record(plugin.manifest.id, "unknown_method", method)
                throw PluginBridgeException("unknown_method", "Unknown bridge method: $method")
            }
        }
    }

    /**
     * Phase 12 step 13: stateless seek-and-read for capability
     * staged bytes. `platform.fileBytes(handle, offset, length)`.
     * Returns `{bytes: base64, eof: boolean}` on success.
     */
    private fun readHandleBytes(argsJson: String): String {
        val store = capabilityHandleStore ?: return JsonBridgeCodec.error("invalid_handle")
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val handle = args["handle"] as? String
            ?: return JsonBridgeCodec.error("invalid_handle")
        val offset = (args["offset"] as? Number)?.toLong() ?: 0L
        val length = (args["length"] as? Number)?.toInt() ?: CapabilityHandleStore.MAX_CHUNK_BYTES
        val result = store.read(plugin.manifest.id, handle, offset, length)
            ?: return JsonBridgeCodec.error("invalid_handle")
        return JsonBridgeCodec.toJson(
            mapOf(
                "bytes" to android.util.Base64.encodeToString(
                    result.bytes,
                    android.util.Base64.NO_WRAP,
                ),
                "eof" to result.eof,
            ),
        )
    }

    /**
     * Phase 12 step 13: explicit handle release. Returns `{}` on
     * success (or already-released — idempotent).
     */
    private suspend fun releaseCapHandle(argsJson: String): String {
        val store = capabilityHandleStore ?: return "{}"
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val handle = args["handle"] as? String ?: return "{}"
        store.release(plugin.manifest.id, handle)
        return "{}"
    }

    companion object {
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
