package app.syncler.core.auth

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionGateTest {

    @Test
    fun startsLocked() {
        val gate = SensitiveActionGate()
        assertFalse(gate.isUnlocked())
    }

    @Test
    fun unlockFromUserAuthOpensTheGate() {
        val gate = SensitiveActionGate()
        gate.unlockFromUserAuth()
        assertTrue(gate.isUnlocked())
    }

    @Test
    fun lockNowClosesTheGate() {
        val gate = SensitiveActionGate()
        gate.unlockFromUserAuth()
        assertTrue(gate.isUnlocked())

        gate.lockNow()
        assertFalse(gate.isUnlocked())
    }

    /**
     * Triad 166 / codex specifically called out: a background process
     * calling `touch...` while the gate is locked MUST NOT side-effect
     * into an unlock. Defends against the abuse shape where some
     * background work indirectly re-opens the gate.
     */
    @Test
    fun touchOnLockedGateDoesNotUnlock() {
        val gate = SensitiveActionGate()
        gate.touchFromForegroundSensitiveView()
        assertFalse(gate.isUnlocked())
    }

    /**
     * Sliding window UX: while unlocked, every touch from foreground
     * sensitive view extends the deadline. Two touches in quick
     * succession should leave the gate unlocked.
     */
    @Test
    fun touchExtendsTheDeadlineWhileUnlocked() {
        val gate = SensitiveActionGate()
        gate.unlockFromUserAuth(Duration.ofSeconds(1))
        gate.touchFromForegroundSensitiveView(Duration.ofSeconds(30))
        assertTrue(gate.isUnlocked())
    }

    @Test
    fun defaultTimeoutMatchesAgreedConstant() {
        // Triad 166 agreed value. Bumping this requires a follow-up
        // triad — codex/gemini both objected to >600s timeouts.
        assertEquals(300L, SensitiveActionGate.DEFAULT_TIMEOUT_SECONDS)
    }
}
