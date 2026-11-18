package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SearchAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Citation chips in text of the other direction than the layout's (#1350): an English paragraph in
 * a right-to-left layout ends its sentence after its last chip, as it does left to right, in the
 * lyrics explanation and in an insight alike; a right-to-left paragraph keeps its chips in its own
 * reading order. Text measured as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CitationDirectionTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val pages =
        listOf(CitedPage("S1", "https://example.com/a", "a"), CitedPage("S2", "https://example.org/b", "b"))

    private fun show(
        direction: LayoutDirection,
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        SleevesightTheme(darkTheme = false) { Column(Modifier.padding(24.dp)) { content() } }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun grounded(reply: String) =
        (GroundingCheck.check(reply, pages.map { it.id }.toSet(), allowModel = false) as InsightReply.Written).grounded

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single()
    }

    // The text's full stop, on the line of the last chip, to the right of its id's last letter.
    private fun assertStopAfterLastChip(node: SemanticsNodeInteraction) {
        val text =
            node
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
                .text
        val layout = layout(node)
        val stop = text.lastIndexOf('.')
        val lastId = text.lastIndexOf("S2") + 1
        assertEquals(layout.getLineForOffset(lastId), layout.getLineForOffset(stop))
        val chipEnd = layout.getBoundingBox(lastId).right
        val stopLeft = layout.getBoundingBox(stop).left
        assertTrue("the full stop ($stopLeft) is after the last chip ($chipEnd)", stopLeft >= chipEnd)
    }

    private val english = "A Brazilian blog hears it as a song for the road. [S1] [S2]"

    @Test
    fun anEnglishExplanationInARightToLeftLayoutEndsAfterItsLastChip() =
        show(LayoutDirection.Rtl, { AiExplanation(SearchAnswer.Explanation(grounded(english), pages)) }) {
            assertStopAfterLastChip(compose.onNodeWithTag(LYRICS_EXPLANATION_TAG))
        }

    @Test
    fun anEnglishInsightInARightToLeftLayoutEndsAfterItsLastChip() {
        val written = grounded(english)
        show(LayoutDirection.Rtl, { CheckedProse(written.paragraphs.single(), written, null, {}) }) {
            assertStopAfterLastChip(compose.onNode(hasText("song for the road", substring = true)))
        }
    }

    @Test
    fun aRightToLeftExplanationKeepsItsChipsInItsReadingOrder() {
        val hebrew = grounded("השיר עוסק בדרך. [S1] [S2]")
        show(LayoutDirection.Ltr, { AiExplanation(SearchAnswer.Explanation(hebrew, pages)) }) {
            val node = compose.onNodeWithTag(LYRICS_EXPLANATION_TAG)
            val text =
                node
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .first()
                    .text
            val layout = layout(node)
            // Read right to left, the first chip comes first: to the right of the second.
            val first = layout.getBoundingBox(text.indexOf("S1")).left
            val second = layout.getBoundingBox(text.indexOf("S2")).right
            assertTrue("S1 ($first) is right of S2 ($second)", first >= second)
        }
    }
}
