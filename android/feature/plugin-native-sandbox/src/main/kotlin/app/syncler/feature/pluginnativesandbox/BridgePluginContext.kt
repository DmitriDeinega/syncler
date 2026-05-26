package app.syncler.feature.pluginnativesandbox

import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.plugin.runtime.CameraOptions
import app.syncler.plugin.runtime.CameraResult
import app.syncler.plugin.runtime.CapabilityHandle
import app.syncler.plugin.runtime.FileBytesChunk
import app.syncler.plugin.runtime.FileOptions
import app.syncler.plugin.runtime.FileResult
import app.syncler.plugin.runtime.GalleryOptions
import app.syncler.plugin.runtime.GalleryResult
import app.syncler.plugin.runtime.LocationOptions
import app.syncler.plugin.runtime.LocationResult
import app.syncler.plugin.runtime.NetworkRequest
import app.syncler.plugin.runtime.NetworkResponse
import app.syncler.plugin.runtime.Notification
import app.syncler.plugin.runtime.PluginContext
import app.syncler.plugin.runtime.PluginStorage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/**
 * Phase 11 native [PluginContext] implementation. Marshals each
 * `platform.*` call into a `bridgeCall(method, argsJson, callbackId)`
 * AIDL invocation to the host, then suspends until the host's
 * `deliverBridgeResult` arrives.
 *
 * The native sandbox process never makes the actual network /
 * camera / storage call — the isolated UID can't reach those
 * subsystems anyway. The host's PluginBridge (Phase 10) is the
 * single dispatcher for both JS and native plugins.
 *
 * Each method returns [Result] so transient errors round-trip
 * without an exception; the host's audit log records the
 * `(method, plugin_row_id)` tuple either way.
 *
 * The pending-call map is `ConcurrentHashMap` because bridge
 * results arrive on the AIDL Binder thread (not the plugin's
 * dispatcher) and may interleave with the plugin's coroutines.
 */
