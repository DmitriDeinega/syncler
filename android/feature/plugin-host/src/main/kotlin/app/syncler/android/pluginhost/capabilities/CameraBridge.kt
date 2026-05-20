package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.PluginInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CameraBridge {
    suspend fun capture(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        JsonBridgeCodec.error("not_implemented")
    }
}
