package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.android.pluginhost.BuildConfig
import app.syncler.android.pluginhost.EndpointMatcher
import app.syncler.android.pluginhost.PluginInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * V2 #11: `platform.message.respond(actionId, payload)` becomes
 * a request/response handshake instead of fire-and-forget.
 *
 * The plugin's manifest declares `template.actions[].endpoint`
 * (V1 schema, surfaced via [PluginManifest]). The bridge:
 *  1. Looks up the action by id in the plugin's manifest.
 *  2. POSTs the payload to that endpoint.
 *  3. Returns `{status, body}` to the plugin so it can react.
 *
 * Audit-logging routes through the existing [AuditLogger] for
 * each invocation (the legacy fire-and-forget path didn't
 * audit at all).
 */
class MessageBridge(
    private val auditLogger: AuditLogger,
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(
                if (BuildConfig.DEBUG) {
                    listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(ConnectionSpec.MODERN_TLS)
                },
            )
            .build()
    }

    suspend fun respond(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = JsonBridgeCodec.objectFrom(argsJson)
            val actionId = args["actionId"] as? String
                ?: return@withContext JsonBridgeCodec.error("invalid_args")
            val endpoint = args["endpoint"] as? String
                ?: return@withContext JsonBridgeCodec.error("invalid_args")
            val payload = when (val raw = args["payload"]) {
                is String -> raw // already JSON-encoded by caller
                null -> "{}"
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    JsonBridgeCodec.toJson(raw as Map<String, Any?>)
                }
                else -> raw.toString()
            }

            // V2 #11: gate the endpoint on declaredEndpoints —
            // the plugin can't address arbitrary URLs via this
            // bridge. Same glob grammar `platform.network.fetch`
            // uses (EndpointMatcher).
            if (!EndpointMatcher.matches(endpoint, plugin.manifest.declaredEndpoints)) {
                auditLogger.denied(plugin.manifest.id, "endpoint_not_declared", endpoint)
                return@withContext JsonBridgeCodec.error("endpoint_not_declared")
            }

            // Scheme check (mirrors TemplateActionRunner / NetworkBridge).
            val schemeOk = endpoint.startsWith("https://") ||
                (BuildConfig.DEBUG && endpoint.startsWith("http://"))
            if (!schemeOk) {
                auditLogger.denied(plugin.manifest.id, "non_https_endpoint", endpoint)
                return@withContext JsonBridgeCodec.error("non_https_endpoint")
            }

            val result = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    Pair(response.code, body)
                }
            }
            if (result.isFailure) {
                val exc = result.exceptionOrNull()
                Timber.tag(TAG).w(exc, "message.respond POST %s threw", endpoint)
                auditLogger.denied(plugin.manifest.id, "respond_threw", exc?.message ?: "")
                return@withContext JsonBridgeCodec.error("io_error")
            }
            val (status, body) = result.getOrNull()!!
            auditLogger.denied(
                plugin.manifest.id,
                if (status in 200..299) "respond_ok" else "respond_$status",
                "action=$actionId endpoint=$endpoint",
            )
            JsonBridgeCodec.toJson(mapOf("status" to status, "body" to body))
        }

    suspend fun dismissBehavior(plugin: PluginInstance, argsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = JsonBridgeCodec.objectFrom(argsJson)
            val behavior = args["behavior"] as? String
                ?: return@withContext JsonBridgeCodec.error("invalid_args")
            if (behavior != plugin.manifest.dismissBehavior) {
                auditLogger.denied(plugin.manifest.id, "dismiss_behavior_override", behavior)
            }
            "{}"
        }

    private companion object {
        const val TAG = "MessageBridge"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
