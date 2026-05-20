package app.syncler.core.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Foreground service that runs plugin code on FCM wake-up.
 *
 * Lifecycle per message:
 *   1. FCM wakes the app; SynclerFcmService starts this service with metadata.
 *   2. Service immediately calls startForeground() with a low-priority "Receiving..."
 *      notification (Android 14+ uses FOREGROUND_SERVICE_TYPE_DATA_SYNC).
 *   3. PluginMessagePipeline (DI) fetches encrypted body from server, decrypts,
 *      dispatches to plugin instance's onMessage hook. Plugin returns a
 *      NotificationDescriptor which becomes the user-visible notification.
 *   4. Service stops (whether or not the plugin succeeded). 30s hard timeout.
 */
@AndroidEntryPoint
class PluginNotificationService : Service() {

    @Inject lateinit var pipeline: PluginMessagePipeline
    @Inject lateinit var notifications: NotificationFactory

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val activeJobs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Job, Boolean>())

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel(this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)
        val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID)
        val minVersion = intent?.getStringExtra(EXTRA_MIN_PLUGIN_VERSION).orEmpty()
        if (messageId == null || pluginId == null) {
            Timber.tag(TAG).w("missing message_id or plugin_id; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val job = scope.launch {
            val outcome = withTimeoutOrNull(MAX_WORK_MS) {
                pipeline.process(
                    messageId = messageId,
                    pluginId = pluginId,
                    minPluginVersion = minVersion,
                )
            }
            if (outcome == null) {
                Timber.tag(TAG).w("plugin pipeline timed out for message=%s", messageId)
                notifications.postDeliveryFailed(this@PluginNotificationService, messageId)
            } else {
                outcome.notification?.let { notifications.post(this@PluginNotificationService, it) }
                if (outcome.requiresUpdate) {
                    notifications.postUpdateRequired(
                        context = this@PluginNotificationService,
                        pluginId = pluginId,
                        requiredVersion = outcome.requiredVersion ?: minVersion,
                    )
                }
            }
            if (activeJobs.size <= 1) {
                stopSelf(startId)
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Cancel the WHOLE scope so all queued jobs stop, not just the latest.
        supervisor.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncler")
            .setContentText("Receiving message…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PluginNotifSvc"
        private const val EXTRA_MESSAGE_ID = "syncler.message_id"
        private const val EXTRA_PLUGIN_ID = "syncler.plugin_id"
        private const val EXTRA_MIN_PLUGIN_VERSION = "syncler.min_plugin_version"

        private const val FOREGROUND_ID = 11_001
        const val FOREGROUND_CHANNEL_ID = "syncler.foreground.delivery"
        const val USER_CHANNEL_ID = "syncler.user.messages"

        private const val MAX_WORK_MS = 30_000L

        fun start(context: Context, messageId: String, pluginId: String, minPluginVersion: String) {
            val intent = Intent(context, PluginNotificationService::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_PLUGIN_ID, pluginId)
                putExtra(EXTRA_MIN_PLUGIN_VERSION, minPluginVersion)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/**
 * Implemented by the host app — fetches encrypted body, decrypts, dispatches
 * to the loaded plugin instance.
 */
interface PluginMessagePipeline {
    suspend fun process(
        messageId: String,
        pluginId: String,
        minPluginVersion: String,
    ): PluginMessageOutcome
}

data class PluginMessageOutcome(
    val notification: PluginNotificationRequest? = null,
    val requiresUpdate: Boolean = false,
    val requiredVersion: String? = null,
)

data class PluginNotificationRequest(
    val title: String,
    val body: String,
    val importance: String = "default",
    val groupId: String? = null,
)

interface NotificationFactory {
    fun ensureChannel(context: Context)
    fun post(context: Context, request: PluginNotificationRequest)
    fun postUpdateRequired(context: Context, pluginId: String, requiredVersion: String)
    fun postDeliveryFailed(context: Context, messageId: String)
}

class DefaultNotificationFactory @Inject constructor() : NotificationFactory {

    override fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val foregroundChannel = NotificationChannel(
            PluginNotificationService.FOREGROUND_CHANNEL_ID,
            "Background delivery",
            NotificationManager.IMPORTANCE_MIN,
        )
        val userChannel = NotificationChannel(
            PluginNotificationService.USER_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(foregroundChannel)
        nm.createNotificationChannel(userChannel)
    }

    override fun post(context: Context, request: PluginNotificationRequest) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(context, PluginNotificationService.USER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setPriority(importanceToPriority(request.importance))
            .setAutoCancel(true)
        request.groupId?.let { builder.setGroup(it) }
        nm.notify(request.hashCode(), builder.build())
    }

    override fun postUpdateRequired(context: Context, pluginId: String, requiredVersion: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(context, PluginNotificationService.USER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Plugin update required")
            .setContentText("Tap to update $pluginId to $requiredVersion to view this message")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        nm.notify(pluginId.hashCode(), builder.build())
    }

    override fun postDeliveryFailed(context: Context, messageId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(context, PluginNotificationService.USER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Message delivery failed")
            .setContentText("Open Syncler to retry")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        nm.notify("failed:$messageId".hashCode(), builder.build())
    }

    private fun importanceToPriority(value: String): Int = when (value) {
        "low" -> NotificationCompat.PRIORITY_LOW
        "high" -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }
}
