package app.syncler.android.pluginhost.live

import app.syncler.android.pluginhost.AuditLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber

/**
 * V3 #14 step 9 — Android-side live-channel WebSocket client.
 *
 * One [LiveChannelClient] per (device, plugin_row_id) pair.
 * Manages:
 *  - Single OkHttp WebSocket connection
 *  - Two-step auth (mint connect-token via REST → open WS with
 *    `Sec-WebSocket-Protocol: syncler.v1, bearer.<token>`)
 *  - Channel multiplex via the spec's outer frame envelope
 *  - Reconnect with jittered exponential backoff
 *  - Heartbeat tracking (server pings; we just respond to
 *    "ping" frames with "pong")
 *
 * Plugin code interacts via [openChannel] → [LiveChannel],
 * which exposes a hot Flow of incoming envelope payloads and
 * a suspending [LiveChannel.send] for outbound frames.
 *
 * Per the spec at `docs/live-channel.md`:
 *  - Frame payloads are OPAQUE V2-shape envelopes — this
 *    class doesn't seal / open them; that's the plugin's job
 *    via the SDK runtime's V2 envelope helpers.
 *  - Server delivery is best-effort + ephemeral; the inbox
 *    pull is the authoritative catch-up path.
 *  - Plugins targeting CRDT-safe ordering MUST use a separate
 *    durable channel (V3 #17 work).
 */
