package app.syncler.core.network

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import timber.log.Timber

/**
 * Phase 2: server event stream client.
 *
 * Opens a Server-Sent Events stream to `GET /v1/events` while the app
 * is in the foreground, and translates incoming events into a
 * [SharedFlow<ServerEvent>] that feature-layer collectors subscribe to.
 *
 * Lifecycle: [start] is idempotent (no-op if a stream is already open
 * or a reconnect is scheduled); [stop] cancels the current stream AND
 * any pending reconnect cleanly. The Android UI layer binds these to
 * `Lifecycle.Event.ON_RESUME` / `ON_PAUSE`.
 *
 * Reactive freshness model replaces the V1 polling loop:
 *  - SSE delivers hints (inbox.changed, state.changed, dismiss) while
 *    the app is in foreground.
 *  - Transient network failures auto-reconnect with exponential backoff
 *    (Codex consultation 56 RED #13) so a network flip doesn't strand
 *    the user until the next lifecycle event.
 *  - 401 (bootstrap token / device-revoked / other auth failure) is
 *    treated as terminal: the manager calls `onAuthFailure` so the
 *    auth layer can clear the session and route to login.
 *  - FCM remains the background-wakeup path (Doze kills any
 *    long-lived stream the moment the app backgrounds anyway).
 */
@Singleton
class EventStreamManager @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokenProviders: Set<@JvmSuppressWildcards AuthTokenProvider>,
    private val authFailureHandler: AuthFailureHandler,
) {
    private val _events = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /**
     * Emits Unit once the SSE handshake actually completes (server
     * acknowledged the subscription). Collectors use this to do a
     * "catchup" refresh AFTER the stream is open — closes the small
     * race where `start()` returns before subscription is live and an
     * event landed during that gap is missed (Codex consultation 56
     * RED #9).
     */
    private val _streamReady = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val streamReady: SharedFlow<Unit> = _streamReady.asSharedFlow()

    @Volatile
    private var current: EventSource? = null

    /**
     * Tracks whether we've been asked to be running by lifecycle.
     * Reconnect attempts only fire while this is true so a backgrounded
     * app doesn't spin up new streams via the retry path.
     */
    @Volatile
    private var wantRunning: Boolean = false

    /**
     * Active reconnect job. Cancelled on `stop()` so a queued retry
     * after pause doesn't open a stream the user can't see.
     */
    private var reconnectJob: Job? = null

    /**
     * Current reconnect attempt count. Reset on successful onOpen.
     * Used to compute exponential backoff (1s, 2s, 4s, 8s, 16s, 30s cap).
     */
    @Volatile
    private var reconnectAttempt: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sseFactory by lazy {
        val sseClient = httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
        EventSources.createFactory(sseClient)
    }

    /**
     * Open a stream if one isn't already open. Returns immediately;
     * the stream runs in the background and surfaces events through
     * the [events] flow. Collectors that need a "stream is now live"
     * signal observe [streamReady].
     */
    fun start() {
        wantRunning = true
        openStream()
    }

    /** Cancel the current stream + any pending reconnect. */
    fun stop() {
        wantRunning = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        current?.cancel()
        current = null
        Timber.tag(TAG).i("event stream closed")
    }

    private fun openStream() {
        if (current != null) return
        val token = tokenProviders.firstNotNullOfOrNull { it.currentToken() }
        if (token.isNullOrBlank()) {
            Timber.tag(TAG).w("openStream() called without a session token; ignoring")
            return
        }
        val request = Request.Builder()
            .url(BuildConfig.SERVER_BASE_URL.trimEnd('/') + "/v1/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        current = sseFactory.newEventSource(request, listener)
        Timber.tag(TAG).i("event stream opening (attempt=%d)", reconnectAttempt)
    }

    private fun scheduleReconnect() {
        if (!wantRunning) return
        if (reconnectJob?.isActive == true) return
        val attempt = ++reconnectAttempt
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 30s.
        val delayMs = min(1_000L * (1L shl (attempt - 1).coerceAtMost(5)), 30_000L)
        Timber.tag(TAG).i("scheduling SSE reconnect attempt=%d in %dms", attempt, delayMs)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wantRunning) openStream()
        }
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            Timber.tag(TAG).d("SSE open (status=%d)", response.code)
            reconnectAttempt = 0
            // Signal that the subscription is live. Collectors do a
            // catchup refresh in response so any event that landed
            // between the start() catchup and stream-open is picked up.
            _streamReady.tryEmit(Unit)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val parsed = ServerEvent.parse(type, data)
            if (parsed == null) {
                Timber.tag(TAG).w("ignoring SSE event with unknown/bad payload: type=%s", type)
                return
            }
            val accepted = _events.tryEmit(parsed)
            if (!accepted) {
                Timber.tag(TAG).w("SSE event dropped — buffer full: %s", type)
            }
        }

        override fun onClosed(eventSource: EventSource) {
            Timber.tag(TAG).i("SSE closed by server")
            current = null
            // Server-initiated close. Common reason: stream max-age
            // reached (just below JWT TTL). Reconnect with the current
            // token; if the token is also expired the next handshake
            // gets 401 and the auth-failure path fires.
            if (wantRunning) scheduleReconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            current = null
            val code = response?.code
            if (code == 401) {
                // Terminal auth failure: bootstrap-only token, revoked
                // device, or expired-and-not-refreshed JWT. Stop the
                // reconnect machinery and let the auth layer route to
                // the login screen.
                Timber.tag(TAG).w("SSE rejected with 401; signalling auth failure")
                wantRunning = false
                reconnectJob?.cancel()
                reconnectJob = null
                authFailureHandler.onAuthFailure()
                return
            }
            Timber.tag(TAG).w(t, "SSE failure (status=%s); will retry", code)
            if (wantRunning) scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "EventStream"
    }
}

/**
 * Implemented by the auth layer (`AuthRepository` is the production
 * binding). EventStreamManager calls [onAuthFailure] when the server
 * rejects the SSE handshake with 401 — the auth layer is responsible
 * for clearing the session and surfacing the login UI.
 *
 * Defined as an interface in `:core:network` so this module doesn't
 * depend back on `:core:auth`.
 */
interface AuthFailureHandler {
    fun onAuthFailure()
}

/**
 * A decoded SSE event from `/v1/events`. The server publishes hints
 * (not authoritative data) — collectors react by calling the existing
 * REST endpoints to fetch the actual content.
 */
sealed class ServerEvent {
    /** A new inbox message landed for this user. Pull the inbox. */
    data class InboxChanged(val messageId: String?, val sentAt: String?) : ServerEvent()

    /** The encrypted user-state blob changed. Pull /v1/state. */
    data class StateChanged(val version: Int) : ServerEvent()

    /** Another device dismissed a message. Update local state. */
    data class Dismiss(val messageId: String, val sourceDeviceId: String?) : ServerEvent()

    companion object {
        fun parse(type: String?, data: String): ServerEvent? = runCatching {
            val obj = JSONObject(data)
            when (type) {
                "inbox.changed" -> InboxChanged(
                    messageId = obj.optString("message_id").takeIf { it.isNotEmpty() },
                    sentAt = obj.optString("sent_at").takeIf { it.isNotEmpty() },
                )
                "state.changed" -> StateChanged(version = obj.getInt("version"))
                "dismiss" -> Dismiss(
                    messageId = obj.getString("message_id"),
                    sourceDeviceId = obj.optString("source_device_id").takeIf { it.isNotEmpty() },
                )
                else -> null
            }
        }.getOrNull()
    }
}