internal class BridgePluginContext(
    override val pluginId: String,
    override val grantedCapabilities: Set<String>,
    private val sandboxToken: Int,
    private val hostCallback: IPluginHostCallback,
) : PluginContext {

    private val pending = ConcurrentHashMap<String, (String) -> Unit>()

    /**
     * Called by the surrounding service when the host delivers a
     * bridge result for this token. Looks up the suspended
     * continuation and resumes it with the result JSON. If no
     * continuation is registered (caller timed out / cancelled),
     * the result is dropped.
     */
    fun onBridgeResult(callbackId: String, resultJson: String) {
        val resume = pending.remove(callbackId) ?: run {
            Timber.tag(TAG).w("bridge result for unknown callbackId=%s", callbackId)
            return
        }
        resume(resultJson)
    }

    override suspend fun networkFetch(request: NetworkRequest): Result<NetworkResponse> =
        bridgeRoundTrip("platform.networkFetch", encodeNetworkRequest(request)) { json ->
            decodeNetworkResponse(json)
        }

    override suspend fun storage(): PluginStorage = object : PluginStorage {
        override suspend fun get(key: String) =
            bridgeRoundTrip("platform.storage.get", "{\"key\":${escape(key)}}") { json ->
                Result.success(decodeNullableBlob(json))
            }
        override suspend fun set(key: String, value: ByteArray) =
            bridgeRoundTrip(
                "platform.storage.set",
                "{\"key\":${escape(key)},\"value\":\"${base64(value)}\"}",
            ) { Result.success(Unit) }
        override suspend fun delete(key: String) =
            bridgeRoundTrip(
                "platform.storage.delete",
                "{\"key\":${escape(key)}}",
            ) { Result.success(Unit) }
        override suspend fun list(prefix: String) =
            bridgeRoundTrip(
                "platform.storage.list",
                "{\"prefix\":${escape(prefix)}}",
            ) { json -> Result.success(decodeStringList(json)) }
    }

    override suspend fun showNotification(notification: Notification): Result<Unit> =
        bridgeRoundTrip(
            "platform.showNotification",
            encodeNotification(notification),
        ) { Result.success(Unit) }

    override suspend fun cameraCapture(options: CameraOptions): Result<CameraResult> =
        bridgeRoundTrip(
            "platform.cameraCapture",
            "{\"front\":${options.front}}",
        ) { json -> decodeCameraResult(json) }

    override suspend fun galleryPick(options: GalleryOptions): Result<GalleryResult> =
        bridgeRoundTrip(
            "platform.galleryPick",
            "{\"maxItems\":${options.maxItems},\"mimeFilter\":${
                options.mimeFilter?.let { escape(it) } ?: "null"
            }}",
        ) { json -> decodeGalleryResult(json) }

    override suspend fun filePick(options: FileOptions): Result<FileResult> =
        bridgeRoundTrip(
            "platform.filePick",
            "{\"mimeFilter\":${options.mimeFilter?.let { escape(it) } ?: "null"}}",
        ) { json -> decodeFileResult(json) }

    override suspend fun locationCurrent(options: LocationOptions): Result<LocationResult> =
        bridgeRoundTrip(
            "platform.locationCurrent",
            "{\"fineAccuracy\":${options.fineAccuracy},\"timeoutMillis\":${options.timeoutMillis}}",
        ) { json -> decodeLocationResult(json) }

    override suspend fun messageRespond(actionId: String, payload: ByteArray): Result<Unit> =
        bridgeRoundTrip(
            "platform.messageRespond",
            "{\"actionId\":${escape(actionId)},\"payload\":\"${base64(payload)}\"}",
        ) { Result.success(Unit) }

    /**
     * Phase 12 (ABI 2): stateless seek-and-read for capability
     * staged bytes. Marshals via the same bridgeCall path; host
     * returns `{bytes: base64, eof: boolean}`.
     */
    override suspend fun fileBytes(
        handle: String,
        offset: Long,
        length: Int,
    ): Result<FileBytesChunk> =
        bridgeRoundTrip(
            "platform.fileBytes",
            "{\"handle\":${escape(handle)},\"offset\":$offset,\"length\":$length}",
        ) { json ->
            if (isError(json)) {
                Result.failure(IllegalStateException(extractError(json)))
            } else {
                val bytes = pickBase64(json, "bytes") ?: ByteArray(0)
                val eof = json.contains("\"eof\":true")
                Result.success(FileBytesChunk(bytes, eof))
            }
        }

    /** Phase 12 (ABI 2): explicit handle release. Idempotent. */
    override suspend fun releaseHandle(handle: String): Result<Unit> =
        bridgeRoundTrip(
            "platform.releaseHandle",
            "{\"handle\":${escape(handle)}}",
        ) { Result.success(Unit) }

    /**
     * Suspends until the host delivers a bridge result for the
     * generated callbackId. Cancellation removes the pending
     * entry so a late result doesn't try to resume a dead
     * coroutine.
     */
    private suspend fun <T> bridgeRoundTrip(
        method: String,
        argsJson: String,
        decode: (String) -> Result<T>,
    ): Result<T> {
        val callbackId = UUID.randomUUID().toString()
        return try {
            val resultJson = suspendCancellableCoroutine<String> { cont ->
                pending[callbackId] = { json -> cont.resume(json) }
                cont.invokeOnCancellation { pending.remove(callbackId) }
                runCatching {
                    hostCallback.bridgeCall(sandboxToken, method, argsJson, callbackId)
                }.onFailure { exc ->
                    pending.remove(callbackId)
                    cont.resumeWith(Result.failure(exc))
                }
            }
            decode(resultJson)
        } catch (exc: Throwable) {
            Result.failure(exc)
        }
    }

    // ---- Encoding helpers ----

    private fun encodeNetworkRequest(req: NetworkRequest): String {
        val headers = req.headers.entries.joinToString(",") {
            "${escape(it.key)}:${escape(it.value)}"
        }
        val body = req.body?.let { "\"${base64(it)}\"" } ?: "null"
        return "{\"method\":${escape(req.method)},\"url\":${escape(req.url)}," +
            "\"headers\":{$headers},\"body\":$body,\"timeoutMillis\":${req.timeoutMillis}}"
    }

    private fun encodeNotification(n: Notification): String {
        val action = n.actionId?.let { escape(it) } ?: "null"
        return "{\"title\":${escape(n.title)},\"text\":${escape(n.text)},\"actionId\":$action}"
    }

    // ---- Decoding helpers ----
    //
    // Hand-rolled to avoid pulling Moshi/JSON into the isolated
    // sandbox module — keeps the trusted code surface small. The
    // host owns canonical JSON encoding for results.

    private fun decodeNetworkResponse(json: String): Result<NetworkResponse> {
        if (isError(json)) return Result.failure(IllegalStateException(extractError(json)))
        val status = pickInt(json, "status") ?: return Result.failure(
            IllegalStateException("malformed networkFetch result"),
        )
        val body = pickBase64(json, "body") ?: ByteArray(0)
        // Headers parsing: minimal — empty if missing.
        return Result.success(NetworkResponse(status, emptyMap(), body))
    }

    private fun decodeNullableBlob(json: String): ByteArray? {
        if (json.contains("\"value\":null")) return null
        return pickBase64(json, "value")
    }

    private fun decodeStringList(json: String): List<String> {
        // Triad 136 fix (gemini): walk the array respecting JSON
        // string escapes. Naive split(',') shreds strings that
        // contain commas (file paths, user-defined prefixes, etc.).
        // Format expected: `"items":["a","b\"with quotes",...]`.
        val key = "\"items\":["
        val start = json.indexOf(key)
        if (start < 0) return emptyList()
        var i = start + key.length
        val items = mutableListOf<String>()
        while (i < json.length) {
            // Skip whitespace / commas between items.
            while (i < json.length && (json[i] == ' ' || json[i] == ',')) i++
            if (i < json.length && json[i] == ']') return items
            if (i >= json.length || json[i] != '"') return items
            i++ // consume opening quote
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                if (c == '"') {
                    items.add(sb.toString())
                    i++
                    break
                }
                if (c == '\\' && i + 1 < json.length) {
                    when (val n = json[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        else -> {
                            sb.append(c); sb.append(n)
                        }
                    }
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
        }
        return items
    }

    private fun decodeCameraResult(json: String): Result<CameraResult> {
        if (isError(json)) return Result.failure(IllegalStateException(extractError(json)))
        val handle = decodeHandle(json) ?: return Result.failure(
            IllegalStateException("malformed cameraCapture result"),
        )
        return Result.success(CameraResult(handle))
    }

    private fun decodeGalleryResult(json: String): Result<GalleryResult> {
        if (isError(json)) return Result.failure(IllegalStateException(extractError(json)))
        // Phase 12 (ABI 2): items is an array of handle objects.
        // Minimal V0.1 decode — single-item case for now; multi-
        // item decode pending a real JSON parser.
        val single = decodeHandle(json)
        return Result.success(GalleryResult(if (single != null) listOf(single) else emptyList()))
    }

    private fun decodeFileResult(json: String): Result<FileResult> {
        if (isError(json)) return Result.failure(IllegalStateException(extractError(json)))
        val handle = decodeHandle(json) ?: return Result.failure(
            IllegalStateException("malformed filePick result"),
        )
        return Result.success(FileResult(handle))
    }

    private fun decodeLocationResult(json: String): Result<LocationResult> {
        if (isError(json)) return Result.failure(IllegalStateException(extractError(json)))
        val lat = pickDouble(json, "latitude") ?: return Result.failure(
            IllegalStateException("malformed location result"),
        )
        val lon = pickDouble(json, "longitude") ?: return Result.failure(
            IllegalStateException("malformed location result"),
        )
        val acc = pickDouble(json, "accuracyMeters")?.toFloat() ?: 0f
        val precision = pickString(json, "precision") ?: "coarse"
        return Result.success(LocationResult(lat, lon, acc, precision))
    }

    /** Phase 12 ABI 2 capability-handle decode. */
    private fun decodeHandle(json: String): CapabilityHandle? {
        val handle = pickString(json, "handle") ?: return null
        val name = pickString(json, "name") ?: ""
        val mime = pickString(json, "mime") ?: "application/octet-stream"
        val sizeBytes = (pickInt(json, "sizeBytes")?.toLong()) ?: 0L
        val expiresAtMs = (pickInt(json, "expiresAtMs")?.toLong()) ?: 0L
        return CapabilityHandle(
            handle = handle,
            name = name,
            mime = mime,
            sizeBytes = sizeBytes,
            expiresAtMs = expiresAtMs,
        )
    }

    // ---- JSON micro-helpers ----

    private fun isError(json: String): Boolean = json.contains("\"error\":")

    private fun extractError(json: String): String =
        pickString(json, "error") ?: "unknown error"

    private fun pickString(json: String, key: String): String? {
        // Triad 136 fix (gemini): respect JSON escapes. Previous
        // impl stopped at the first `"` it found, which truncated
        // strings containing `\"`. Walks the value respecting `\"`,
        // `\\`, and the common `\X` letter escapes. \uXXXX is left
        // as literal bytes (host doesn't currently emit them).
        val k = "\"$key\":\""
        val start = json.indexOf(k)
        if (start < 0) return null
        var i = start + k.length
        val out = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '"') return out.toString()
            if (c == '\\' && i + 1 < json.length) {
                when (val n = json[i + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('')
                    else -> {
                        out.append(c)
                        out.append(n)
                    }
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return null
    }

    private fun pickInt(json: String, key: String): Int? {
        val k = "\"$key\":"
        val start = json.indexOf(k)
        if (start < 0) return null
        val from = start + k.length
        var end = from
        while (end < json.length && (json[end].isDigit() || json[end] == '-')) end++
        if (end == from) return null
        return json.substring(from, end).toIntOrNull()
    }

    private fun pickDouble(json: String, key: String): Double? {
        val k = "\"$key\":"
        val start = json.indexOf(k)
        if (start < 0) return null
        val from = start + k.length
        var end = from
        while (end < json.length && (json[end].isDigit() || json[end] == '.' || json[end] == '-')) end++
        if (end == from) return null
        return json.substring(from, end).toDoubleOrNull()
    }

    private fun pickBase64(json: String, key: String): ByteArray? {
        val s = pickString(json, key) ?: return null
        return runCatching { android.util.Base64.decode(s, android.util.Base64.NO_WRAP) }
            .getOrNull()
    }

    private fun escape(s: String): String {
        // Triad 136 fix (codex + gemini): escape ALL control chars
        // (U+0000..U+001F). Plugin-supplied strings reach this
        // function via PluginContext.storage.set(key=...),
        // showNotification(title=...), etc. A `\b` / `\f` / NUL
        // in plugin input would emit invalid JSON the host's
        // parser would reject — at best a Result.failure, at worst
        // a malformed request the host had to defend against.
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '' -> out.append("\\f")
            else -> if (c.code < 0x20) {
                out.append("\\u").append("%04x".format(c.code))
            } else {
                out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    companion object {
        private const val TAG = "PluginNativeBridge"
    }
}
