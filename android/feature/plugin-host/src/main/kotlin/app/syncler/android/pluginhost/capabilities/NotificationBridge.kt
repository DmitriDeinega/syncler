package app.syncler.android.pluginhost.capabilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.syncler.android.pluginhost.PluginInstance
import app.syncler.android.pluginhost.PluginRegistry
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * V2 #11 — `platform.showNotification(title, body, groupId,
 * actionId, actionLabel)`.
 *
 * The `actionId` + `actionLabel` pair upgrades the legacy fire-
 * and-forget notification into a request/response handshake: a
 * tap on the notification routes back through
 * [NotificationActionReceiver] to the originating plugin's
 * `onAction(actionId, payload)` hook. Plugins can `await` for
 * the user to interact even when the inbox UI is closed.
 *
 * Legacy callers (no actionId) still work — the notification
 * is a plain tap-to-dismiss.
 */
class NotificationBridge(context: Context) {
    private val appContext = context.applicationContext

    // Triad 140 codex #2 FIX: receiver registration moved to a
    // manifest-declared <receiver> entry in the app module.
    // That way a notification tap that fires AFTER process death
    // still cold-starts the receiver instead of no-op'ing. The
    // previous volatile-check-then-register dance had two bugs:
    // (1) check-then-set wasn't atomic; (2) `receiverRegistered =
    // true` was set BEFORE registerReceiver(), so a failed call
    // silently suppressed all future retries.

    suspend fun show(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val title = args["title"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        val body = args["body"] as? String ?: ""
        val actionId = args["actionId"] as? String
        val actionLabel = args["actionLabel"] as? String
        ensureChannel()
        val notificationId = (plugin.manifest.id + title + body).hashCode().absoluteValue
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setGroup(args["groupId"] as? String)

        // V2 #11 request/response handshake: wire a PendingIntent
        // that dispatches plugin.onAction when the user taps the
        // notification. We use the BROADCAST flavor so taps don't
        // bring the host app to the foreground unless the plugin
        // explicitly wants to.
        if (actionId != null) {
            val intent = Intent(ACTION_INTENT).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_PLUGIN_ID, plugin.manifest.id)
                putExtra(EXTRA_ACTION_ID, actionId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = PendingIntent.getBroadcast(
                appContext,
                notificationId,
                intent,
                flags,
            )
            builder.setContentIntent(pending)
            if (actionLabel != null) {
                builder.addAction(
                    NotificationCompat.Action.Builder(0, actionLabel, pending).build(),
                )
            }
        }

        val notification = builder.build()
        NotificationManagerCompat.from(appContext).notify(notificationId, notification)
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
        private const val TAG = "NotificationBridge"
        private const val CHANNEL_ID = "pluginhost"
        const val ACTION_INTENT = "app.syncler.NOTIFICATION_ACTION"
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_ACTION_ID = "action_id"
    }
}

/**
 * V2 #11 broadcast receiver. Routes user taps on plugin
 * notifications into the originating plugin's `onAction` hook
 * via [PluginRegistry]. Same-app broadcast (RECEIVER_NOT_EXPORTED
 * on API 34+) so no other app can spoof these.
 */
object NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != NotificationBridge.ACTION_INTENT) return
        val pluginId = intent.getStringExtra(NotificationBridge.EXTRA_PLUGIN_ID) ?: return
        val actionId = intent.getStringExtra(NotificationBridge.EXTRA_ACTION_ID) ?: return
        // Triad 140 codex #3 FIX: actionId comes from a plugin-
        // supplied string. Hand-interpolating it into JSON would
        // emit invalid bytes for any actionId containing a
        // quote, backslash, or control char. Route through the
        // existing JSON codec for safe escaping.
        val payloadJson = JsonBridgeCodec.toJson(
            mapOf(
                "actionId" to actionId,
                "surface" to "notification",
            ),
        )
        runCatching {
            PluginRegistry.dispatchAction(pluginId, actionId, payloadJson)
        }.onFailure { Timber.tag("NotifReceiver").w(it, "dispatchAction failed") }
    }
}
