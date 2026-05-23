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

    /**
     * Single monitor that serializes every lifecycle/state transition:
     * `start`, `stop`, `openStream`, `scheduleReconnect`, and the
     * mutating portions of the SSE listener callbacks (`onOpen`,
     * `onClosed`, `onFailure`). Closes the Codex consultation 58
     * compound-transition race where stop() interleaved with openStream()
     * could leave `wantRunning == true` with `current == null` and no
     * reconnect scheduled.
     *
     * The lock is uncontended in the common case (one open stream, one
     * lifecycle thread, occasional okhttp dispatcher callbacks) and
     * never held across a suspend point — all suspending work (`delay`
     * in reconnect) happens outside the lock.
     */
    private val lifecycleLock = Any()

    /**
     * The EventSource we currently consider "ours". Mutated only while
     * holding [lifecycleLock]; reads are also locked except for the
     * identity check in `onEvent` (the high-frequency hot path), where
     * volatility is enough — a stale read just drops the event safely.
     */
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
        synchronized(lifecycleLock) {
            wantRunning = true
            openStreamLocked()
        }
    }

    /** Cancel the current stream + any pending reconnect. */
    fun stop() {
        val prev = synchronized(lifecycleLock) {
            wantRunning = false
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            val p = current
            current = null
            p
        }
        prev?.cancel()
        Timber.tag(TAG).i("event stream closed")
    }

    private fun openStreamLocked() {
        if (!wantRunning) return
        if (current != null) return
        val token = tokenProviders.firstNotNullOfOrNull { it.currentToken() }
        if (token.isNullOrBlank()) {
            Timber.tag(TAG).w("openStreamLocked() called without a session token; ignoring")
            return
        }
        val request = Request.Builder()
            .url(BuildConfig.SERVER_BASE_URL.trimEnd('/') + "/v1/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        // Listener is per-stream so it captures the token used for this
        // handshake; the 401 path passes that token back through
        // AuthFailureHandler so the auth layer can ignore stale 401s
        // for tokens the session no longer holds (Codex 57 RED #12).
        // newEventSource returns immediately; the actual handshake runs
        // asynchronously on the okhttp dispatcher.
        val source = sseFactory.newEventSource(request, makeListener(token))
        current = source
        Timber.tag(TAG).i("event stream opening (attempt=%d)", reconnectAttempt)
    }

    private fun scheduleReconnectLocked() {
        if (!wantRunning) return
        if (reconnectJob?.isActive == true) return
        val attempt = ++reconnectAttempt
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, capped at 30s.
        val delayMs = min(1_000L * (1L shl (attempt - 1).coerceAtMost(5)), 30_000L)
        Timber.tag(TAG).i("scheduling SSE reconnect attempt=%d in %dms", attempt, delayMs)
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lifecycleLock) {
                if (wantRunning) openStreamLocked()
            }
        }
    }

    private fun makeListener(token: String) = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            val accepted = synchronized(lifecycleLock) {
                if (current !== eventSource) false
                else {
                    reconnectAttempt = 0
                    true
                }
            }
            if (!accepted) return
            Timber.tag(TAG).d("SSE open (status=%d)", response.code)
            // Emit outside the lock — collectors may do work in their
            // tryEmit handler; no need to hold the lifecycle lock across.
            _streamReady.tryEmit(Unit)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            // Hot path: lock-free identity check. A stale read drops the
            // event safely; correctness is preserved by the mutating
            // callbacks which take the lock.
            if (current !== eventSource) return
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
            synchronized(lifecycleLock) {
                if (current !== eventSource) return@synchronized
                current = null
                Timber.tag(TAG).i("SSE closed by server")
                if (wantRunning) scheduleReconnectLocked()
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            var terminal401 = false
            synchronized(lifecycleLock) {
                if (current !== eventSource) return@synchronized
                current = null
                val code = response?.code
                terminal401 = (code == 401)
                if (terminal401) {
                    Timber.tag(TAG).w("SSE rejected with 401; signalling auth failure")
                } else {
                    Timber.tag(TAG).w(t, "SSE failure (status=%s); will retry", code)
                }
                // Do NOT short-circuit reconnect on 401. Two cases:
                //  - Stale 401 (session token rolled by a fast re-login):
                //    the next reconnect picks up the new token and
                //    succeeds. AuthRepository.onAuthFailure verifies the
                //    token is still current before logging out.
                //  - Real terminal 401: AuthRepository.onAuthFailure
                //    triggers session.logout(), which clears the token.
                //    The next reconnect attempt sees a null token and
                //    no-ops; lifecycle teardown (InboxScreen ON_PAUSE
                //    when AuthScreen takes over) sets wantRunning=false.
                // Closes Codex consultation 58 concern: a stale 401 must
                // not strand SSE in a stopped state for an unrelated
                // session that's still alive.
                if (wantRunning) scheduleReconnectLocked()
            }
            // Auth handler call happens outside the lock — it does
            // scope.launch internally and we don't want any handler
            // ordering effects to hold the lifecycle lock.
            if (terminal401) {
                authFailureHandler.onAuthFailure(token)
            }
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
 * [failedToken] is the bearer token that triggered the 401. The
 * implementation MUST verify this still matches the session's current
 * token before logging out — otherwise a stale 401 from a stream that
 * was already replaced (e.g., after a fast re-login) could wipe the
 * newer session (Codex consultation 57 RED #12).
 *
 * Defined as an interface in `:core:network` so this module doesn't
 * depend back on `:core:auth`.
 */
interface AuthFailureHandler {
    fun onAuthFailure(failedToken: String)
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
