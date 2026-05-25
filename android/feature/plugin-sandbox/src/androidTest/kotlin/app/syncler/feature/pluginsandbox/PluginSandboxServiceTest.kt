package app.syncler.feature.pluginsandbox

import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import app.syncler.core.pluginaidl.IPluginHostCallback
import app.syncler.core.pluginaidl.IPluginSandbox
import app.syncler.core.pluginaidl.PluginLoadParcel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 10b step 5c — e2e harness for [PluginSandboxService].
 *
 * Binds the real service in the `:plugin` subprocess via
 * [ServiceTestRule] and exercises the AIDL contract end-to-end:
 * parcel marshalling, error-code propagation across Binder,
 * callback round-trip, [IPluginSandbox.querySandboxState].
 *
 * Scope deliberately stops short of asserting an
 * `onPluginReady` round-trip from JS — the real
 * [RealPluginWebViewHost] in step 5a doesn't yet wire the
 * "plugin reported ready" signal back to the coordinator
 * (`coordinator.reportReady()` is unreachable from the
 * production WebView path today). Step 5d closes that gap;
 * once it lands, this test will be extended with a
 * load → ready → dispatchHook → bridgeCall → unload assertion.
 *
 * Cross-process note: [WebViewHostFactoryOverride] is a
 * test-process static and is NOT visible inside `:plugin`, so we
 * cannot swap in a recording host from here. The trade-off is
 * deliberate — this test verifies the AIDL boundary against the
 * production [RealPluginWebViewHostFactory] rather than a fake.
 */
