package app.syncler.feature.inbox

import org.json.JSONObject

/**
 * Builds the per-card WebView HTML shell.
 *
 * The shell:
 *  1. Installs a `window.platform` proxy that round-trips through the native
 *     `__syncler_native__` JavascriptInterface (see [CardBridge]). For V1
 *     inbox mode only `platform.network.fetch` is wired native-side; other
 *     methods reject with a clear error so plugins fail visibly rather than
 *     silently no-op'ing.
 *  2. Runs the plugin bundle (IIFE; self-registers via `registerPlugin(...)`).
 *  3. Dispatches the `render` hook with the message payload, injects the
 *     returned HTML into the body, and re-creates any inline `<script>` tags
 *     so click handlers (e.g. lottery's "Played" button) wire up.
 */
internal object RenderShell {
    fun build(bundleJs: String, payloadJson: String): String {
        // Defensive: the bundle's source may contain literal `</script>` (e.g.
        // in a quoted string). Break the sequence so the host's `<script>`
        // closer doesn't get prematurely matched by the HTML parser.
        val safeBundle = bundleJs.replace("</script", "<\\/script", ignoreCase = true)
        val safePayloadWrap = JSONObject().put("p", payloadJson).toString()
            .replace("</script", "<\\/script", ignoreCase = true)
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                html, body { margin: 0; padding: 0; }
                body { font-family: -apple-system, Roboto, sans-serif; color: #1c1b1f; background: transparent; }
                #syncler-render-error { color: #b3261e; padding: 12px; font-size: 13px; }
              </style>
            </head>
            <body>
              <script>
              // Capture uncaught errors that fire during bundle execution.
              // WebView <script> tags swallow throws and move on to the next
              // block, so without this hook a manifest-validation failure (or
              // any other top-level throw) is invisible — the symptom you'd
              // see is the *next* error ("No Syncler plugin has been
              // registered") instead of the real root cause.
              window.__syncler_bundleLoadErrors = [];
              window.addEventListener('error', (ev) => {
                window.__syncler_bundleLoadErrors.push(
                  (ev.error && ev.error.message) || ev.message || 'unknown error'
                );
              });
              window.addEventListener('unhandledrejection', (ev) => {
                const reason = ev.reason;
                window.__syncler_bundleLoadErrors.push(
                  (reason && reason.message) || String(reason)
                );
              });
              </script>
              <script>
              (() => {
                const callbacks = new Map();
                let nextCallbackId = 1;
                window.__syncler_internal_callback = (callbackId, result) => {
                  const callback = callbacks.get(callbackId);
                  if (!callback) return;
                  callbacks.delete(callbackId);
                  if (result && result.success) callback.resolve(result.value);
                  else callback.reject(new Error((result && (result.message || result.error)) || 'unknown_error'));
                };
                const nativeCall = (method, args) => new Promise((resolve, reject) => {
                  const callbackId = String(nextCallbackId++);
                  callbacks.set(callbackId, { resolve, reject });
                  window.__syncler_native__.call(method, JSON.stringify(args || {}), callbackId);
                });
                const asResponse = async (payload) => new Response(payload.body || '', {
                  status: payload.status || 200,
                  headers: payload.headers || {}
                });
                const reject = (name) => () => Promise.reject(new Error(
                  'platform.' + name + ' is not wired in V1 inbox mode'));
                window.platform = {
                  __version__: '1.0.0',
                  showNotification: reject('showNotification'),
                  storage: { get: reject('storage.get'), set: reject('storage.set'), delete: reject('storage.delete') },
                  network: {
                    fetch: (url, init) => nativeCall('platform.network.fetch', { url, init }).then(asResponse)
                  },
                  camera: { capture: reject('camera.capture') },
                  gallery: { pick: reject('gallery.pick') },
                  file: { pick: reject('file.pick') },
                  location: { current: reject('location.current') },
                  message: {
                    respond: reject('message.respond'),
                    dismissBehavior: () => undefined
                  }
                };
              })();
              </script>
              <script>$safeBundle</script>
              <script>
              (async () => {
                try {
                  // If the bundle threw at load time, surface that — its real
                  // message is far more useful than the generic
                  // "plugin not registered" we'd otherwise see.
                  const loadErrors = window.__syncler_bundleLoadErrors || [];
                  if (loadErrors.length > 0) {
                    throw new Error('plugin bundle threw at load: ' + loadErrors.join(' | '));
                  }
                  const payloadWrap = $safePayloadWrap;
                  const payload = JSON.parse(payloadWrap.p);
                  const dispatcher = window.__syncler_internal_dispatch;
                  if (typeof dispatcher !== 'function') {
                    throw new Error('plugin did not install __syncler_internal_dispatch — missing registerPlugin() call?');
                  }
                  const html = await dispatcher('render', [payload]);
                  if (typeof html !== 'string') {
                    throw new Error('plugin render() did not return a string');
                  }
                  document.body.innerHTML = html;
                  document.body.querySelectorAll('script').forEach((oldScript) => {
                    const newScript = document.createElement('script');
                    for (const attr of oldScript.attributes) newScript.setAttribute(attr.name, attr.value);
                    newScript.textContent = oldScript.textContent;
                    oldScript.parentNode.replaceChild(newScript, oldScript);
                  });
                } catch (e) {
                  document.body.innerHTML =
                    '<div id="syncler-render-error">render failed: ' +
                    (e && e.message ? e.message : String(e)) + '</div>';
                }
              })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
