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
 * Phase 12 (V2 #10) `gallery` capability — AndroidX
 * `PickVisualMedia` / `PickMultipleVisualMedia` through the
 * [CapabilityActivityCoordinator]. SDK boundary hard-cap at
 * 10 items; multi-result staging is **atomic** — if any item
 * exceeds 16 MB or staging fails, ALL siblings are released
 * and the call returns the error (triad 138 H).
 *
 * Picker UI itself is the consent surface — no in-app prompt.
 */
class GalleryBridge(
    private val context: Context,
    private val coordinator: CapabilityActivityCoordinator?,
    private val grantStore: CapabilityGrantStore,
    private val handleStore: CapabilityHandleStore,
    private val auditLogger: PluginCapabilityAuditDao,
) {

    suspend fun pick(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = JsonBridgeCodec.objectFrom(argsJson)
            val maxItems = ((args["maxItems"] as? Number)?.toInt() ?: 1)
                .coerceIn(1, CapabilityActivityCoordinator.GALLERY_MAX_ITEMS)
            val pluginRowId = plugin.manifest.id
            val sandboxToken = plugin.sandboxHandle?.sandboxToken ?: 0
            val callId = UUID.randomUUID().toString()
            val startedAtElapsedMs = nowElapsedMs()
            val capability = "gallery"

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

            val uris: List<Uri> = if (maxItems == 1) {
                listOfNotNull(coord.pickVisualSingle(callId))
            } else {
                coord.pickVisualMultiple(callId)
            }

            if (uris.isEmpty()) {
                audit(plugin, capability, callId, startedAtElapsedMs, "cancelled")
                return@withContext JsonBridgeCodec.error("cancelled")
            }

            // Atomic staging: stage all; on any failure release
            // siblings and fail the call.
            val staged = mutableListOf<CapabilityHandleMetadata>()
            var totalBytes = 0L
            for ((i, uri) in uris.withIndex()) {
                val perCallId = "$callId-$i"
                val item = stageFromUri(uri, pluginRowId, sandboxToken, perCallId)
                if (item == null) {
                    // Release any already-staged siblings.
                    staged.forEach { runCatching { handleStore.release(pluginRowId, it.handle) } }
                    val outcome = if (totalBytes > CapabilityHandleStore.MAX_BYTES) {
                        "result_too_large"
                    } else "io_error"
                    audit(plugin, capability, callId, startedAtElapsedMs, outcome)
                    return@withContext JsonBridgeCodec.error(outcome)
                }
                staged.add(item)
                totalBytes += item.sizeBytes
            }

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
                sizeBytes = totalBytes,
                itemCount = staged.size,
            )
            val items = staged.map {
                mapOf(
                    "handle" to it.handle,
                    "name" to it.name,
                    "mime" to it.mime,
                    "sizeBytes" to it.sizeBytes,
                    "expiresAtMs" to it.expiresAtMs,
                )
            }
            JsonBridgeCodec.toJson(mapOf("items" to items))
        }

    private suspend fun stageFromUri(
        uri: Uri,
        pluginRowId: String,
        sandboxToken: Int,
        callId: String,
    ): CapabilityHandleMetadata? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && nameIdx >= 0) c.getString(nameIdx) else "image"
        } ?: "image"
        val mime = resolver.getType(uri) ?: "image/*"
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
                name = name,
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
        itemCount: Int? = null,
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
                    itemCount = itemCount,
                ),
            )
        }.onFailure { Timber.tag(TAG).w(it, "audit insert failed") }
    }

    companion object {
        private const val TAG = "GalleryBridge"
    }
}
