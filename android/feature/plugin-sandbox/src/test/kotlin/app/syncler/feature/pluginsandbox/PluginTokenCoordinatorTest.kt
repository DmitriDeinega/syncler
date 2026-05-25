package app.syncler.feature.pluginsandbox

import app.syncler.core.pluginaidl.PluginLoadParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTokenCoordinatorTest {

    @Test
    fun lifecycleFromLoadingThroughUnload() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)

        coordinator.startLoad(sampleParcel())
        assertEquals(PluginSandboxState.LOADING, coordinator.state)

        coordinator.reportReady()
        assertEquals(PluginSandboxState.READY, coordinator.state)
        assertEquals(listOf("ready(42)"), callback.events)

        coordinator.submitHook("inbox.changed", "{\"v\":1}", "cb-1")
        coordinator.submitBridgeResult("cb-2", "{\"ok\":true}")
        // Give the pump a tick to drain.
        delay(50)
        assertEquals(
            listOf(
                "startLoad(plugin-test)",
                "dispatchHook(inbox.changed, {\"v\":1}, cb-1)",
                "deliverBridgeResult(cb-2, {\"ok\":true})",
            ),
            webView.calls,
        )

        coordinator.unload()
        assertEquals(PluginSandboxState.UNLOADED, coordinator.state)
        assertTrue("destroy invoked", webView.destroyed)
        assertEquals(
            listOf("ready(42)", "unloaded(42)"),
            callback.events,
        )
    }

    @Test
    fun hooksDroppedAfterErrored() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)
        coordinator.startLoad(sampleParcel())
        coordinator.reportReady()

        coordinator.reportError("renderer_crash", "boom")
        assertEquals(PluginSandboxState.ERRORED, coordinator.state)

        coordinator.submitHook("late.hook", "{}", "cb-late")
        delay(20)
        assertTrue(
            "post-errored hooks must not reach WebView",
            webView.calls.none { it.startsWith("dispatchHook(late.hook") },
        )
        coordinator.unload()
        assertEquals(PluginSandboxState.UNLOADED, coordinator.state)
    }

    @Test
    fun unloadIsIdempotent() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)
        coordinator.startLoad(sampleParcel())
        coordinator.reportReady()

        coordinator.unload()
        coordinator.unload()  // second call — should be no-op
        assertEquals(1, callback.events.count { it.startsWith("unloaded") })
    }

    @Test
    fun bridgeCallFromWebViewRoutesToHost() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)
        coordinator.startLoad(sampleParcel())
        coordinator.reportReady()

        // The webview's stored signals interface is what the real
        // impl would call from a @JavascriptInterface method.
        webView.signals!!.bridgeCall("network.fetch", "{\"url\":\"x\"}", "cb-bridge")
        assertEquals(
            listOf(
                "ready(42)",
                "bridgeCall(42, network.fetch, {\"url\":\"x\"}, cb-bridge)",
            ),
            callback.events,
        )
    }

    @Test
    fun reportReadyFromWebViewTransitionsToReady() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)
        coordinator.startLoad(sampleParcel())
        assertEquals(PluginSandboxState.LOADING, coordinator.state)

        // Simulate WebViewClient.onPageFinished firing.
        webView.signals!!.reportReady()
        assertEquals(PluginSandboxState.READY, coordinator.state)
        assertEquals(listOf("ready(42)"), callback.events)
    }

    @Test
    fun reportErrorFromWebViewTransitionsToErrored() = runBlocking {
        val callback = RecordingHostCallback()
        val webView = RecordingWebViewHost()
        val coordinator = newCoordinator(callback, webView)
        coordinator.startLoad(sampleParcel())

        webView.signals!!.reportError("renderer_crash", "boom")
        assertEquals(PluginSandboxState.ERRORED, coordinator.state)
        assertEquals(
            listOf("error(42, renderer_crash, boom)"),
            callback.events,
        )
    }

    private fun sampleParcel(): PluginLoadParcel = PluginLoadParcel(
        sandboxToken = 42,
        pluginId = "plugin-test",
        pluginIdentifier = "com.example.test",
        version = "1.0.0",
        renderer = "script",
        bundleFilePath = "/tmp/bundle.js",
        bundleHashHex = "00".repeat(32),
        declaredCapabilities = emptyList(),
        declaredEndpoints = emptyList(),
        dismissBehavior = "ack",
        timeoutMillis = 1_000L,
        diagnosticManifestJson = "{}",
    )

    private fun newCoordinator(
        callback: PluginHostCallbackLocal,
        webView: PluginWebViewHost,
    ): PluginTokenCoordinator = PluginTokenCoordinator(
        sandboxToken = 42,
        pluginId = "plugin-test",
        callback = callback,
        webViewHost = webView,
        parentScope = CoroutineScope(Dispatchers.Default),
    )
}

private class RecordingHostCallback : PluginHostCallbackLocal {
    val events: MutableList<String> =
        java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun bridgeCall(sandboxToken: Int, method: String, argsJson: String, callbackId: String) {
        events.add("bridgeCall($sandboxToken, $method, $argsJson, $callbackId)")
    }
    override fun onWebViewError(sandboxToken: Int, code: String, message: String) {
        events.add("error($sandboxToken, $code, $message)")
    }
    override fun onPluginReady(sandboxToken: Int) {
        events.add("ready($sandboxToken)")
    }
    override fun onPluginCrashed(sandboxToken: Int, reason: String) {
        events.add("crashed($sandboxToken, $reason)")
    }
    override fun onPluginUnloaded(sandboxToken: Int) {
        events.add("unloaded($sandboxToken)")
    }
}

private class RecordingWebViewHost : PluginWebViewHost {
    val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    var signals: HostSignals? = null
    var destroyed: Boolean = false

    override fun startLoad(parcel: PluginLoadParcel, hostSignals: HostSignals) {
        signals = hostSignals
        calls.add("startLoad(${parcel.pluginId})")
    }
    override fun dispatchHook(hook: String, payloadJson: String, callbackId: String) {
        calls.add("dispatchHook($hook, $payloadJson, $callbackId)")
    }
    override fun deliverBridgeResult(callbackId: String, resultJson: String) {
        calls.add("deliverBridgeResult($callbackId, $resultJson)")
    }
    override fun destroy() {
        destroyed = true
    }
}
