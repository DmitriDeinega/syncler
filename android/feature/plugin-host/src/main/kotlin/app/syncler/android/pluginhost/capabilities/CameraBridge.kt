package app.syncler.android.pluginhost.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.syncler.android.pluginhost.PluginInstance
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Phase 12 (V2 #10) `camera` capability — `ACTION_IMAGE_CAPTURE`
 * via AndroidX `TakePicture` contract. The host writes the
 * camera output to a FileProvider URI in the host's own cache;
 * after capture the host reads the bytes, stages them via the
 * handle store, and deletes the original camera tempfile
 * (triad 138 D).
 *
 * CAMERA runtime permission is required AND the camera UI
 * itself is the consent surface — no separate in-app prompt
 * (triad 138 gemini, codex acquiesced with "insert grant row
 * after full success").
 */
class CameraBridge(
    private val context: Context,
    private val coordinator: CapabilityActivityCoordinator?,
    private val grantStore: CapabilityGrantStore,
    private val handleStore: CapabilityHandleStore,
    private val auditLogger: PluginCapabilityAuditDao,
) {

    suspend fun capture(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val pluginRowId = plugin.manifest.id
            val sandboxToken = plugin.sandboxHandle?.sandboxToken ?: 0
            val callId = UUID.randomUUID().toString()
            val startedAtElapsedMs = nowElapsedMs()
            val capability = "camera"

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

            // OS permission gate (triad 138 #3: no in-app prompt;
            // the camera UI is the consent surface, but we still
            // need runtime CAMERA permission).
            val osGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (!osGranted) {
                val accepted = coord.requestPermission(callId, Manifest.permission.CAMERA)
                if (!accepted) {
                    audit(plugin, capability, callId, startedAtElapsedMs, "os_denied")
                    return@withContext JsonBridgeCodec.error("os_denied")
                }
            }

            // Allocate temp FileProvider URI for the camera output.
            val cameraDir = File(context.cacheDir, "plugin-camera").apply {
                if (!exists()) mkdirs()
            }
            val tempFile = File(cameraDir, "$callId.jpg")
            val authority = "${context.packageName}.fileprovider"
            val outputUri = try {
                FileProvider.getUriForFile(context, authority, tempFile)
            } catch (exc: Throwable) {
                Timber.tag(TAG).w(exc, "FileProvider getUriForFile failed; check manifest")
                audit(plugin, capability, callId, startedAtElapsedMs, "io_error")
                return@withContext JsonBridgeCodec.error("io_error")
            }

            // Launch ACTION_IMAGE_CAPTURE; suspends until result.
            val success = coord.captureImage(callId, outputUri)
            if (!success) {
                runCatching { tempFile.delete() }
                audit(plugin, capability, callId, startedAtElapsedMs, "cancelled")
                return@withContext JsonBridgeCodec.error("cancelled")
            }

            // Read bytes into staging, then delete the camera tempfile
            // (triad 138 D: immediate delete after staging copy).
            val bytes = try {
                tempFile.readBytes()
            } catch (exc: Throwable) {
                runCatching { tempFile.delete() }
                audit(plugin, capability, callId, startedAtElapsedMs, "io_error")
                return@withContext JsonBridgeCodec.error("io_error")
            }
            runCatching { tempFile.delete() }

            if (bytes.size > CapabilityHandleStore.MAX_BYTES) {
                audit(plugin, capability, callId, startedAtElapsedMs, "result_too_large")
                return@withContext JsonBridgeCodec.error("result_too_large")
            }

            val staged = try {
                handleStore.stage(
                    pluginRowId = pluginRowId,
                    sandboxToken = sandboxToken,
                    callId = callId,
                    bytes = bytes,
                    name = "capture-$callId.jpg",
                    mime = "image/jpeg",
                )
            } catch (exc: IllegalStateException) {
                audit(
                    plugin = plugin,
                    capability = capability,
                    callId = callId,
                    startedAtElapsedMs = startedAtElapsedMs,
                    outcome = exc.message ?: "io_error",
                )
                return@withContext JsonBridgeCodec.error(exc.message ?: "io_error")
            }

            // First-time grant after success.
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
                mimeClaimed = "image/jpeg",
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
        private const val TAG = "CameraBridge"
    }
}
