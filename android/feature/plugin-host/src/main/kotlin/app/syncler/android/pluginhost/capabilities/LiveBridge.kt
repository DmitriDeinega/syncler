package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.AuditLogger
import app.syncler.android.pluginhost.PluginInstance
import app.syncler.android.pluginhost.live.ChannelEvent
import app.syncler.android.pluginhost.live.LiveChannel
import app.syncler.android.pluginhost.live.LiveChannelClient
import app.syncler.android.pluginhost.live.LiveChannelException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * V3 #14/#15 — `platform.live.*` bridge dispatcher.
 *
 * Per-plugin [LiveChannelClient] instances are created on
 * first `platform.live.connect(channel)` call and shared
 * across subsequent calls in the same plugin lifecycle. On
 * plugin unload the bridge closes the client (steps 9 + 10
 * of docs/live-channel.md).
 *
 * Hot incoming flow from each [LiveChannel] is forwarded to
 * the plugin's `onLiveMessage` hook via [PluginInstance
 * .dispatchHook] — closes V3 #15 step (SDK sugar over
 * `connect()`).
 *
 * The `clientFactory` is injected so tests can substitute a
 * fake — production passes the OkHttp + AuditLogger flavor
 * from PluginLoader.android().
 */
class LiveBridge(
    private val clientFactory: LiveChannelClientFactory,
    private val auditLogger: AuditLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * Keyed by `plugin.manifest.id` (process-singleton-ish per
     * plugin row). On plugin unload, callers should
     * [closeForPlugin] to drop the client.
     */
    private val clientsByPlugin = ConcurrentHashMap<String, LiveChannelClient>()

    suspend fun connect(plugin: PluginInstance, argsJson: String): String {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val channelName = args["channel"] as? String
            ?: return JsonBridgeCodec.error("invalid_args")
        val client = clientsByPlugin.getOrPut(plugin.manifest.id) {
            clientFactory.build(plugin)
        }
        return try {
            val channel = client.openChannel(channelName)
            wireIncoming(plugin, channel)
            auditLogger.record(plugin.manifest.id, "live_connect_ok", channelName)
            JsonBridgeCodec.toJson(mapOf("channel" to channelName, "ok" to true))
        } catch (exc: LiveChannelException) {
            auditLogger.record(plugin.manifest.id, "live_connect_${exc.code}", channelName)
            JsonBridgeCodec.error(exc.code)
        } catch (exc: Throwable) {
            Timber.tag(TAG).w(exc, "live.connect threw")
            auditLogger.record(plugin.manifest.id, "live_connect_error", exc.message ?: "")
            JsonBridgeCodec.error("io_error")
        }
    }

    suspend fun send(plugin: PluginInstance, argsJson: String): String {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val channelName = args["channel"] as? String
            ?: return JsonBridgeCodec.error("invalid_args")
        val envelopeB64 = args["envelope"] as? String
            ?: return JsonBridgeCodec.error("invalid_args")
        val bytes = runCatching {
            android.util.Base64.decode(envelopeB64, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return JsonBridgeCodec.error("invalid_args")

        val client = clientsByPlugin[plugin.manifest.id]
            ?: return JsonBridgeCodec.error("channel_not_open")
        return try {
            // open-channel state is the client's concern; if
            // the plugin hasn't opened this channel send()
            // will surface a server-side `channel_not_open`
            // error frame on the incoming flow.
            val handle = clientChannelHandle(client, channelName)
                ?: return JsonBridgeCodec.error("channel_not_open")
            handle.send(bytes)
            "{}"
        } catch (exc: Throwable) {
            Timber.tag(TAG).w(exc, "live.send threw")
            auditLogger.record(plugin.manifest.id, "live_send_error", channelName)
            JsonBridgeCodec.error("io_error")
        }
    }

    suspend fun close(plugin: PluginInstance, argsJson: String): String {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val channelName = args["channel"] as? String
            ?: return JsonBridgeCodec.error("invalid_args")
        val client = clientsByPlugin[plugin.manifest.id]
            ?: return "{}"
        return try {
            val handle = clientChannelHandle(client, channelName) ?: return "{}"
            handle.close()
            "{}"
        } catch (exc: Throwable) {
            Timber.tag(TAG).w(exc, "live.close threw")
            JsonBridgeCodec.error("io_error")
        }
    }

    /**
     * Called from PluginRegistry / unload hooks to release the
     * per-plugin WebSocket on teardown.
     */
    fun closeForPlugin(pluginId: String) {
        clientsByPlugin.remove(pluginId)?.close()
    }

    private fun wireIncoming(plugin: PluginInstance, channel: LiveChannel) {
        // Forward each incoming event to the plugin's
        // onLiveMessage hook via dispatchHook. The hook
        // payload carries the channel name + base64'd
        // envelope bytes so the plugin can open it on its
        // own side using the V2 envelope helpers.
        scope.launch {
            channel.incoming.collect { event ->
                when (event) {
                    is ChannelEvent.Message -> {
                        val payload = JSONObject().apply {
                            put("channel", channel.name)
                            put(
                                "envelope",
                                android.util.Base64.encodeToString(
                                    event.envelopeBytes,
                                    android.util.Base64.NO_WRAP,
                                ),
                            )
                        }.toString()
                        plugin.dispatchHook(
                            "onLiveMessage", payload, channel.name,
                        )
                    }
                    is ChannelEvent.Error -> {
                        plugin.dispatchHook(
                            "onLiveError",
                            JSONObject().apply {
                                put("channel", channel.name)
                                put("code", event.code)
                            }.toString(),
                            channel.name,
                        )
                    }
                    ChannelEvent.Closed -> {
                        plugin.dispatchHook(
                            "onLiveClosed",
                            JSONObject().apply {
                                put("channel", channel.name)
                            }.toString(),
                            channel.name,
                        )
                    }
                }
            }
        }
    }

    // Currently the LiveChannelClient holds channels internally;
    // exposing a getter would widen its API surface. Walk the
    // client via the existing openChannel re-call (idempotent —
    // returns the cached LiveChannel if already open).
    private suspend fun clientChannelHandle(
        client: LiveChannelClient, channelName: String,
    ): LiveChannel? = runCatching { client.openChannel(channelName) }.getOrNull()

    companion object {
        private const val TAG = "LiveBridge"
    }
}

/**
 * Factory injected into [LiveBridge] so production wiring +
 * tests share one constructor surface.
 */
interface LiveChannelClientFactory {
    fun build(plugin: PluginInstance): LiveChannelClient
}