class LiveChannelClient(
    private val baseUrl: String,
    private val pluginRowId: String,
    private val deviceJwtProvider: suspend () -> String,
    private val httpClient: OkHttpClient,
    private val auditLogger: AuditLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    private val channels = ConcurrentHashMap<String, LiveChannel>()
    private val socketMutex = Mutex()
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var connectJob: Job? = null
    private val closed = AtomicBoolean(false)

    /**
     * Open a channel for [channelName]. Returns a [LiveChannel]
     * the plugin reads/writes. Suspends until the WS is
     * connected and the channel-open ack arrives.
     */
    suspend fun openChannel(channelName: String): LiveChannel {
        require(channelName.isNotBlank() && channelName.length <= 64) {
            "channel name must be 1..64 chars"
        }
        val existing = channels[channelName]
        if (existing != null) return existing

        ensureConnected()

        val channel = LiveChannel(channelName, this)
        channels[channelName] = channel
        val deferred = CompletableDeferred<Unit>()
        channel._openDeferred = deferred
        sendFrame(JSONObject().apply {
            put("channel", channelName)
            put("type", "open")
        }.toString())
        deferred.await()
        return channel
    }

    /** Called by [LiveChannel.send]. */
    internal suspend fun sendMessageFrame(channelName: String, envelope: ByteArray) {
        // Triad 144 BOTH FIX: client-side 64 KB frame cap. The
        // server enforces this too (closing 4429 on sustained
        // violation) but failing fast in the client avoids the
        // base64+JSON encode work and gives the plugin a clean
        // error before any bytes leave the device.
        if (envelope.size > MAX_FRAME_BYTES) {
            throw LiveChannelException(
                "payload_too_large",
                "envelope size ${envelope.size} > $MAX_FRAME_BYTES",
            )
        }
        ensureConnected()
        val payloadB64 = android.util.Base64.encodeToString(
            envelope, android.util.Base64.NO_WRAP,
        )
        val frameId = java.util.UUID.randomUUID().toString().substring(0, 8)
        val frame = JSONObject().apply {
            put("channel", channelName)
            put("type", "message")
            put("id", frameId)
            put("payload", payloadB64)
        }.toString()
        sendFrame(frame)
    }

    /** Called by [LiveChannel.close]. */
    internal suspend fun sendCloseFrame(channelName: String) {
        val s = socket ?: return
        val frame = JSONObject().apply {
            put("channel", channelName)
            put("type", "close")
        }.toString()
        s.send(frame)
        channels.remove(channelName)
    }

    private suspend fun sendFrame(frame: String) {
        val s = socket
        if (s == null) {
            Timber.tag(TAG).w("sendFrame with no socket")
            return
        }
        s.send(frame)
    }

    private suspend fun ensureConnected(): Unit = socketMutex.withLock {
        if (socket != null) return
        if (closed.get()) error("LiveChannelClient is closed")
        connectInternal()
    }

    private suspend fun connectInternal() {
        // Step 1 of the two-step handshake — mint the connect
        // token via the standard REST surface.
        val deviceJwt = deviceJwtProvider()
        val mintReq = Request.Builder()
            .url("$baseUrl/v1/live/connect-token")
            .header("Authorization", "Bearer $deviceJwt")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val tokenJson = withCallSafe {
            httpClient.newCall(mintReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw LiveChannelException(
                        "mint_token_failed_${resp.code}",
                        "connect-token mint failed: HTTP ${resp.code}",
                    )
                }
                resp.body?.string() ?: throw LiveChannelException(
                    "mint_token_empty", "connect-token response empty",
                )
            }
        }
        val connectToken = JSONObject(tokenJson).getString("token")

        // Step 2 — open the WS with the connect token.
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/v1/live/plugin/$pluginRowId"
        val wsReq = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", "syncler.v1, bearer.$connectToken")
            .build()

        val ready = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.tag(TAG).i("ws open %s", pluginRowId)
                socket = webSocket
                ready.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleIncomingFrame(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Spec frames are text; ignore binary for V0.1.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.tag(TAG).w("ws closing code=%d reason=%s", code, reason)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.tag(TAG).w("ws closed code=%d", code)
                socket = null
                if (!closed.get()) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?,
            ) {
                Timber.tag(TAG).w(t, "ws failure")
                socket = null
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        LiveChannelException("ws_failure", t.message ?: ""),
                    )
                }
                if (!closed.get()) {
                    scheduleReconnect()
                }
            }
        }
        httpClient.newWebSocket(wsReq, listener)
        ready.await()
    }

    private fun scheduleReconnect() {
        // Jittered exponential backoff per spec
        // ("client SDK uses jittered exponential backoff
        //  starting at 2s, max 30s").
        if (closed.get()) return
        connectJob?.cancel()
        connectJob = scope.launch {
            var attempt = 0
            while (!closed.get() && socket == null) {
                val baseSec = min(30.0, 2.0 * Math.pow(2.0, attempt.toDouble())).toInt()
                val jitter = Random.nextDouble(0.0, baseSec * 0.3)
                delay(((baseSec + jitter) * 1000).toLong())
                attempt++
                runCatching { socketMutex.withLock { if (socket == null) connectInternal() } }
                    .onFailure { Timber.tag(TAG).w(it, "reconnect attempt %d failed", attempt) }
            }
        }
    }

    private fun handleIncomingFrame(raw: String) {
        val frame = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = frame.optString("type")
        val channelName = frame.optString("channel")
        when (type) {
            "open" -> channels[channelName]?._openDeferred?.complete(Unit)
            "close" -> {
                channels.remove(channelName)?._incoming?.tryEmit(ChannelEvent.Closed)
            }
            "message" -> {
                val payload = frame.optString("payload", "")
                if (payload.isNotEmpty()) {
                    val bytes = runCatching {
                        android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
                    }.getOrNull() ?: return
                    channels[channelName]?._incoming?.tryEmit(ChannelEvent.Message(bytes))
                }
            }
            "ack" -> {
                // V0.1: noop — we don't track per-frame acks at
                // the client level. SDK-side `await` on send is
                // a v0.2 follow-up.
            }
            "error" -> {
                val code = frame.optString("payload", "unknown")
                auditLogger.record(pluginRowId, "live_error_$code", channelName)
                channels[channelName]?._incoming?.tryEmit(ChannelEvent.Error(code))
            }
            "ping" -> {
                socket?.send(
                    JSONObject().apply {
                        put("channel", "")
                        put("type", "pong")
                    }.toString(),
                )
            }
            "pong" -> Unit
            else -> Timber.tag(TAG).w("unknown frame type: %s", type)
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        connectJob?.cancel()
        socket?.close(1000, "client_close")
        socket = null
        scope.cancel()
    }

    private inline fun <T> withCallSafe(block: () -> T): T = try {
        block()
    } catch (exc: LiveChannelException) {
        throw exc
    } catch (exc: Throwable) {
        throw LiveChannelException("network_error", exc.message ?: "")
    }

    companion object {
        private const val TAG = "LiveChannelClient"
        /** Spec docs/live-channel.md "Wire contract": 64 KB. */
        const val MAX_FRAME_BYTES: Int = 64 * 1024
    }
}

/**
 * Plugin-facing channel handle. Hot SharedFlow of inbound
 * events; suspending [send] for outbound; [close] removes
 * the channel from the multiplex.
 */
class LiveChannel internal constructor(
    val name: String,
    private val client: LiveChannelClient,
) {
    internal val _incoming = MutableSharedFlow<ChannelEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val incoming: SharedFlow<ChannelEvent> = _incoming.asSharedFlow()

    internal var _openDeferred: CompletableDeferred<Unit>? = null

    suspend fun send(envelope: ByteArray) {
        client.sendMessageFrame(name, envelope)
    }

    suspend fun close() {
        client.sendCloseFrame(name)
    }
}

sealed class ChannelEvent {
    data class Message(val envelopeBytes: ByteArray) : ChannelEvent()
    data class Error(val code: String) : ChannelEvent()
    object Closed : ChannelEvent()
}

class LiveChannelException(val code: String, message: String) : RuntimeException(message)
