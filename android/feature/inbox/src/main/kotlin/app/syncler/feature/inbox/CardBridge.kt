package app.syncler.feature.inbox

import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.syncler.feature.inbox.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal native bridge for inbox-rendered cards. Exposed to the WebView as
 * `window.__syncler_native__`; the shell HTML wraps it in a `window.platform`
 * proxy that mirrors the SDK contract.
 *
 * Only `platform.network.fetch` is wired — the lottery dogfood's "Played"
 * button needs it, and that's the only capability V1 senders rely on. Other
 * methods (storage, camera, notifications, …) explicitly reject so plugins
 * fail loudly instead of silently no-op'ing. Wiring the full bridge is the
 * next chunk, and it should reuse `:feature:plugin-host`'s bridges rather
 * than re-implement them here.
 */
class CardBridge(
    private val webView: WebView,
    private val declaredEndpoints: List<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionSpecs(
            if (BuildConfig.DEBUG) {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                listOf(ConnectionSpec.MODERN_TLS)
            },
        )
        .build()

    @JavascriptInterface
    fun call(method: String, argsJson: String, callbackId: String) {
        scope.launch {
            val result = runCatching { dispatch(method, argsJson) }
            val payload = if (result.isSuccess) {
                """{"success":true,"value":${result.getOrThrow()}}"""
            } else {
                val message = result.exceptionOrNull()?.message ?: "card bridge error"
                """{"success":false,"error":"call_failed","message":${JSONObject.quote(message)}}"""
            }
            webView.post {
                webView.evaluateJavascript(
                    "window.__syncler_internal_callback(${JSONObject.quote(callbackId)}, $payload)",
                    null,
                )
            }
        }
    }

    fun destroy() {
        scope.cancel()
    }

    private suspend fun dispatch(method: String, argsJson: String): String = when (method) {
        "platform.network.fetch" -> doFetch(argsJson)
        else -> throw UnsupportedOperationException("$method is not wired in inbox mode")
    }

    private suspend fun doFetch(argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JSONObject(argsJson)
        val url = args.optString("url").ifBlank { throw IllegalArgumentException("missing url") }
        // Scheme check (defense in depth — the OkHttp client already refuses
        // cleartext in release via its connectionSpecs, but failing explicitly
        // here gives a clearer error and survives future client wiring drift).
        val schemeOk = url.startsWith("https://") || (BuildConfig.DEBUG && url.startsWith("http://"))
        if (!schemeOk) throw SecurityException("fetch URL must be HTTPS: $url")
        if (!matchesAny(url, declaredEndpoints)) {
            throw SecurityException("endpoint not declared: $url")
        }
        val init = args.optJSONObject("init")
        val method = init?.optString("method")?.uppercase()?.takeIf { it.isNotBlank() } ?: "GET"
        val bodyText = init?.optString("body")?.takeIf { it.isNotBlank() && method !in setOf("GET", "HEAD") }
        val mediaType = init?.optString("contentType")?.takeIf { it.isNotBlank() }?.toMediaTypeOrNull()
        val request = Request.Builder().url(url).method(method, bodyText?.toRequestBody(mediaType)).apply {
            init?.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { key -> header(key, headers.optString(key)) }
            }
        }.build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val headersJson = JSONObject().also { h ->
                response.headers.names().forEach { name ->
                    h.put(name, response.headers(name).joinToString(","))
                }
            }
            JSONObject()
                .put("status", response.code)
                .put("headers", headersJson)
                .put("body", responseBody)
                .toString()
        }
    }

    private fun matchesAny(url: String, patterns: List<String>): Boolean =
        patterns.any { pattern -> endpointPatternToRegex(pattern).matches(url) }

    // Same logic as :feature:plugin-host's EndpointMatcher; inlined here so
    // :feature:inbox doesn't need to depend on the whole plugin-host module
    // graph just for this glob match.
    private fun endpointPatternToRegex(pattern: String): Regex {
        val pathStart = findPathStart(pattern)
        val source = buildString {
            pattern.forEachIndexed { index, character ->
                if (character == '*') {
                    append(if (index < pathStart) "[^./]*" else "[^/]*")
                } else {
                    append(Regex.escape(character.toString()))
                }
            }
        }
        return Regex("^$source$")
    }

    private fun findPathStart(pattern: String): Int {
        val schemeEnd = pattern.indexOf("://")
        val searchFrom = if (schemeEnd == -1) 0 else schemeEnd + 3
        val slash = pattern.indexOf('/', searchFrom)
        return if (slash == -1) pattern.length else slash
    }

    companion object {
        const val NATIVE_BRIDGE_NAME = "__syncler_native__"
    }
}
