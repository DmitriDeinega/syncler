package app.syncler.plugin.runtime

/**
 * Native plugin SDK ABI version (Phase 11).
 *
 * Every breaking change to [SynclerPlugin] or [PluginContext] bumps
 * this integer. Plugins declare the ABI they were compiled against
 * in `PluginManifest.native_sdk_abi`; the sandbox checks equality
 * BEFORE loading the DEX and fails with
 * `LoadFailureCodes.UNSUPPORTED_SDK_ABI` on mismatch.
 *
 * No compatibility shims — plugin re-publish is the upgrade path.
 *
 * V1.5 ships ABI 1. The spec is locked at
 * docs/plugin-host-native-kotlin.md "SDK runtime API" / "Manifest
 * fields".
 */
const val NATIVE_SDK_ABI: Int = 1
