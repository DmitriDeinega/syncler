package app.syncler.feature.pluginnativesandbox

/**
 * Phase 11 native-sandbox load failure codes. Mirrors the shape of
 * [app.syncler.feature.pluginsandbox.LoadFailureCodes] from the JS
 * path so both sandboxes return the same kind of structured error
 * to the host (`IllegalStateException` over Binder, message = one
 * of these constants).
 *
 * The host-side decoder is shared — see SandboxRouter's catch-all
 * in Phase 10. Anything outside this set is treated as
 * `unknown_sandbox_error`.
 */
object NativeLoadFailureCodes {
    // Reused from Phase 10 (shape-identical):
    const val PARCEL_MALFORMED = "parcel_malformed"
    const val BUNDLE_HASH_MISMATCH = "bundle_hash_mismatch"
    const val UNSUPPORTED_RENDERER = "unsupported_renderer"
    const val DIAGNOSTIC_FIELD_OVERSIZE = "diagnostic_field_oversize"
    const val CONCURRENT_LOAD_IN_PROGRESS = "concurrent_load_in_progress"

    // Phase 11 additions (docs/plugin-host-native-kotlin.md):
    const val DEX_TOO_LARGE = "dex_too_large"
    const val UNSUPPORTED_SDK_ABI = "unsupported_sdk_abi"
    const val FORBIDDEN_PACKAGE_PREFIX = "forbidden_package_prefix"
    const val ENTRY_CLASS_NOT_FOUND = "entry_class_not_found"
    const val ENTRY_CLASS_INVALID = "entry_class_invalid"
    const val INIT_TIMEOUT = "init_timeout"
    const val MISSING_BUNDLE_FD = "missing_bundle_fd"
}

/** Per docs/plugin-host-native-kotlin.md "Bundle format". */
const val DEX_MAX_BYTES: Int = 4 * 1024 * 1024

/** Per docs/plugin-host-native-kotlin.md "Per-plugin lifecycle". */
const val ON_INIT_TIMEOUT_MILLIS: Long = 10_000L
