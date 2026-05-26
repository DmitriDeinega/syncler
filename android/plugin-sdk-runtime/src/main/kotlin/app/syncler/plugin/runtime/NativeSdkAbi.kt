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
 * V1.5 shipped ABI 1. Phase 12 (V2 #10) bumped to ABI 2 to swap
 * camera/gallery/file return types from byte-bearing data classes
 * to handle-bearing ones (binary results too large for Binder
 * inline transfer). V3 #14 bumped to ABI 3 to add liveConnect /
 * LiveChannel on PluginContext.
 *
 * Spec:
 *  - docs/plugin-host-native-kotlin.md "SDK runtime API"
 *  - docs/plugin-capability-expansion.md "Binary transport"
 *  - docs/live-channel.md "Native SDK ABI bump"
 *
 * Earlier-ABI plugins fail load with `unsupported_sdk_abi`.
 * V0.1 has no shipped native plugins yet so the bump is free.
 */
const val NATIVE_SDK_ABI: Int = 3
