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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import kotlin.math.abs

/**
 * The footer under a written insight (#671, which replaced #606's row): each part on its own line,
 * at the start, top to bottom: *As written previously.* with what follows from it, the grounding
 * line (the note on the dimmed sentences, or in developer mode the counts), then *Report
 * a problem*, its label where the lines start, in the theme's error colour, light and dark. At twice
 * the font size each line wraps on its own, on the whole row. A screen reader reads them in that
 * order. Text measured as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightFooterLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // The theme's error colour, as the card was drawn in it.
    private var error = Color.Unspecified

    private fun show(
        details: Boolean,
        dark: Boolean = false,
        block: (MainActivity) -> Unit,
    ) {
        // The second sentence cites nothing: dimmed, so the note shows.
        val insight = HomeFixtures.written("Sundown was recorded in 1973 [MB1]. It is a classic.", cached = true)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = InsightOffers(rewrite = true),
                // The card's rewrite stopped, and *Rewrite insight* offered again: the longest line 1.
                rewriteFailed = PipelineHost.STOPPED,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        error = MaterialTheme.colorScheme.error
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

    private fun assertEachOnItsOwnLineAtTheStart(scale: Float) {
        for ((details, grounding) in listOf(false to NOTE, true to COUNTS)) {
            show(details) { _ ->
                val first = bounds(BEFORE)
                val second = bounds(grounding)
                val action = report
                val where = "$grounding at $scale"
                assertTrue("$where: the grounding line under line 1", second.top >= first.bottom - 1f)
                assertTrue("$where: Report a problem under the grounding line", action.top >= second.bottom - 1f)
                assertEquals("$where: line 1 at the start", card.left, first.left, 1f)
                // In developer mode its mark's outline sits round it, in the same column.
                assertEquals("$where: the grounding line at the start", card.left, second.left, density * 2)
                // The action's label, inside the button, where the lines start.
                assertEquals("$where: Report a problem's label at the start", card.left, bounds(REPORT).left, 1f)
                // Its 48 dp target, which its semantic bounds (the button drawn) leave out, reserved
                // under the line above, to the card's end, where the action is last.
                assertTrue("$where: a 48 dp target", (card.bottom - second.bottom) / density >= 48f - 0.5f)
                assertTrue("$where: the action inside it", action.bottom <= card.bottom + 1f)
            }
        }
    }

    @Test
    fun atTheUsualFontSizeEachPartIsOnItsOwnLineAtTheStart() = assertEachOnItsOwnLineAtTheStart(1f)

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeEachPartIsOnItsOwnLineAtTheStart() = assertEachOnItsOwnLineAtTheStart(2f)

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeEachLineWrapsOnTheWholeRow() =
        show(details = false) { _ ->
            val first = bounds(BEFORE)
            // Line 1 wraps (several lines high), on the whole row rather than beside the action.
            assertTrue("line 1 wraps", first.height > bounds(REPORT).height * 2)
            assertTrue("line 1 on more than half the row", first.width > card.width / 2)
            assertTrue("the note on more than half the row", bounds(NOTE).width > card.width / 2)
        }

    @Test
    fun reportAProblemIsInTheThemesErrorColourLightAndDark() {
        for (dark in listOf(false, true)) {
            show(details = false, dark = dark) { _ ->
                val pixels = compose.onNodeWithTag(REPORT_TAG).captureToImage().toPixelMap()
                val drawn = (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y] } }
                // The label's strokes are drawn solid in the error colour.
                assertTrue("dark $dark: the label in $error", drawn.any { close(it, error) })
            }
        }
    }

    private fun close(
        a: Color,
        b: Color,
    ): Boolean = listOf(a.red - b.red, a.green - b.green, a.blue - b.blue).all { abs(it) < TOLERANCE }

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
            show(details = false) { activity ->
                val provider = checkNotNull(composeView(activity.window.decorView).accessibilityNodeProvider)
                val ids = listOf(BEFORE, NOTE).map { node(it).id } + reportNode.id
                // Each node names the one read after it.
                val next = ids.map { checkNotNull(provider.createAccessibilityNodeInfo(it)).extras.getInt(AFTER) }
                assertEquals("at $scale: line 1, the note, then the action", ids.drop(1), next.dropLast(1))
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
        const val BEFORE = "As written previously. Rewriting stopped. Tap Rewrite insight"
        const val NOTE = "The dimmed sentence is not backed by the sources."
        const val COUNTS = "1 of 2 sentences grounded · 1 uncited"
        const val REPORT = "Report a problem"
        const val TOLERANCE = 0.02f

        // Where Compose puts, for tests, the node a screen reader reads after a node.
        const val AFTER = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
    }
}
