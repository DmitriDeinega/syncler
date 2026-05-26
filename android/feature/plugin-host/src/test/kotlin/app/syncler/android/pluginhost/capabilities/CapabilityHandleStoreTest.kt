package app.syncler.android.pluginhost.capabilities

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 12 (V2 #10) tests for [CapabilityHandleStore].
 *
 * The store is mostly file I/O so unit tests run against a real
 * temp directory wrapped in a mocked Android Context. No need
 * for Robolectric or instrumented tests — the host-side behavior
 * (stage / read / release / sweep) is testable in pure JVM.
 */
class CapabilityHandleStoreTest {

    private lateinit var tempCacheDir: File
    private lateinit var store: CapabilityHandleStore

    @Before
    fun setUp() {
        tempCacheDir = File.createTempFile("syncler-cap-test-", ".dir").apply {
            delete()
            mkdirs()
        }
        store = CapabilityHandleStore(FakeContext(tempCacheDir))
    }

    @After
    fun tearDown() {
        tempCacheDir.deleteRecursively()
    }

    /**
     * Minimal fake Context that returns only `cacheDir` and
     * `applicationContext` (returning itself). Everything else
     * throws — keeps the surface small and the test pure JVM.
     */
    private class FakeContext(private val cache: File) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = cache
    }

    @Test
    fun `stage and read round-trips bytes`() = runBlocking {
        val bytes = "hello world".toByteArray()
        val handle = store.stage(
            pluginRowId = "plugin-1",
            sandboxToken = 100,
            callId = "call-1",
            bytes = bytes,
            name = "test.txt",
            mime = "text/plain",
        )
        val result = store.read("plugin-1", handle.handle, offset = 0L, length = 1024)
        assertNotNull(result)
        assertArrayEquals(bytes, result!!.bytes)
        assertTrue(result.eof)
    }

    @Test
    fun `read respects offset and length for partial chunks`() = runBlocking {
        val bytes = ByteArray(1000) { (it % 256).toByte() }
        val handle = store.stage(
            pluginRowId = "plugin-1",
            sandboxToken = 100,
            callId = "call-1",
            bytes = bytes,
            name = "data.bin",
            mime = "application/octet-stream",
        )
        val mid = store.read("plugin-1", handle.handle, offset = 100L, length = 50)
        assertNotNull(mid)
        assertEquals(50, mid!!.bytes.size)
        assertFalse(mid.eof)
        // Verify content matches the slice.
        assertArrayEquals(bytes.copyOfRange(100, 150), mid.bytes)
    }

    @Test
    fun `read returns eof and empty bytes past end`() = runBlocking {
        val bytes = "abc".toByteArray()
        val handle = store.stage(
            "plugin-1", 100, "call-1", bytes, "tiny.txt", "text/plain",
        )
        val past = store.read("plugin-1", handle.handle, offset = 100L, length = 50)
        assertNotNull(past)
        assertEquals(0, past!!.bytes.size)
        assertTrue(past.eof)
    }

    @Test
    fun `read rejects negative offset`() = runBlocking {
        val handle = store.stage(
            "plugin-1", 100, "call-1", "x".toByteArray(), "x.txt", "text/plain",
        )
        assertNull(store.read("plugin-1", handle.handle, offset = -1L, length = 10))
    }

    @Test
    fun `read rejects oversize chunk request`() = runBlocking {
        val handle = store.stage(
            "plugin-1", 100, "call-1", "x".toByteArray(), "x.txt", "text/plain",
        )
        val tooBig = CapabilityHandleStore.MAX_CHUNK_BYTES + 1
        assertNull(store.read("plugin-1", handle.handle, offset = 0L, length = tooBig))
    }

    @Test
    fun `read rejects cross-plugin access (handle scoping)`() = runBlocking {
        val handle = store.stage(
            "plugin-A", 100, "call-1", "secret".toByteArray(), "s.txt", "text/plain",
        )
        // Plugin B knows the handle string but should NOT read it.
        assertNull(store.read("plugin-B", handle.handle, offset = 0L, length = 100))
    }

    @Test
    fun `release wipes file and frees per-plugin slot`() = runBlocking {
        val handle = store.stage(
            "plugin-1", 100, "call-1", "data".toByteArray(), "d.txt", "text/plain",
        )
        val released = store.release("plugin-1", handle.handle)
        assertTrue(released)
        assertNull(store.read("plugin-1", handle.handle, offset = 0L, length = 100))
    }

    @Test
    fun `release wrong plugin returns false`() = runBlocking {
        val handle = store.stage(
            "plugin-A", 100, "call-1", "data".toByteArray(), "d.txt", "text/plain",
        )
        assertFalse(store.release("plugin-B", handle.handle))
        // Original handle still readable.
        assertNotNull(store.read("plugin-A", handle.handle, offset = 0L, length = 100))
    }

    @Test
    fun `wipeForToken removes that token's handles only`() = runBlocking {
        val h1 = store.stage("plugin-1", 100, "c1", "a".toByteArray(), "a.txt", "text/plain")
        val h2 = store.stage("plugin-2", 200, "c2", "b".toByteArray(), "b.txt", "text/plain")

        store.wipeForToken(100)

        assertNull(store.read("plugin-1", h1.handle, 0L, 10))
        assertNotNull(store.read("plugin-2", h2.handle, 0L, 10))
    }

    @Test
    fun `wipeAll clears entire directory`() = runBlocking {
        store.stage("plugin-1", 100, "c1", "a".toByteArray(), "a.txt", "text/plain")
        store.wipeAll()
        // Directory was recreated empty; no listing to assert via API, but
        // re-staging into the same token should succeed.
        val fresh = store.stage("plugin-1", 100, "c2", "b".toByteArray(), "b.txt", "text/plain")
        assertNotNull(fresh.handle)
    }

    @Test
    fun `per-plugin handle count cap enforced`() = runBlocking {
        // Fill to MAX_HANDLES_PER_PLUGIN.
        repeat(CapabilityHandleStore.MAX_HANDLES_PER_PLUGIN) { i ->
            store.stage("p1", 100, "c$i", byteArrayOf(i.toByte()), "x.bin", "application/octet-stream")
        }
        val beyondCap = try {
            store.stage("p1", 100, "overflow", byteArrayOf(0), "x.bin", "application/octet-stream")
            null
        } catch (exc: IllegalStateException) {
            exc.message
        }
        assertEquals("too_many_handles", beyondCap)
    }

    @Test
    fun `result_too_large rejected before staging`() = runBlocking {
        val tooBig = ByteArray(CapabilityHandleStore.MAX_BYTES.toInt() + 1)
        val msg = try {
            store.stage("p1", 100, "huge", tooBig, "huge.bin", "application/octet-stream")
            null
        } catch (exc: IllegalStateException) {
            exc.message
        }
        assertEquals("result_too_large", msg)
    }

    @Test
    fun `repeated reads at same offset return identical bytes`() = runBlocking {
        val bytes = "consistent".toByteArray()
        val handle = store.stage(
            "p1", 100, "c1", bytes, "c.txt", "text/plain",
        )
        val first = store.read("p1", handle.handle, 0L, bytes.size)!!
        val second = store.read("p1", handle.handle, 0L, bytes.size)!!
        assertArrayEquals(first.bytes, second.bytes)
    }
}