@RunWith(AndroidJUnit4::class)
class PluginSandboxServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var sandbox: IPluginSandbox
    private lateinit var stagingDir: File

    @Before
    fun bindSandbox() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(ctx, PluginSandboxService::class.java)
        val binder: IBinder = serviceRule.bindService(intent)
        sandbox = IPluginSandbox.Stub.asInterface(binder)

        // The bundle file must be readable by the :plugin process.
        // Same UID + same data dir → noBackupFilesDir works.
        stagingDir = File(ctx.noBackupFilesDir, "plugin-sandbox-androidtest").apply {
            if (!exists()) mkdirs()
        }
    }

    @Test
    fun querySandboxState_unknownToken_returnsUnknown() {
        assertEquals("unknown", sandbox.querySandboxState(/* sandboxToken = */ 999_999))
    }

    @Test
    fun loadPlugin_blankPluginId_throwsParcelMalformed() {
        val parcel = sampleParcel(
            sandboxToken = 1001,
            pluginId = "",
            bundlePath = stageBundle("noop-1001.js", "// noop").absolutePath,
            bundleHash = sha256Hex("// noop".toByteArray()),
        )
        val callback = RecordingHostCallback()
        val exc = assertThrowsIllegalState {
            sandbox.loadPlugin(parcel, callback)
        }
        assertEquals(LoadFailureCodes.PARCEL_MALFORMED, exc.message)
    }

    @Test
    fun loadPlugin_oversizeDiagnostic_throwsDiagnosticFieldOversize() {
        val parcel = sampleParcel(
            sandboxToken = 1002,
            pluginId = "plugin-oversize",
            bundlePath = stageBundle("noop-1002.js", "// noop").absolutePath,
            bundleHash = sha256Hex("// noop".toByteArray()),
            diagnosticManifestJson = "x".repeat(PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP + 1),
        )
        val callback = RecordingHostCallback()
        val exc = assertThrowsIllegalState {
            sandbox.loadPlugin(parcel, callback)
        }
        assertEquals(LoadFailureCodes.DIAGNOSTIC_FIELD_OVERSIZE, exc.message)
    }

    @Test
    fun loadPlugin_hashMismatch_throwsBundleHashMismatch() {
        val parcel = sampleParcel(
            sandboxToken = 1003,
            pluginId = "plugin-bad-hash",
            bundlePath = stageBundle("real-1003.js", "// real bundle").absolutePath,
            bundleHash = sha256Hex("// different bundle".toByteArray()),
        )
        val callback = RecordingHostCallback()
        val exc = assertThrowsIllegalState {
            sandbox.loadPlugin(parcel, callback)
        }
        assertEquals(LoadFailureCodes.BUNDLE_HASH_MISMATCH, exc.message)
    }

    @Test
    fun loadPlugin_unsupportedRenderer_throwsUnsupportedRenderer() {
        val js = "// renderer test"
        val parcel = sampleParcel(
            sandboxToken = 1004,
            pluginId = "plugin-template",
            bundlePath = stageBundle("renderer-1004.js", js).absolutePath,
            bundleHash = sha256Hex(js.toByteArray()),
            renderer = "template",
        )
        val callback = RecordingHostCallback()
        val exc = assertThrowsIllegalState {
            sandbox.loadPlugin(parcel, callback)
        }
        assertEquals(LoadFailureCodes.UNSUPPORTED_RENDERER, exc.message)
    }

    @Test
    fun loadPlugin_thenUnload_firesOnPluginUnloadedAcrossProcesses() {
        val js = "// load/unload e2e"
        val token = 1005
        val parcel = sampleParcel(
            sandboxToken = token,
            pluginId = "plugin-unload-e2e",
            bundlePath = stageBundle("e2e-1005.js", js).absolutePath,
            bundleHash = sha256Hex(js.toByteArray()),
        )
        val callback = RecordingHostCallback()

        val returnedToken = sandbox.loadPlugin(parcel, callback)
        assertEquals(token, returnedToken)
        // Service is now in LOADING. WebView creation is posted to
        // :plugin's main looper; we don't wait for it.
        assertTrue(
            "expected loading/ready, got ${sandbox.querySandboxState(token)}",
            sandbox.querySandboxState(token) in setOf("loading", "ready"),
        )

        sandbox.unloadPlugin(token)
        // oneway — wait for the callback to fire across processes.
        assertTrue(
            "onPluginUnloaded did not fire within timeout",
            callback.unloaded.await(5, TimeUnit.SECONDS),
        )
        assertEquals(token, callback.unloadedToken)
        // After unload, the token is removed from the registry.
        assertEquals("unknown", sandbox.querySandboxState(token))
    }

    @Test
    fun loadPlugin_sameTokenTwice_throwsConcurrentLoadInProgress() {
        val js = "// concurrent load"
        val token = 1006
        val first = sampleParcel(
            sandboxToken = token,
            pluginId = "plugin-concurrent",
            bundlePath = stageBundle("concurrent-1006.js", js).absolutePath,
            bundleHash = sha256Hex(js.toByteArray()),
        )
        val firstCallback = RecordingHostCallback()
        sandbox.loadPlugin(first, firstCallback)

        try {
            val second = first.copy(pluginId = "plugin-concurrent-2")
            val secondCallback = RecordingHostCallback()
            val exc = assertThrowsIllegalState {
                sandbox.loadPlugin(second, secondCallback)
            }
            assertEquals(LoadFailureCodes.CONCURRENT_LOAD_IN_PROGRESS, exc.message)
        } finally {
            sandbox.unloadPlugin(token)
            firstCallback.unloaded.await(5, TimeUnit.SECONDS)
        }
    }

    private fun stageBundle(name: String, contents: String): File {
        val file = File(stagingDir, name)
        file.writeText(contents)
        return file
    }

    private fun sampleParcel(
        sandboxToken: Int,
        pluginId: String,
        bundlePath: String,
        bundleHash: String,
        renderer: String = "script",
        diagnosticManifestJson: String = "{}",
    ): PluginLoadParcel = PluginLoadParcel(
        sandboxToken = sandboxToken,
        pluginId = pluginId,
        pluginIdentifier = "com.example.$pluginId",
        version = "1.0.0",
        renderer = renderer,
        bundleFilePath = bundlePath,
        bundleHashHex = bundleHash,
        declaredCapabilities = emptyList(),
        declaredEndpoints = emptyList(),
        dismissBehavior = "ack",
        timeoutMillis = 1_000L,
        diagnosticManifestJson = diagnosticManifestJson,
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private inline fun assertThrowsIllegalState(block: () -> Unit): IllegalStateException {
        try {
            block()
            fail("expected IllegalStateException, got no exception")
            error("unreachable")
        } catch (e: IllegalStateException) {
            return e
        }
    }

    /**
     * Host-process binder stub for [IPluginHostCallback]. The
     * sandbox in `:plugin` invokes these via Binder; we record on
     * [CountDownLatch]s the test can await.
     */
    private class RecordingHostCallback : IPluginHostCallback.Stub() {
        val ready = CountDownLatch(1)
        @Volatile var readyToken: Int = -1
        val unloaded = CountDownLatch(1)
        @Volatile var unloadedToken: Int = -1
        val crashed = CountDownLatch(1)
        @Volatile var crashReason: String = ""
        val bridgeCalls = mutableListOf<String>()

        override fun bridgeCall(
            sandboxToken: Int, method: String?, argsJson: String?, callbackId: String?,
        ) {
            synchronized(bridgeCalls) {
                bridgeCalls.add("bridgeCall($sandboxToken, $method, $argsJson, $callbackId)")
            }
        }

        override fun onWebViewError(sandboxToken: Int, code: String?, message: String?) {
            // Recorded but not asserted on in step 5c tests.
        }

        override fun onPluginReady(sandboxToken: Int) {
            readyToken = sandboxToken
            ready.countDown()
        }

        override fun onPluginCrashed(sandboxToken: Int, reason: String?) {
            crashReason = reason.orEmpty()
            crashed.countDown()
        }

        override fun onPluginUnloaded(sandboxToken: Int) {
            unloadedToken = sandboxToken
            unloaded.countDown()
        }
    }
}
