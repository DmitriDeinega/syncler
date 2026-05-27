package app.syncler.core.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * V4 #20 — in-memory authorization flag for sensitive actions.
 *
 * Triad 166 agreement: the user/app — NOT the plugin author — owns the
 * sensitive-action timeout. Plugin manifests can DECLARE sensitivity
 * (so the inbox knows to render a locked tile, and the open path knows
 * to consult this gate), but they CANNOT extend the timeout window.
 *
 * Lifecycle:
 *
 * - All state is in-memory. Process death wipes [unlockedUntil] so a
 *   cold start always re-prompts on the first sensitive action.
 * - [unlockFromUserAuth] is called by the biometric / device-credential
 *   / password prompt host AFTER a successful authentication. Starts
 *   the sliding window.
 * - [touchFromForegroundSensitiveView] slides the window forward when a
 *   user-driven sensitive view succeeds. Codex 166 specifically asked
 *   for this name to make abuse-shape obvious: only the foreground UI
 *   layer should call it. Background jobs, notification handlers, plugin
 *   code, and live-card refreshers MUST NOT call it.
 * - [lockNow] is the "Lock sensitive actions" Settings affordance.
 *   Clears the in-memory flag without touching the persisted master key.
 *   Distinct from [Session.logout] (gemini 166 demanded the two
 *   affordances be separate).
 *
 * Timeout policy:
 *
 * - Default 300s (5 min). Codex 166 / gemini 166 both rejected
 *   per-plugin overrides longer than the user/app policy. V1 ships
 *   with a single global timeout; the plugin manifest's optional
 *   ``sensitiveActionTimeoutSeconds`` (when we add it) will be
 *   clamped to ≤ this constant.
 */
@Singleton
class SensitiveActionGate @Inject constructor() {
    private val defaultTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
    private val clock: Clock = Clock.systemUTC()

    private val state = MutableStateFlow<Instant?>(null)

    /**
     * Cold subscribers receive a Boolean reflecting the current
     * unlocked/locked state at subscription time. The flow re-emits
     * on every transition; consumers should NOT depend on it for
     * timing precision (use a poll on action).
     */
    val isUnlockedFlow: StateFlow<Instant?> = state.asStateFlow()

    /**
     * Snapshot check. Cheap (no IO); call this inline before any
     * sensitive action to decide "prompt or proceed".
     */
    fun isUnlocked(): Boolean {
        val deadline = state.value ?: return false
        return clock.instant().isBefore(deadline)
    }

    /**
     * Set or extend the unlock window. Called by the biometric prompt
     * host after the user authenticates successfully.
     */
    fun unlockFromUserAuth(timeout: Duration = defaultTimeout) {
        state.value = clock.instant().plus(timeout)
    }

    /**
     * Slide the unlock window forward by [timeout] from now. ONLY safe
     * to call from foreground user-driven access (codex 166 explicitly
     * called out background-process abuse). No-op when the gate is
     * currently locked, so a background touch can never trigger an
     * unlock as a side effect.
     */
    fun touchFromForegroundSensitiveView(timeout: Duration = defaultTimeout) {
        if (!isUnlocked()) return
        state.value = clock.instant().plus(timeout)
    }

    /**
     * Explicit re-lock affordance. Used by the Settings "Lock sensitive
     * actions" button (NOT "Sign out" — that's [Session.logout], which
     * also wipes the persisted master key).
     */
    fun lockNow() {
        state.value = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 300L
    }
}
