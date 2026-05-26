package app.syncler.android.pluginhost.capabilities

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Phase 12 (V2 #10) — file-backed staging for camera / gallery /
 * file capability results.
 *
 * The bridge stages the picked / captured bytes into
 * `cacheDir/plugin-capability/<sandboxToken>/{callId}.bin`,
 * indexed in memory by an opaque handle string. Plugin reads via
 * `platform.fileBytes(handle, offset, length)` (stateless seek
 * + read). On unload / expiry / explicit release the file is
 * wiped.
 *
 * Per `docs/plugin-capability-expansion.md` "Binary transport
 * (handles)" and the v3 spec's TTL-via-elapsedRealtime decision
 * (triad 138 B): `expiresAtElapsedMs` drives validity checks,
 * the wall-clock `expiresAtMs` is for plugin display only.
 *
 * Caps (spec):
 *  - Max size per handle: 16 MB
 *  - Max concurrent handles per plugin: 32
 *  - TTL: 5 minutes from creation
 *  - Per-call chunk read cap: 256 KB
 */
class CapabilityHandleStore(private val context: Context) {

    private val rootDir: File by lazy {
        File(context.applicationContext.cacheDir, "plugin-capability").apply {
            if (!exists()) mkdirs()
        }
    }

    private val handles = ConcurrentHashMap<String, Entry>()
    private val perPluginCount = ConcurrentHashMap<String, Int>()
    private val mutex = Mutex()

    /**
     * On app start the host wipes the entire staging dir — process
     * death lost any in-flight handles anyway. Call from
     * Application.onCreate or PluginRegistry.init.
     */
    fun wipeAll() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
            rootDir.mkdirs()
        }
        handles.clear()
        perPluginCount.clear()
    }

    /**
     * Plugin unload (`onPluginUnloaded`) wipes that token's
     * subdir + all handles it owned. Per-token sub-cleanup avoids
     * killing live handles from other plugins sharing the cache.
     */
    suspend fun wipeForToken(sandboxToken: Int): Unit = mutex.withLock {
        val tokenDir = File(rootDir, sandboxToken.toString())
        if (tokenDir.exists()) tokenDir.deleteRecursively()
        val gone = handles.entries.filter { it.value.sandboxToken == sandboxToken }
            .map { it.key }
        gone.forEach { handles.remove(it) }
        // Recompute per-plugin counts; cheaper than removing tokens
        // one by one since unload is rare.
        rebuildPerPluginCounts()
    }

    /**
     * Stage [bytes] into a fresh handle for the given plugin /
     * sandbox token. Throws on cap violations:
     *  - `result_too_large` if bytes > 16 MB.
     *  - `too_many_handles` if the plugin already holds the cap.
     */
    suspend fun stage(
        pluginRowId: String,
        sandboxToken: Int,
        callId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
    ): CapabilityHandleMetadata = mutex.withLock {
        if (bytes.size > MAX_BYTES) throw IllegalStateException("result_too_large")
        val current = perPluginCount.getOrDefault(pluginRowId, 0)
        if (current >= MAX_HANDLES_PER_PLUGIN) {
            throw IllegalStateException("too_many_handles")
        }

        val tokenDir = File(rootDir, sandboxToken.toString()).apply {
            if (!exists()) mkdirs()
        }
        val handle = "cap-" + UUID.randomUUID().toString()
        val file = File(tokenDir, "$callId.bin")
        file.writeBytes(bytes)

        val createdElapsedMs = SystemClock.elapsedRealtime()
        val expiresAtElapsedMs = createdElapsedMs + TTL_MS
        val createdWallMs = System.currentTimeMillis()
        val expiresAtMs = createdWallMs + TTL_MS

        handles[handle] = Entry(
            handle = handle,
            pluginRowId = pluginRowId,
            sandboxToken = sandboxToken,
            file = file,
            name = name,
            mime = mime,
            sizeBytes = bytes.size.toLong(),
            createdElapsedMs = createdElapsedMs,
            expiresAtElapsedMs = expiresAtElapsedMs,
            expiresAtMs = expiresAtMs,
        )
        perPluginCount[pluginRowId] = current + 1
        return CapabilityHandleMetadata(
            handle = handle,
            name = name,
            mime = mime,
            sizeBytes = bytes.size.toLong(),
            expiresAtMs = expiresAtMs,
        )
    }

    /**
     * Stateless seek-and-read per triad 138 C. Each call opens
     * RandomAccessFile, seeks, reads, closes. No cursor state.
     *
     * Returns null on `invalid_handle` conditions: unknown handle,
     * expired handle, oversize length, negative offset/length,
     * scope mismatch (plugin A trying plugin B's handle).
     */
    fun read(
        pluginRowId: String,
        handle: String,
        offset: Long,
        length: Int,
    ): ReadResult? {
        if (offset < 0 || length < 0 || length > MAX_CHUNK_BYTES) return null
        val entry = handles[handle] ?: return null
        if (entry.pluginRowId != pluginRowId) return null
        if (SystemClock.elapsedRealtime() > entry.expiresAtElapsedMs) {
            // Lazy expiry — sweep on read.
            handles.remove(handle)
            decrementPlugin(entry.pluginRowId)
            entry.file.delete()
            return null
        }
        if (offset >= entry.sizeBytes) return ReadResult(ByteArray(0), eof = true)
        val toRead = minOf(length.toLong(), entry.sizeBytes - offset).toInt()
        val buf = ByteArray(toRead)
        RandomAccessFile(entry.file, "r").use { raf ->
            raf.seek(offset)
            var read = 0
            while (read < toRead) {
                val n = raf.read(buf, read, toRead - read)
                if (n < 0) break
                read += n
            }
        }
        val eof = offset + toRead >= entry.sizeBytes
        return ReadResult(buf, eof = eof)
    }

    /** Plugin's explicit release — wipes the file + handle. */
    suspend fun release(pluginRowId: String, handle: String): Boolean = mutex.withLock {
        val entry = handles[handle] ?: return@withLock false
        if (entry.pluginRowId != pluginRowId) return@withLock false
        handles.remove(handle)
        decrementPlugin(entry.pluginRowId)
        entry.file.delete()
        true
    }

    /**
     * Periodic sweep — call from a 60s tick to wipe expired
     * handles even if the plugin never reads them. Returns the
     * count of handles released.
     */
    suspend fun sweepExpired(): Int = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val expired = handles.entries.filter { it.value.expiresAtElapsedMs < now }
            .map { it.key }
        expired.forEach { key ->
            val e = handles.remove(key)
            if (e != null) {
                decrementPlugin(e.pluginRowId)
                e.file.delete()
            }
        }
        expired.size
    }

    private fun decrementPlugin(pluginRowId: String) {
        perPluginCount.compute(pluginRowId) { _, v ->
            val next = (v ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    private fun rebuildPerPluginCounts() {
        perPluginCount.clear()
        handles.values.forEach { e ->
            perPluginCount.merge(e.pluginRowId, 1) { a, b -> a + b }
        }
    }

    /** For unit-test injection of a clock; default uses real elapsed. */
    private data class Entry(
        val handle: String,
        val pluginRowId: String,
        val sandboxToken: Int,
        val file: File,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
        val createdElapsedMs: Long,
        val expiresAtElapsedMs: Long,
        val expiresAtMs: Long,
    )

    companion object {
        const val MAX_BYTES: Long = 16L * 1024 * 1024
        const val MAX_HANDLES_PER_PLUGIN = 32
        const val MAX_CHUNK_BYTES = 256 * 1024
        const val TTL_MS: Long = 5L * 60 * 1000
    }
}

/** Plugin-facing handle metadata. */
data class CapabilityHandleMetadata(
    val handle: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    /**
     * Wall-clock ms; display-only. Validity is enforced
     * internally via elapsedRealtime so timezone shifts /
     * manual clock changes can't extend a handle's life.
     */
    val expiresAtMs: Long,
)

/** Bridge return for [CapabilityHandleStore.read]. */
data class ReadResult(val bytes: ByteArray, val eof: Boolean)
