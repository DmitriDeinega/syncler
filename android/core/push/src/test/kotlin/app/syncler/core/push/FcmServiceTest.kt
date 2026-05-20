package app.syncler.core.push

import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FcmServiceTest {

    @Test
    fun `dismiss outcome is constructed correctly`() = runTest {
        // Sanity check that the outcome data classes work as designed.
        val outcome = PluginMessageOutcome(
            notification = PluginNotificationRequest(
                title = "Hello",
                body = "World",
                groupId = "sender::plugin",
            ),
        )
        assertEquals("Hello", outcome.notification?.title)
        assertEquals("sender::plugin", outcome.notification?.groupId)
        assertEquals(false, outcome.requiresUpdate)
    }

    @Test
    fun `update-required outcome carries required version`() {
        val outcome = PluginMessageOutcome(
            requiresUpdate = true,
            requiredVersion = "1.1.0",
        )
        assertEquals(true, outcome.requiresUpdate)
        assertEquals("1.1.0", outcome.requiredVersion)
        assertEquals(null, outcome.notification)
    }
}
