package app.sleevesight.app.ui.home

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line under an insight written from evidence that holds nothing beyond what Home shows (#1142):
 * "We could not find more source information for the insight.", under the insight's text, in the
 * card's quiet note style (its secondary text colour, light and dark), read by a screen reader as
 * it is written; none under an insight whose evidence held more.
 */
@RunWith(RobolectricTestRunner::class)
class InsightNothingBeyondScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val note: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.home_insight_nothing_beyond)

    private var scheme: ColorScheme? = null
    private var typography: Typography? = null

    private fun show(
        nothingBeyond: Boolean,
        dark: Boolean = false,
        block: () -> Unit,
    ) {
        val insight = HomeFixtures.written(REPLY).copy(nothingBeyondHome = nothingBeyond)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = InsightOffers(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        scheme = MaterialTheme.colorScheme
                        typography = MaterialTheme.typography
                        Column {
                            InsightCard(insight, context, InsightActions({}, {}, {}, onReport = { _, _ -> }))
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun theLineIsUnderTheInsightsText() =
        show(nothingBeyond = true) {
            assertEquals("We could not find more source information for the insight.", note)
            val line = compose.onNodeWithTag(INSIGHT_NOTHING_BEYOND_TAG, useUnmergedTree = true)
            line.assertIsDisplayed()
            val text = compose.onNodeWithText(PROSE, substring = true, useUnmergedTree = true)
            assertTrue(
                "under the insight's text",
                line.getUnclippedBoundsInRoot().top >= text.getUnclippedBoundsInRoot().bottom,
            )
        }

    @Test
    fun aScreenReaderReadsItAsWritten() =
        show(nothingBeyond = true) {
            // Read as its own text, once, with nothing added or hidden.
            compose.onNodeWithText(note).assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithText(note).fetchSemanticsNodes().size)
        }

    @Test
    fun itIsInTheQuietNoteColourLightAndDark() {
        for (dark in listOf(false, true)) {
            show(nothingBeyond = true, dark = dark) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose
                    .onNodeWithText(note, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.GetTextLayoutResult]
                    .action
                    ?.invoke(layouts)
                val style = layouts.single().layoutInput.style
                val drawn = checkNotNull(scheme)
                assertEquals("dark $dark", drawn.onSurfaceVariant, style.color)
                assertEquals("dark $dark", checkNotNull(typography).bodyMedium.fontSize, style.fontSize)
            }
        }
    }

    @Test
    fun noLineWhenTheEvidenceHeldMore() =
        show(nothingBeyond = false) {
            compose.onNodeWithText(PROSE, substring = true).assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText(note).fetchSemanticsNodes().size)
        }

    private companion object {
        const val PROSE = "Sundown was recorded in 1973"
        const val REPLY = "$PROSE [MB1]."
    }
}
