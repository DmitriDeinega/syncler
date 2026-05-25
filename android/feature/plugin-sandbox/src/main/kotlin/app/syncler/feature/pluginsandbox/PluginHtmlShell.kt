package app.syncler.feature.pluginsandbox

/**
 * Phase 10b step 5: HTML + bridge JS shell for plugin bundles.
 *
 * Mirrors `app.syncler.android.pluginhost.PluginHtmlShell` (the
 * in-process loader's shell) byte-for-byte so capability calls
 * and lifecycle hooks behave identically across the multi-
 * process port. Duplicated here intentionally — the in-process
 * shell will be deleted in step 6, but until then both paths
 * MUST agree.
 *
 * The shell wraps the plugin bundle in:
 *   1. A capability stub that translates `platform.*` calls into
 *      `__syncler_native__.call(method, JSON.stringify(args),
 *      callbackId)` JS-bridge invocations.
 *   2. A callback router (`window.__syncler_internal_callback`)
 *      keyed by `callbackId` — the sandbox's `deliverBridgeResult`
 *      evaluates this with `{success, value}` or `{success: false,
 *      error, message}` payloads.
 *   3. A host-hook dispatcher (`__syncler_internal_dispatch`)
 *      that wraps the plugin's SDK-registered hook map and
 *      routes return values back via the same callback path.
 */
internal object PluginHtmlShell {
    fun render(bundleJs: String): String {
        val escapedBundle = bundleJs.replace("</script", "<\\/script", ignoreCase = true)
        return """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body>
            <script>
            (() => {
              const callbacks = new Map();
              let nextCallbackId = 1;
              window.__syncler_internal_callback = (callbackId, result) => {
                const callback = callbacks.get(callbackId);
                if (!callback) return;
                callbacks.delete(callbackId);
                if (result && result.success) callback.resolve(result.value);
                else callback.reject({ error: result?.error || "unknown_error", message: result?.message });
              };
              const nativeCall = (method, args) => new Promise((resolve, reject) => {
                const callbackId = String(nextCallbackId++);
                callbacks.set(callbackId, { resolve, reject });
                window.__syncler_native__.call(method, JSON.stringify(args || {}), callbackId);
              });
              const asResponse = async (payload) => new Response(payload.body || "", {
                status: payload.status || 200,
                headers: payload.headers || {}
              });
              window.platform = {
                __version__: "1.0.0",
                showNotification: (opts) => nativeCall("platform.showNotification", opts),
                storage: {
                  get: (key, opts) => nativeCall("platform.storage.get", { key, opts }).then((r) => r.value ?? null),
                  set: (key, value, opts) => nativeCall("platform.storage.set", { key, value, opts }),
                  delete: (key, opts) => nativeCall("platform.storage.delete", { key, opts })
                },
                network: {
                  fetch: (url, init) => nativeCall("platform.network.fetch", { url, init }).then(asResponse)
                },
                camera: { capture: (opts) => nativeCall("platform.camera.capture", opts) },
                gallery: { pick: (opts) => nativeCall("platform.gallery.pick", opts) },
                file: { pick: (opts) => nativeCall("platform.file.pick", opts) },
                location: { current: (opts) => nativeCall("platform.location.current", opts) },
                message: {
                  respond: (actionId, payload) => nativeCall("platform.message.respond", { actionId, payload }),
                  dismissBehavior: (behavior) => nativeCall("platform.message.dismissBehavior", { behavior })
                }
              };
            })();
            </script>
            <script>$escapedBundle</script>
            <script>
            (() => {
              const sdkDispatch = window.__syncler_internal_dispatch;
              window.__syncler_internal_dispatch = (hook, args, callbackId) => {
                const promise = sdkDispatch ? Promise.resolve(sdkDispatch(hook, args || [])) : Promise.reject(new Error("plugin dispatcher unavailable"));
                if (callbackId) {
                  promise.then(
                    (value) => window.__syncler_internal_callback(callbackId, { success: true, value }),
                    (error) => window.__syncler_internal_callback(callbackId, { success: false, error: "plugin_dispatch_failed", message: String(error && error.message || error) })
                  );
                }
                return promise;
              };
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** Sentinel base URL for the WebView — matches the in-process loader. */
    const val INITIAL_URL = "https://plugin.local/"

    /** Name the JavascriptInterface object is bound under. */
    const val NATIVE_BRIDGE_NAME = "__syncler_native__"
}
