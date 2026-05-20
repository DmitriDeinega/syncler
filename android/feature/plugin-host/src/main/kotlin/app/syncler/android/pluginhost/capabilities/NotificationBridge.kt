package app.syncler.android.pluginhost.capabilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.syncler.android.pluginhost.PluginInstance
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationBridge(context: Context) {
    private val appContext = context.applicationContext

    suspend fun show(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val title = args["title"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        val body = args["body"] as? String ?: ""
        ensureChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setGroup(args["groupId"] as? String)
            .build()
        NotificationManagerCompat.from(appContext).notify((plugin.manifest.id + title + body).hashCode().absoluteValue, notification)
        "{}"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Plugin notifications", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL_ID = "pluginhost"
    }
}
