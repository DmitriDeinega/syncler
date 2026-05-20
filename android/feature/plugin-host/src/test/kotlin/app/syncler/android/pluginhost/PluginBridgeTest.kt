package app.syncler.android.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginBridgeTest {
    @Test
    fun showNotificationDoesNotRequireBackgroundExecCapability() {
        assertNull(requiredCapability("platform.showNotification"))
    }

    @Test
    fun bridgeResultsAreWrappedInSuccessEnvelope() {
        val result = bridgeSuccessJson("""{"error":"domain_error","body":"not a bridge failure"}""")

        assertEquals(
            """{"success":true,"value":{"error":"domain_error","body":"not a bridge failure"}}""",
            result,
        )
    }

    @Test
    fun bridgeErrorsAreWrappedInFailureEnvelope() {
        val result = bridgeErrorJson("bridge_error", "boom")

        assertEquals("""{"success":false,"error":"bridge_error","message":"boom"}""", result)
    }
}
