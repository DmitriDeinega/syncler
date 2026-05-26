package app.syncler.core.pluginaidl;

import android.os.ParcelFileDescriptor;
import app.syncler.core.pluginaidl.PluginLoadParcel;
import app.syncler.core.pluginaidl.IPluginHostCallback;

// Phase 10 — host -> subprocess (:plugin). Implemented by
// PluginSandboxService. See docs/plugin-host-multi-process.md.
interface IPluginSandbox {

    // Synchronous load. Caller blocks on Binder until the sandbox
    // has accepted the parcel. Returns the sandboxToken from the
    // parcel verbatim (host verifies equality on return; mismatch
    // = fatal sandbox bug).
    //
    // Throws IllegalStateException via Binder with one of:
    //   - parcel_malformed
    //   - bundle_hash_mismatch
    //   - unsupported_renderer
    //   - diagnostic_field_oversize
    //   - concurrent_load_in_progress
    //   - concurrent_unload_in_progress
    //   - dex_too_large                 (Phase 11)
    //   - unsupported_sdk_abi           (Phase 11)
    //   - forbidden_package_prefix      (Phase 11)
    //   - entry_class_not_found         (Phase 11)
    //   - entry_class_invalid           (Phase 11)
    //
    // The IPluginHostCallback is registered as part of this call
    // so there's no race between token-return and the first
    // lifecycle event firing.
    //
    // Phase 11: `bundleFd` carries the DEX bytes for native plugins
    // (the isolated process can't read host /data/data/... so the
    // host opens the file in its own process and passes the FD).
    // Null for JS (`script`) plugins — they keep using
    // `PluginLoadParcel.bundleFilePath` since the `:plugin` process
    // isn't isolated. See docs/plugin-host-native-kotlin.md.
    int loadPlugin(
        in PluginLoadParcel request,
        in IPluginHostCallback callback,
        in @nullable ParcelFileDescriptor bundleFd);

    // Begin unload. Fire-and-forget; sandbox will fire
    // IPluginHostCallback.onPluginUnloaded(sandboxToken) when the
    // unloading -> unloaded transition completes.
    oneway void unloadPlugin(int sandboxToken);

    // Host -> plugin event delivery. Oneway so the sandbox cannot
    // block the host UI on slow plugin code. Sandbox serializes
    // per-token via an internal per-token Channel so concurrent
    // host coroutines don't reorder hooks.
    oneway void dispatchHook(int sandboxToken, String hook, String payloadJson, String callbackId);
    oneway void deliverBridgeResult(int sandboxToken, String callbackId, String resultJson);

    // Diagnostics (settings UI only). Returns one of:
    //   "loading" | "ready" | "errored" | "unloading"
    //   | "unloaded" | "process_dead" | "unknown"
    String querySandboxState(int sandboxToken);
}
