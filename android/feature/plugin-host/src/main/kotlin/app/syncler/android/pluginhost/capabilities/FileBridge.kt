package app.syncler.android.pluginhost.capabilities

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.syncler.android.pluginhost.PluginInstance
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Phase 12 (V2 #10) `file` capability — `ACTION_OPEN_DOCUMENT`
 * via AndroidX `OpenDocument` contract through the
 * [CapabilityActivityCoordinator]. The SAF picker UI itself is
 * the consent surface — no in-app prompt per triad 138 spec.
 *
 * Returns a handle (per docs/plugin-capability-expansion.md
 * "Binary transport"); plugin reads via `platform.fileBytes(...)`.
 */
class FileBridge(
    private val context: Context,
    private val coordinator: CapabilityActivityCoordinator?,
    private val grantStore: CapabilityGrantStore,
    private val handleStore: CapabilityHandleStore,
    private val auditLogger: PluginCapabilityAuditDao,
) {

    suspend fun pick(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = JsonBridgeCodec.objectFrom(argsJson)
            val mimeFilter = args["mimeFilter"] as? String
            val pluginRowId = plugin.manifest.id
            val sandboxToken = plugin.sandboxHandle?.sandboxToken ?: 0
            val callId = UUID.randomUUID().toString()
            val startedAtElapsedMs = nowElapsedMs()
            val capability = "file"

            if (capability !in plugin.manifest.declaredCapabilities.map { it.toString() }
                .toSet()
            ) {
                audit(plugin, capability, callId, startedAtElapsedMs, "capability_not_declared")
                return@withContext JsonBridgeCodec.error("capability_not_declared")
            }

            val coord = coordinator ?: run {
                audit(plugin, capability, callId, startedAtElapsedMs, "no_foreground_activity")
                return@withContext JsonBridgeCodec.error("no_foreground_activity")
            }

            // Spec v3 Category A: launch picker directly, no
            // in-app prompt. The SAF dialog is the consent.
            val uri = coord.pickFile(callId, mimeFilter)
            if (uri == null) {
                audit(plugin, capability, callId, startedAtElapsedMs, "cancelled")
                return@withContext JsonBridgeCodec.error("cancelled")
            }

            val staged = stageFromUri(uri, pluginRowId, sandboxToken, callId)
            if (staged == null) {
                audit(plugin, capability, callId, startedAtElapsedMs, "io_error")
                return@withContext JsonBridgeCodec.error("io_error")
            }

            // First-time grant: insert AFTER successful staging
            // per spec v3 "insert after full success".
            if (!grantStore.hasGrant(pluginRowId, capability)) {
                grantStore.grant(pluginRowId, capability, nowWallClockMs())
            } else {
                grantStore.touch(pluginRowId, capability, nowWallClockMs())
            }

            audit(
                plugin = plugin,
                capability = capability,
                callId = callId,
                startedAtElapsedMs = startedAtElapsedMs,
                outcome = "success",
                sizeBytes = staged.sizeBytes,
                mimeClaimed = staged.mime,
            )
            JsonBridgeCodec.toJson(
                mapOf(
                    "handle" to staged.handle,
                    "name" to staged.name,
                    "mime" to staged.mime,
                    "sizeBytes" to staged.sizeBytes,
                    "expiresAtMs" to staged.expiresAtMs,
                ),
            )
        }

    private suspend fun stageFromUri(
        uri: Uri,
        pluginRowId: String,
        sandboxToken: Int,
        callId: String,
    ): CapabilityHandleMetadata? {
        val resolver = context.contentResolver
        // Triad 138 gemini missing-item: SAF URI permission is
        // bound to the activity-result delivery; copy synchronously
        // before the callback finishes.
        val nameAndSize = resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) else "untitled"
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                Pair(name, size)
            } else null
        } ?: Pair("untitled", -1L)

        if (nameAndSize.second > CapabilityHandleStore.MAX_BYTES) return null

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (exc: Throwable) {
            Timber.tag(TAG).w(exc, "openInputStream failed")
            null
        } ?: return null
        if (bytes.size > CapabilityHandleStore.MAX_BYTES) return null
        return try {
            handleStore.stage(
                pluginRowId = pluginRowId,
                sandboxToken = sandboxToken,
                callId = callId,
                bytes = bytes,
                name = nameAndSize.first,
                mime = mime,
            )
        } catch (exc: IllegalStateException) {
            null
        }
    }

    private suspend fun audit(
        plugin: PluginInstance,
        capability: String,
        callId: String,
        startedAtElapsedMs: Long,
        outcome: String,
        sizeBytes: Long? = null,
        mimeClaimed: String? = null,
    ) {
        runCatching {
            auditLogger.insert(
                PluginCapabilityAuditRow(
                    pluginRowId = plugin.manifest.id,
                    capability = capability,
                    sandboxToken = plugin.sandboxHandle?.sandboxToken ?: 0,
                    callId = callId,
                    atMs = nowWallClockMs(),
                    durationMs = nowElapsedMs() - startedAtElapsedMs,
                    outcome = outcome,
                    surface = "activity_result",
                    sizeBytes = sizeBytes,
                    mimeClaimed = mimeClaimed,
                ),
            )
        }.onFailure { Timber.tag(TAG).w(it, "audit insert failed") }
    }

    companion object {
        private const val TAG = "FileBridge"
    }
}
