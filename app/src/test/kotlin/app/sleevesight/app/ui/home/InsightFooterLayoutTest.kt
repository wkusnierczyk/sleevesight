package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The footer under a written insight (#606, from #598): the grounding line with *Report a problem*
 * at the row's end beside it, at the usual font size; at twice the size, where the action beside
 * it left the line about a word a line, the action under the line, still at the row's end, and the
 * line on the whole row. Text measured as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightFooterLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        details: Boolean,
        block: () -> Unit,
    ) {
        val insight = HomeFixtures.written("Sundown was recorded in 1973 [MB1]. It is a classic.", cached = true)
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
                    SleevesightTheme(darkTheme = false) {
                        Column(Modifier.padding(16.dp).fillMaxWidth().testTag(CARD)) {
                            InsightCard(
                                insight,
                                context,
                                InsightActions({}, {}, {}, onReport = { _, _ -> }),
                                groundingDetails = details,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun bounds(text: String): Rect =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val report get() = compose.onNodeWithTag(REPORT_TAG).fetchSemanticsNode().boundsInRoot

    private val card get() = compose.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot

    private val density get() = compose.density.density

    @Test
    fun atTheUsualFontSizeReportAProblemIsBesideTheGroundingLine() {
        for ((details, line) in listOf(false to NOTE, true to META)) {
            show(details) {
                val text = bounds(line)
                val action = report
                assertTrue("$line: the action after the line", action.left >= text.right)
                assertEquals("$line: the action at the row's end", card.right, action.right, 1f)
                assertEquals("$line: the two centred on each other", text.center.y, action.center.y, density)
            }
        }
    }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeReportAProblemIsUnderTheGroundingLine() {
        for ((details, line) in listOf(false to NOTE, true to META)) {
            show(details) {
                val text = bounds(line)
                val action = report
                assertTrue("$line: the action under the line", action.top >= text.bottom - 1f)
                assertEquals("$line: the action at the row's end", card.right, action.right, 1f)
                assertTrue("$line: the line wider than half the row", text.width > card.width / 2)
                assertTrue("$line: the action still 48 dp high", action.height / density >= 48f - 0.5f)
            }
        }
    }

    private companion object {
        const val CARD = "card"
        const val NOTE = "Kept from before."
        const val META = "kept from before"
    }
}
