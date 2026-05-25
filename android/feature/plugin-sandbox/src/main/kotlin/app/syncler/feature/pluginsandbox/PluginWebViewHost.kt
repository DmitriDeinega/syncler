package app.syncler.feature.pluginsandbox

import app.syncler.core.pluginaidl.PluginLoadParcel

/**
 * Thin abstraction over the actual `WebView` instance one plugin
 * runs in. The sandbox routes lifecycle + dispatch through this
 * interface so unit tests can substitute a recording double
 * (`FakePluginWebViewHost` in the test source set) without
 * pulling in `android.webkit.WebView`, which isn't on the JVM
 * test classpath.
 *
 * Phase 10b step 2 ships this interface + a thread-safe state
 * machine that operates against it. Step 2 lands a no-op
 * production [PluginWebViewHost] factory whose actual
 * `WebView`-touching implementation we'll fill in once the
 * connectedAndroidTest path is wired up (the WebView APIs need
 * a `Context` + a real main looper, which is awkward for unit
 * tests).
 */
interface PluginWebViewHost {

    /**
     * Stage the bundle, verify the hash matches
     * [PluginLoadParcel.bundleHashHex], and start evaluating JS.
     * Returns when the WebView has loaded enough that subsequent
     * [dispatchHook] calls are safe to queue. The real readiness
     * signal is [HostSignals.reportReady], fired once the WebView
     * finishes loading the bundle.
     *
     * Throws an [IllegalStateException] with a
     * [LoadFailureCodes] code on failure.
     */
    fun startLoad(parcel: PluginLoadParcel, hostSignals: HostSignals)

    /**
     * Deliver a host-originated hook to the plugin's JS. Caller
     * has already serialized via the per-token Channel; the
     * implementation may assume sequential invocation per token.
     */
    fun dispatchHook(hook: String, payloadJson: String, callbackId: String)

    /**
     * Resolve a previously-issued `bridgeCall` callback with a
     * JSON result (success or `{"error":...}` shape).
     */
    fun deliverBridgeResult(callbackId: String, resultJson: String)

    /**
     * Destroy the WebView + tear down per-token state. Idempotent.
     * The sandbox fires `onPluginUnloaded` AFTER this returns.
     */
    fun destroy()
}

/**
 * Sandbox-side signals the [PluginWebViewHost] fires up to its
 * coordinator: bridge calls (from `JavascriptInterface`), ready,
 * and error. The interface indirection keeps the AIDL types out
 * of the WebView implementation and lets unit tests substitute a
 * recording double.
 *
 * Phase 10b step 5d: split from the previous `BridgeBroker` so
 * the WebView impl can drive `onPluginReady` /
 * `onWebViewError` lifecycle transitions, not just bridge calls.
 */
interface HostSignals {
    /** JS-bridge `call(method, argsJson, callbackId)` arrived. */
    fun bridgeCall(method: String, argsJson: String, callbackId: String)

    /** WebView finished loading the bundle; plugin is ready. */
    fun reportReady()

    /** WebView reported an unrecoverable error. */
    fun reportError(code: String, message: String)
}
