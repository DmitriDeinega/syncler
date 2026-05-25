package app.syncler.core.pluginaidl

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Parcelable round-trip — host writes, sandbox reads via Parcel.
 *
 * Marked @Ignore because the parcel-round-trip needs an actual
 * Android `Parcel` instance (host APIs are Android-only) — this
 * test ships with the module so it can run under
 * `connectedAndroidTest` in Phase 10b step 5, but unit-test
 * scope on the JVM can't instantiate Parcel.
 *
 * Kept here as the contract assertion: any field reordering /
 * removal MUST keep this test passing.
 */
class PluginLoadParcelTest {

    private val sample = PluginLoadParcel(
        sandboxToken = 42,
        pluginId = "plugin-abc",
        pluginIdentifier = "com.example.weather",
        version = "1.2.3",
        renderer = "script",
        bundleFilePath = "/data/.../plugin-sandbox/plugin-abc_42/bundle.js",
        bundleHashHex = "deadbeef".repeat(8),
        declaredCapabilities = listOf("network", "storage"),
        declaredEndpoints = listOf("https://example.com/*"),
        dismissBehavior = "ack",
        timeoutMillis = 5_000L,
        diagnosticManifestJson = "{\"id\":\"plugin-abc\"}",
    )

    @Test
    @Ignore("requires android.os.Parcel — runs under connectedAndroidTest in Phase 10b step 5")
    fun roundTripPreservesEveryField() {
        val parcel = Parcel.obtain()
        try {
            sample.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = PluginLoadParcel.CREATOR.createFromParcel(parcel)
            assertEquals(sample, restored)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun diagnosticManifestCapIsAReasonableSize() {
        // Sanity that the documented cap matches the constant.
        assertEquals(64 * 1024, PluginLoadParcel.DIAGNOSTIC_MANIFEST_BYTES_CAP)
        assertTrue(PluginLoadParcel.WIRE_VERSION >= 1)
    }
}
