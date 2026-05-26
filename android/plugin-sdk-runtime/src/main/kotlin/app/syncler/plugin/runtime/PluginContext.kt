package app.syncler.plugin.runtime

/**
 * Typed Kotlin facade over the same capability dispatch path JS
 * plugins use via `platform.*` (Phase 11).
 *
 * Each method here marshals to the host's `PluginBridge` over
 * AIDL `bridgeCall(method, argsJson, callbackId)` and suspends
 * until the host responds via `deliverBridgeResult`. The host
 * enforces grants, rate limits, and audit logging exactly as it
 * does for the JS bridge — see `docs/plugin-host-multi-process.md`
 * "Bridge call attribution" and Phase 10's `PluginBridge`.
 *
 * Implementations live in the sandbox process; this interface is
 * the public contract plugin code links against (`compileOnly` in
 * the plugin's build).
 */
interface PluginContext {
    val pluginId: String

    /**
     * Capabilities the host has granted this plugin row. Each
     * `platform.*` call below silently fails (its [Result]
     * resolves to a structured `Result.failure`) if the
     * corresponding capability isn't in this set — the sandbox
     * mirrors the same grant check the host performs, so a
     * plugin can short-circuit without an extra Binder hop.
     */
    val grantedCapabilities: Set<String>

    suspend fun networkFetch(request: NetworkRequest): Result<NetworkResponse>

    suspend fun storage(): PluginStorage

    suspend fun showNotification(notification: Notification): Result<Unit>

    suspend fun cameraCapture(options: CameraOptions): Result<CameraResult>

    suspend fun galleryPick(options: GalleryOptions): Result<GalleryResult>

    suspend fun filePick(options: FileOptions): Result<FileResult>

    suspend fun locationCurrent(options: LocationOptions): Result<LocationResult>

    /**
     * Replies to an inbox message that previously invoked this
     * plugin. The host correlates [actionId] with the originating
     * `message_id` from its own state — the plugin doesn't see
     * `message_id` directly so it can't fabricate responses for
     * messages it didn't handle.
     */
    suspend fun messageRespond(actionId: String, payload: ByteArray): Result<Unit>

    /**
     * Phase 12 (ABI 2): read bytes from a capability handle.
     * Stateless seek-and-read — each call independently seeks
     * `offset` bytes in and reads up to `length` (max 256 KB).
     * Returns the bytes plus an `eof` flag once the end of the
     * staged file is reached. Repeated reads at the same offset
     * return identical bytes until the handle is released or
     * expires.
     */
    suspend fun fileBytes(
        handle: String,
        offset: Long = 0L,
        length: Int = 65_536,
    ): Result<FileBytesChunk>

    /**
     * Phase 12 (ABI 2): release a capability handle and its
     * underlying staging file. Idempotent — releasing an unknown
     * or already-released handle succeeds. The host also wipes
     * handles on plugin unload + 5-minute TTL, but explicit
     * release is faster.
     */
    suspend fun releaseHandle(handle: String): Result<Unit>
}

data class FileBytesChunk(val bytes: ByteArray, val eof: Boolean)

/**
 * Host-owned key/value store scoped to the plugin's
 * `(sender_id, plugin_identifier)`. Backed by the host's
 * audited storage capability — quotas + GC are host-side
 * concerns the plugin doesn't see.
 */
interface PluginStorage {
    suspend fun get(key: String): Result<ByteArray?>
    suspend fun set(key: String, value: ByteArray): Result<Unit>
    suspend fun delete(key: String): Result<Unit>
    suspend fun list(prefix: String): Result<List<String>>
}

data class NetworkRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMillis: Long = 30_000L,
)

data class NetworkResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

data class Notification(
    val title: String,
    val text: String,
    val actionId: String? = null,
)

/**
 * Phase 12 (V2 #10, ABI 2): capability-handle metadata. The
 * bridge returns ONE of these per camera capture / file pick /
 * gallery item — the plugin reads bytes via
 * [PluginContext.fileBytes] and releases via
 * [PluginContext.releaseHandle].
 *
 * Inline byte transfer (the ABI 1 shape with `bytes: ByteArray`
 * directly on the result) is gone because realistic image / file
 * results easily exceed Binder's ~1 MB transaction limit.
 *
 * `expiresAtMs` is a wall-clock value for display only. The host
 * enforces validity via SystemClock.elapsedRealtime so a system
 * time change can't extend a handle's life.
 */
data class CapabilityHandle(
    val handle: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val expiresAtMs: Long,
)

data class CameraOptions(val front: Boolean = false)
data class CameraResult(val handle: CapabilityHandle)

data class GalleryOptions(val maxItems: Int = 1, val mimeFilter: String? = null)
data class GalleryResult(val items: List<CapabilityHandle>)

data class FileOptions(val mimeFilter: String? = null)
data class FileResult(val handle: CapabilityHandle)

data class LocationOptions(val fineAccuracy: Boolean = false, val timeoutMillis: Long = 10_000L)

/**
 * Phase 12 (ABI 2): `precision` reflects what the OS actually
 * granted, not what the plugin asked for. A plugin requesting
 * `fine` whose user chose "approximate location" sees
 * `precision = "coarse"` here. Plugins SHOULD branch on this
 * field rather than assuming their requested accuracy.
 */
data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val precision: String, // "coarse" or "fine"
)

data class InboxEvent(
    val messageId: String,
    val payloadJson: String,
    val receivedAtMillis: Long,
)

data class ActionEvent(
    val actionId: String,
    val payloadJson: String,
)
