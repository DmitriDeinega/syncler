package app.syncler.core.push

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Receives FCM data messages from the Syncler server.
 *
 * The payload is intentionally opaque metadata only: `type`, `message_id`,
 * `plugin_id`, `min_plugin_version`. The service routes by `type` —
 * `"message"` triggers a foreground service that fetches + decrypts the
 * encrypted body via [PluginNotificationService]; `"dismiss"` is handled
 * by [DismissEventHandler].
 */
@AndroidEntryPoint
class SynclerFcmService : FirebaseMessagingService() {

    @Inject lateinit var dispatcher: FcmDispatcher
    @Inject lateinit var fcmTokenRegistrar: FcmTokenRegistrar

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val type = data["type"] ?: run {
            Timber.tag(TAG).w("FCM message missing 'type' field; ignoring")
            return
        }

        when (type) {
            "message" -> {
                val messageId = data["message_id"] ?: return logMissing("message_id")
                val pluginId = data["plugin_id"] ?: return logMissing("plugin_id")
                val minVersion = data["min_plugin_version"].orEmpty()
                PluginNotificationService.start(
                    context = applicationContext,
                    messageId = messageId,
                    pluginId = pluginId,
                    minPluginVersion = minVersion,
                )
            }
            "dismiss" -> {
                val messageId = data["message_id"] ?: return logMissing("message_id")
                val pluginId = data["plugin_id"] ?: return logMissing("plugin_id")
                scope.launch {
                    dispatcher.dispatchDismiss(messageId, pluginId)
                }
            }
            else -> Timber.tag(TAG).w("unknown FCM payload type: %s", type)
        }
    }

    override fun onNewToken(token: String) {
        Timber.tag(TAG).i("new FCM token received")
        scope.launch {
            runCatching { fcmTokenRegistrar.registerToken(token) }
                .onFailure { Timber.tag(TAG).w(it, "failed to register FCM token") }
        }
    }

    private fun logMissing(field: String) {
        Timber.tag(TAG).w("FCM data message missing required field: %s", field)
    }

    companion object {
        private const val TAG = "SynclerFcm"
    }
}

/**
 * Indirection so the service can be unit-tested with a fake.
 */
interface FcmDispatcher {
    suspend fun dispatchDismiss(messageId: String, pluginId: String)
}

interface FcmTokenRegistrar {
    suspend fun registerToken(token: String)
}
