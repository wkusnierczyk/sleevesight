package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
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
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The footer under a written insight (#606, from #598): the grounding line, and the rewrite's lines
 * under it, with *Report a problem* at the row's end beside them, at the usual font size; at twice
 * the size, where the action beside them left the line about a word a line, the action under the
 * last line, still at the row's end, and the lines on the whole row. A screen reader reads every
 * line, then the action. Text measured as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightFooterLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        details: Boolean,
        rewrite: Boolean = false,
        block: (MainActivity) -> Unit,
    ) {
        val insight = HomeFixtures.written("Sundown was recorded in 1973 [MB1]. It is a classic.", cached = true)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = InsightOffers(rewrite = rewrite),
                // The card's rewrite stopped, and *Rewrite insight* offered again.
                rewriteFailed = PipelineHost.STOPPED.takeIf { rewrite },
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
            scenario.onActivity(block)
        }
    }

    private fun node(text: String) =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()

    private fun bounds(text: String): Rect = node(text).boundsInRoot

    private val reportNode get() = compose.onNodeWithTag(REPORT_TAG).fetchSemanticsNode()

    private val report get() = reportNode.boundsInRoot

    private val card get() = compose.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot

    private val density get() = compose.density.density

    @Test
    fun atTheUsualFontSizeReportAProblemIsBesideTheGroundingLine() {
        for ((details, line) in listOf(false to NOTE, true to META)) {
            show(details) { _ ->
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
            show(details) { _ ->
                val text = bounds(line)
                val action = report
                assertTrue("$line: the action under the line", action.top >= text.bottom - 1f)
                assertEquals("$line: the action at the row's end", card.right, action.right, 1f)
                assertTrue("$line: the line wider than half the row", text.width > card.width / 2)
                assertTrue("$line: the action still 48 dp high", action.height / density >= 48f - 0.5f)
            }
        }
    }

    @Test
    fun withTheRewritesLinesReportAProblemIsCentredBesideThemAll() =
        show(details = false, rewrite = true) { _ ->
            val first = bounds(NOTE)
            val last = bounds(OFFERED)
            val action = report
            val lines = maxOf(first.right, bounds(STOPPED).right, last.right)
            assertTrue("the action after every line", action.left >= lines)
            assertEquals("the action centred on the lines", (first.top + last.bottom) / 2, action.center.y, density)
        }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeReportAProblemIsUnderTheRewritesLastLine() =
        show(details = false, rewrite = true) { _ ->
            val action = report
            assertTrue("the action under the last line", action.top >= bounds(OFFERED).bottom - 1f)
            assertEquals("the action at the row's end", card.right, action.right, 1f)
        }

    // Centred on the lines, the action sat level with the stopped line, and a screen reader read it
    // between that line and the offer to rewrite.
    @Test
    fun aScreenReaderReadsEveryLineThenReportAProblem() {
        val accessibility =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        // A screen reader running: Compose builds nodes only for an enabled service.
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            show(details = false, rewrite = true) { activity ->
                val provider = checkNotNull(composeView(activity.window.decorView).accessibilityNodeProvider)
                val ids = listOf(NOTE, STOPPED, OFFERED).map { node(it).id } + reportNode.id
                // Each node names the one read after it.
                val next = ids.map { checkNotNull(provider.createAccessibilityNodeInfo(it)).extras.getInt(BEFORE) }
                assertEquals("at $scale: the lines, then the action", ids.drop(1), next.dropLast(1))
                // Both layouts: beside the lines at the usual size, under them at twice the size.
                val under = report.top >= bounds(OFFERED).bottom - 1f
                assertEquals("at $scale: the action under the lines", scale > 1f, under)
            }
        }
    }

    // The view Compose draws into, under [root].
    private fun composeView(root: View): View =
        if (root.javaClass.simpleName == "AndroidComposeView") {
            root
        } else {
            (root as ViewGroup).children.firstNotNullOf { child -> runCatching { composeView(child) }.getOrNull() }
        }

    private companion object {
        const val CARD = "card"
        const val NOTE = "Kept from before."
        const val META = "kept from before"
        const val STOPPED = "Rewriting stopped."
        const val OFFERED = "Rewrite insight with"

        // Where Compose puts, for tests, the node a screen reader reads after a node.
        const val BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
    }
}
