package app.syncler.feature.pluginsandbox

/**
 * Plain Kotlin mirror of [app.syncler.core.pluginaidl.IPluginHostCallback].
 *
 * The coordinator depends on this interface instead of the AIDL
 * `Stub` so unit tests can implement it directly without dragging
 * in `android.os.Binder` (which isn't on the JVM unit-test
 * classpath — Stub's constructor calls `attachInterface` and
 * throws "not mocked").
 *
 * [PluginSandboxService] adapts the incoming AIDL
 * `IPluginHostCallback` to this interface before handing it to
 * [PluginTokenCoordinator].
 */
interface PluginHostCallbackLocal {
    fun bridgeCall(sandboxToken: Int, method: String, argsJson: String, callbackId: String)
    fun onWebViewError(sandboxToken: Int, code: String, message: String)
    fun onPluginReady(sandboxToken: Int)
    fun onPluginCrashed(sandboxToken: Int, reason: String)
    fun onPluginUnloaded(sandboxToken: Int)
}
