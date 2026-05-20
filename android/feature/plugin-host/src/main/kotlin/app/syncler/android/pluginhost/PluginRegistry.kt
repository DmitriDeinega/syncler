package app.syncler.android.pluginhost

import java.util.concurrent.ConcurrentHashMap

object PluginRegistry {
    private val instances = ConcurrentHashMap<String, PluginInstance>()

    fun put(instance: PluginInstance) {
        instances[instance.manifest.id]?.destroy()
        instances[instance.manifest.id] = instance
    }

    fun get(pluginId: String): PluginInstance? = instances[pluginId]

    fun unload(pluginId: String) {
        instances.remove(pluginId)?.destroy()
    }

    fun clear() {
        instances.values.forEach(PluginInstance::destroy)
        instances.clear()
    }
}
