package app.syncler.feature.pluginnativesandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift guard for the load-failure codes. Codes are part of the
 * AIDL contract — both sides of the Binder boundary depend on the
 * exact string, so a typo here would silently degrade a structured
 * error into `unknown_sandbox_error` on the host.
 *
 * Source of truth: docs/plugin-host-native-kotlin.md
 * "LoadFailureCodes (Phase 11 additions)".
 */
class NativeLoadFailureCodesTest {

    @Test
    fun `phase 11 additions match spec`() {
        assertEquals("dex_too_large", NativeLoadFailureCodes.DEX_TOO_LARGE)
        assertEquals("unsupported_sdk_abi", NativeLoadFailureCodes.UNSUPPORTED_SDK_ABI)
        assertEquals("init_timeout", NativeLoadFailureCodes.INIT_TIMEOUT)
        assertEquals(
            "forbidden_package_prefix",
            NativeLoadFailureCodes.FORBIDDEN_PACKAGE_PREFIX,
        )
        assertEquals(
            "entry_class_not_found",
            NativeLoadFailureCodes.ENTRY_CLASS_NOT_FOUND,
        )
        assertEquals(
            "entry_class_invalid",
            NativeLoadFailureCodes.ENTRY_CLASS_INVALID,
        )
        assertEquals("missing_bundle_fd", NativeLoadFailureCodes.MISSING_BUNDLE_FD)
    }

    @Test
    fun `reused phase 10 codes match wire format`() {
        // These must match the strings the JS sandbox returns so
        // host code can decode either path through one switch.
        assertEquals("parcel_malformed", NativeLoadFailureCodes.PARCEL_MALFORMED)
        assertEquals("bundle_hash_mismatch", NativeLoadFailureCodes.BUNDLE_HASH_MISMATCH)
        assertEquals("unsupported_renderer", NativeLoadFailureCodes.UNSUPPORTED_RENDERER)
        assertEquals(
            "diagnostic_field_oversize",
            NativeLoadFailureCodes.DIAGNOSTIC_FIELD_OVERSIZE,
        )
        assertEquals(
            "concurrent_load_in_progress",
            NativeLoadFailureCodes.CONCURRENT_LOAD_IN_PROGRESS,
        )
    }

    @Test
    fun `dex max bytes is 4 MB`() {
        assertEquals(4 * 1024 * 1024, DEX_MAX_BYTES)
    }

    @Test
    fun `onInit timeout is 10 seconds`() {
        assertEquals(10_000L, ON_INIT_TIMEOUT_MILLIS)
    }
}
