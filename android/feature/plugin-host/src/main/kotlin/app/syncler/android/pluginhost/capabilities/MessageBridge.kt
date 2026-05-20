package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.android.pluginhost.PluginInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageBridge(
    private val auditLogger: AuditLogger,
) {
    suspend fun respond(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        if (args["actionId"] !is String) {
            JsonBridgeCodec.error("invalid_args")
        } else {
            "{}"
        }
    }

    suspend fun dismissBehavior(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val behavior = args["behavior"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        if (behavior != plugin.manifest.dismissBehavior) {
            auditLogger.denied(plugin.manifest.id, "dismiss_behavior_override", behavior)
        }
        "{}"
    }
}
