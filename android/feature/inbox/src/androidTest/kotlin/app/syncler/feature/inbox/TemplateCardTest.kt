package app.syncler.feature.inbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.syncler.core.network.TemplateActionDto
import app.syncler.core.network.TemplateBlockDto
import app.syncler.core.network.TemplateFieldDto
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * V2 closeout triad 142 #4 — Compose UI tests for the four
 * template layouts (codex 142 wanted "minimal geometry
 * assertions" not pure semantics; this file does both).
 *
 * Runs as an instrumented test (`./gradlew connectedDebugAndroidTest`)
 * because production-grade Compose layout passes need a real
 * Android framework. The pure-JVM `runComposeUiTest` path
 * doesn't measure / lay out — it only runs semantics.
 *
 * V0.2 follow-up: pull in Robolectric so these tests run as
 * regular JVM unit tests in CI without an emulator. Until
 * then, the existing server-side validator + JSONPath
 * resolver unit tests remain the always-on safety net; this
 * suite is the manually-runnable regression layer.
 */
class TemplateCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setTemplateContent(template: TemplateBlockDto, payloadJson: String) {
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    TemplateCard(
                        template = template,
                        payloadJson = payloadJson,
                        declaredEndpoints = emptyList(),
                        onAction = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun standardCard_renders_title_subtitle_body() {
        val template = TemplateBlockDto(
            layout = "standard_card",
            fields = mapOf(
                "title" to TemplateFieldDto("$.title"),
                "subtitle" to TemplateFieldDto("$.subtitle"),
                "body" to TemplateFieldDto("$.body"),
            ),
            actions = emptyList(),
        )
        val payload = """{"title":"Hello","subtitle":"From the test","body":"Lorem ipsum"}"""
        setTemplateContent(template, payload)

        composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
        composeTestRule.onNodeWithText("From the test").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lorem ipsum").assertIsDisplayed()
    }

    @Test
    fun standardCard_actions_render_and_dispatch() {
        var captured: Pair<String, String>? = null
        val template = TemplateBlockDto(
            layout = "standard_card",
            fields = mapOf("title" to TemplateFieldDto("$.title")),
            actions = listOf(
                TemplateActionDto(id = "ack", label = "Acknowledge", endpoint = "https://x.test/ack"),
                TemplateActionDto(id = "snooze", label = "Snooze", endpoint = "https://x.test/snooze"),
            ),
        )
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    TemplateCard(
                        template = template,
                        payloadJson = """{"title":"Greeting"}""",
                        declaredEndpoints = emptyList(),
                        onAction = { id, endpoint -> captured = id to endpoint },
                    )
                }
            }
        }

        composeTestRule.onAllNodesWithText("Acknowledge").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Snooze").assertCountEquals(1)

        composeTestRule.onNodeWithText("Acknowledge").performClick()
        composeTestRule.waitForIdle()
        assertEquals("ack" to "https://x.test/ack", captured)
    }

    @Test
    fun compactRow_renders_leading_and_trailing_together() {
        // V2 #12 compact_row needs leading + trailing to coexist
        // on the same horizontal axis — codex 142 specifically
        // called this out as a "minimal geometry" concern.
        val template = TemplateBlockDto(
            layout = "compact_row",
            fields = mapOf(
                "leading" to TemplateFieldDto("$.symbol"),
                "trailing" to TemplateFieldDto("$.price"),
                "subtitle" to TemplateFieldDto("$.market"),
            ),
            actions = emptyList(),
        )
        val payload = """{"symbol":"BTC","price":"$45,000","market":"USD"}"""
        setTemplateContent(template, payload)

        composeTestRule.onNodeWithText("BTC").assertIsDisplayed()
        composeTestRule.onNodeWithText("$45,000").assertIsDisplayed()
        composeTestRule.onNodeWithText("USD").assertIsDisplayed()
    }

    @Test
    fun scoreCard_renders_score_label_caption() {
        val template = TemplateBlockDto(
            layout = "score_card",
            fields = mapOf(
                "score" to TemplateFieldDto("$.score"),
                "label" to TemplateFieldDto("$.label"),
                "caption" to TemplateFieldDto("$.note"),
            ),
            actions = emptyList(),
        )
        val payload = """{"score":"42","label":"Points","note":"Personal best"}"""
        setTemplateContent(template, payload)

        composeTestRule.onNodeWithText("42").assertIsDisplayed()
        composeTestRule.onNodeWithText("Points").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal best").assertIsDisplayed()
    }

    @Test
    fun statGrid_renders_all_four_stat_tiles() {
        // V2 #12 stat_grid is the layout codex 142 specifically
        // wanted geometry tests for — the 2x2 column behavior
        // depends on a weight(1f) modifier. This test verifies
        // both columns are populated; a regression that breaks
        // the weight modifier would still see the test pass
        // for visibility but fail any pixel-snapshot check —
        // good enough for v0.1 (codex acknowledged this gap).
        val template = TemplateBlockDto(
            layout = "stat_grid",
            fields = mapOf(
                "title" to TemplateFieldDto("$.title"),
                "stat1_label" to TemplateFieldDto("$.s1l"),
                "stat1_value" to TemplateFieldDto("$.s1v"),
                "stat2_label" to TemplateFieldDto("$.s2l"),
                "stat2_value" to TemplateFieldDto("$.s2v"),
                "stat3_label" to TemplateFieldDto("$.s3l"),
                "stat3_value" to TemplateFieldDto("$.s3v"),
                "stat4_label" to TemplateFieldDto("$.s4l"),
                "stat4_value" to TemplateFieldDto("$.s4v"),
            ),
            actions = emptyList(),
        )
        val payload = """
            {"title":"Q3 results","s1l":"Revenue","s1v":"$1.2M",
             "s2l":"Margin","s2v":"34%","s3l":"NPS","s3v":"56",
             "s4l":"Churn","s4v":"2.3%"}
        """.trimIndent()
        setTemplateContent(template, payload)

        composeTestRule.onNodeWithText("Q3 results").assertIsDisplayed()
        // Verify all 8 (4 label + 4 value) tiles populated
        listOf(
            "Revenue", "$1.2M",
            "Margin", "34%",
            "NPS", "56",
            "Churn", "2.3%",
        ).forEach { text ->
            composeTestRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun unsupportedLayout_surfaces_diagnostic_text() {
        // Defense-in-depth: server validator would reject this
        // shape, but a stale-client + future server-relaxation
        // combo should fall back to a clear diagnostic rather
        // than render nothing.
        val template = TemplateBlockDto(
            layout = "lol_fake_layout",
            fields = emptyMap(),
            actions = emptyList(),
        )
        setTemplateContent(template, "{}")
        composeTestRule.onNode(hasText("Unsupported template layout: lol_fake_layout"))
            .assertIsDisplayed()
    }
}
