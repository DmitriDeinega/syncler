package app.syncler.android.pluginhost.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.syncler.android.pluginhost.PluginInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocationBridge(private val context: Context) {
    suspend fun current(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) JsonBridgeCodec.error("permission_denied") else JsonBridgeCodec.error("not_implemented")
    }
}
