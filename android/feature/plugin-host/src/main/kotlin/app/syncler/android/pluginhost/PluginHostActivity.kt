package app.syncler.android.pluginhost

import android.app.Activity
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PluginHostActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var auditLogger: AuditLogger
    private var hostMessenger: Messenger? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auditLogger = AuditLogger(this)
        hostMessenger = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_HOST_MESSENGER, Messenger::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_HOST_MESSENGER)
        }
        setContentView(FrameLayout(this))
        sendStatus(MSG_HOST_READY)
    }

    override fun onDestroy() {
        super.onDestroy()
        PluginRegistry.clear()
        scope.cancel()
    }

    private fun sendStatus(what: Int) {
        runCatching { hostMessenger?.send(Message.obtain(null, what)) }
            .onFailure { auditLogger.denied(null, "ipc_status_failed", it.message) }
    }

    companion object {
        const val EXTRA_HOST_MESSENGER = "app.syncler.android.pluginhost.HOST_MESSENGER"
        const val MSG_HOST_READY = 1
    }
}
