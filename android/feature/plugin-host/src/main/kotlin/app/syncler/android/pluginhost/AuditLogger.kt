package app.syncler.android.pluginhost

import android.content.Context
import java.io.File
import java.time.Instant
import timber.log.Timber

class AuditLogger(context: Context? = null) {
    private val logFile: File? = context?.noBackupFilesDir?.resolve(AUDIT_FILE_NAME)

    fun denied(pluginId: String?, reason: String, detail: String? = null) {
        val line = buildString {
            append(Instant.now())
            append(" reason=")
            append(reason)
            if (!pluginId.isNullOrBlank()) {
                append(" plugin_id=")
                append(pluginId)
            }
            if (!detail.isNullOrBlank()) {
                append(" detail=")
                append(detail.replace('\n', ' '))
            }
        }
        Timber.tag(TAG).w(line)
        logFile?.let { file ->
            synchronized(file.absolutePath.intern()) {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }
    }

    companion object {
        const val TAG = "PLUGIN_AUDIT"
        const val AUDIT_FILE_NAME = "pluginhost_audit.log"
    }
}
