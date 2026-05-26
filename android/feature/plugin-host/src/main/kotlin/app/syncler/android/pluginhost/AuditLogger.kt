package app.syncler.android.pluginhost

import android.content.Context
import java.io.File
import java.time.Instant
import timber.log.Timber

/**
 * Append-only audit log for plugin bridge invocations.
 *
 * Triad 142 closeout (both reviewers): renamed `denied(...)` →
 * `record(...)` and the log field `reason=` → `outcome=`. The
 * method is called for BOTH success and failure outcomes; the
 * legacy "denied" name was misleading whenever it ran on a
 * success path (e.g. `record(plugin, "respond_ok", endpoint)`).
 */
class AuditLogger(context: Context? = null) {
    private val logFile: File? = context?.noBackupFilesDir?.resolve(AUDIT_FILE_NAME)

    fun record(pluginId: String?, outcome: String, detail: String? = null) {
        val line = buildString {
            append(Instant.now())
            append(" outcome=")
            append(outcome)
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
