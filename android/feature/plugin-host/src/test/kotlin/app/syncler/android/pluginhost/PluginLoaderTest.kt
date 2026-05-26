package app.syncler.android.pluginhost

import app.syncler.core.crypto.Signing
import app.syncler.core.crypto.toHex
import com.squareup.moshi.Moshi
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginLoaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val seed = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100".let { hex ->
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private val publicKey = Signing.publicKeyFromSeed(seed)
    private val verifier = PluginSignatureVerifier()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun roundTripSignVerifyAndLoadTinyPlugin() = runTest {
        val bundle = minimalPluginBundle()
        val manifestJson = signedManifestJson(bundle)
        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(bundle))

        val result = loader().load(server.url("/manifest.json").toString(), publicKey)

        assertTrue(result.isSuccess)
        val instance = result.getOrThrow()
        assertEquals("app.syncler.testplugin", instance.manifest.id)
        assertEquals(setOf("network", "storage"), instance.grantedCapabilities)
        assertTrue(File(instance.bundleFilePath).readText() == bundle)
    }

    @Test
    fun rejectsTamperedManifestBeforeBundleDownload() = runTest {
        val bundle = minimalPluginBundle()
        val tampered = signedManifestJson(bundle).replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.1\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody(tampered))

        val result = loader().load(server.url("/manifest.json").toString(), publicKey)

        assertFalse(result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsBundleHashMismatch() = runTest {
        val signedFor = "expected bundle"
        val served = "tampered bundle"
        server.enqueue(MockResponse().setResponseCode(200).setBody(signedManifestJson(signedFor)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(served))

        val result = loader().load(server.url("/manifest.json").toString(), publicKey)

        assertFalse(result.isSuccess)
        assertEquals(2, server.requestCount)
    }

    private fun loader(): PluginLoader = PluginLoader(
        httpClient = OkHttpClient(),
        verifier = verifier,
        permissionReader = { setOf("network", "storage", "camera") },
        bundleStore = object : PluginBundleStore {
            override fun write(pluginId: String, bundleBytes: ByteArray): File =
                temp.newFile("$pluginId.bundle.js").also { it.writeBytes(bundleBytes) }
        },
        instanceFactory = object : PluginInstanceFactory {
            override suspend fun create(
                manifest: PluginManifest,
                grantedCapabilities: Set<String>,
                bundleFilePath: String,
                bundleBytes: ByteArray,
                manifestJson: String,
                pluginRowId: String,
            ): PluginInstance = PluginInstance(manifest, grantedCapabilities, bundleFilePath, pluginRowId = pluginRowId)
        },
    )

    private fun signedManifestJson(bundle: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bundle.toByteArray()).toHex()
        val unsigned = linkedMapOf<String, Any?>(
            "id" to "app.syncler.testplugin",
            "name" to "Tiny Test Plugin",
            "version" to "1.0.0",
            "senderId" to "sender-alpha",
            "bundleHash" to hash,
            "declaredCapabilities" to listOf("network", "storage"),
            "declaredEndpoints" to listOf("https://api.example.com/v1/*"),
            "dismissBehavior" to "dismiss_local_only",
            "minPlatformVersion" to "1.0.0",
            "signed_bundle_url" to server.url("/plugin.bundle.js").toString(),
        )
        val signature = Signing.signWithSeed(seed, verifier.canonicalManifestForSigning(unsigned)).toHex()
        return Moshi.Builder().build().adapter(Map::class.java).toJson(unsigned + ("signature" to signature))
    }

    private fun minimalPluginBundle(): String =
        checkNotNull(javaClass.getResource("/minimal-plugin.bundle.js")).readText()
}
