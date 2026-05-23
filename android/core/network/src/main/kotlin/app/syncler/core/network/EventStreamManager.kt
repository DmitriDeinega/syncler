package app.syncler.core.network

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Lifecycle: [start] is idempotent (no-op if a stream is already open);
 * [stop] cancels the current stream cleanly. The Android UI layer
 * binds these to `Lifecycle.Event.ON_RESUME` / `ON_PAUSE`.
 *
 * Replaces the 15-second polling loop that lived in `InboxScreen.kt`'s
 * `LaunchedEffect`. Pulls now happen reactively: a server event nudges
 * the relevant repository (`InboxRepository.refresh`,
 * `UserStateRepository.pull`) to re-fetch on demand. Background
 * delivery still goes through FCM — SSE is the foreground-only,
 * sub-second freshness path (per consultations 46/47/48).
 */
@Singleton
class EventStreamManager @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokenProviders: Set<@JvmSuppressWildcards AuthTokenProvider>,
) {
    private val _events = MutableSharedFlow<ServerEvent>(
        // No replay — collectors only care about live events from the moment
        // they subscribe. An events-since-resume catchup is what `pull` is
        // for; we never want a stale `inbox.changed` triggering a refresh
        // long after the actual message was already pulled.
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    @Volatile
    private var current: EventSource? = null

    private val sseFactory by lazy {
        // SSE streams are long-lived; OkHttp's default 10s read timeout
        // would kill an idle (heartbeat-only) connection. Set readTimeout
        // to 0 (infinite) on a derived client so SSE can stay open.
        val sseClient = httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
        EventSources.createFactory(sseClient)
    }

    /**
     * Open a stream if one isn't already open. Returns immediately;
     * the stream runs in the background and surfaces events through
     * the [events] flow. Reconnects on transient failures are handled
     * by the OkHttp client; semantic failures (401 auth) are NOT
     * retried — the app must log in again.
     */
    fun start() {
        if (current != null) return
        val token = tokenProviders.firstNotNullOfOrNull { it.currentToken() }
        if (token.isNullOrBlank()) {
            Timber.tag(TAG).w("start() called without a session token; ignoring")
            return
        }
        val request = Request.Builder()
            .url(BuildConfig.SERVER_BASE_URL.trimEnd('/') + "/v1/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        current = sseFactory.newEventSource(request, listener)
        Timber.tag(TAG).i("event stream opened")
    }

    /** Cancel the current stream. Safe to call when no stream is open. */
    fun stop() {
        current?.cancel()
        current = null
        Timber.tag(TAG).i("event stream closed")
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            Timber.tag(TAG).d("SSE open (status=%d)", response.code)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val parsed = ServerEvent.parse(type, data)
            if (parsed == null) {
                Timber.tag(TAG).w("ignoring SSE event with unknown/bad payload: type=%s", type)
                return
            }
            // tryEmit because we never want a slow collector to block the
            // OkHttp dispatcher thread. The buffer is sized so the bound
            // shouldn't be hit in practice.
            val accepted = _events.tryEmit(parsed)
            if (!accepted) {
                Timber.tag(TAG).w("SSE event dropped — buffer full: %s", type)
            }
        }

        override fun onClosed(eventSource: EventSource) {
            Timber.tag(TAG).i("SSE closed by server")
            current = null
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            // 401 = bootstrap-token, device-revoked, or other auth failure —
            // there's no point retrying without re-login. Other failures are
            // transient (network blip, server restart) and clearing
            // `current` lets the next `start()` reopen.
            if (response?.code == 401) {
                Timber.tag(TAG).w("SSE rejected with 401; not retrying — re-login required")
            } else {
                Timber.tag(TAG).w(t, "SSE failure (status=%s)", response?.code)
            }
            current = null
        }
    }

    private companion object {
        const val TAG = "EventStream"
    }
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
