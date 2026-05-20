package app.syncler.android.pluginhost

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PluginPermissionStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pluginhost_permissions",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun grantedCapabilities(pluginId: String): Set<String> =
        prefs.getStringSet(key(pluginId), emptySet()).orEmpty()

    fun setGrantedCapabilities(pluginId: String, capabilities: Set<String>) {
        prefs.edit().putStringSet(key(pluginId), capabilities).apply()
    }

    private fun key(pluginId: String): String = "capabilities:$pluginId"
}
