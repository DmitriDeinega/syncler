package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.android.pluginhost.EndpointMatcher
import app.syncler.android.pluginhost.PluginInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class NetworkBridge(
    httpClient: OkHttpClient? = null,
    private val auditLogger: AuditLogger,
) {
    private val client = httpClient ?: OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .build()

    suspend fun fetch(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val url = args["url"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        if (!EndpointMatcher.matches(url, plugin.manifest.declaredEndpoints)) {
            auditLogger.denied(plugin.manifest.id, "endpoint_not_declared", url)
            return@withContext """{"error":"endpoint_not_declared","url":${app.syncler.android.pluginhost.JsonEscaping.quote(url)}}"""
        }

        val init = args["init"] as? Map<*, *>
        val method = (init?.get("method") as? String)?.uppercase() ?: "GET"
        val body = init?.get("body") as? String
        val mediaType = (init?.get("contentType") as? String)?.toMediaTypeOrNull()
        val requestBody = if (body != null && method !in setOf("GET", "HEAD")) body.toRequestBody(mediaType) else null
        val builder = Request.Builder().url(url).method(method, requestBody)
        (init?.get("headers") as? Map<*, *>)?.forEach { (name, value) ->
            if (name is String && value is String) builder.header(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            JsonBridgeCodec.toJson(
                mapOf(
                    "status" to response.code,
                    "headers" to response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                    "body" to (response.body?.string() ?: ""),
                ),
            )
        }
    }
}
