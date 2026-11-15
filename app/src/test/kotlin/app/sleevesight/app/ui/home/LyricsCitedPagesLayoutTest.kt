package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
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
 * The pages *Explain the song*'s answer cites (#1287), in two columns: the ids right-aligned in a
 * column as wide as the widest, the links left-aligned in theirs; on a link that wraps, the id and
 * the mark of a page that opens outside the app on its first line, not in its middle. At the usual
 * font size and at twice it, in a column narrow enough for the long site to wrap. Native graphics,
 * for the texts' real widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsCitedPagesLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val pages =
        listOf(
            CitedPage("S5", "https://www.thelineofbestfit.com/good-love", "Good Love"),
            CitedPage("S7", "https://skopemag.com/good-love", "Good Love"),
            CitedPage("S10", "https://musicasparaouvirnaestrada.blogspot.com/good-love", "Good Love"),
        )

    private fun show(check: () -> Unit) {
        val reply = "It is about good times. [S5] [S7] It is a song for the road. [S10]"
        val grounded =
            (GroundingCheck.check(reply, pages.map { it.id }.toSet(), allowModel = false) as InsightReply.Written)
                .grounded
        val answer = SearchAnswer.Explanation(grounded, pages)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Surface { Column(Modifier.width(300.dp)) { AiExplanation(answer) } }
                    }
                }
            }
            compose.waitForIdle()
            check()
        }
    }

    // The text node [node] draws, with where its text starts in the root.
    private class Drawn(
        node: SemanticsNode,
    ) {
        val origin: Offset = node.layoutInfo.coordinates.positionInRoot()
        val layout: TextLayoutResult =
            mutableListOf<TextLayoutResult>()
                .also { node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
                .single()

        fun right(line: Int): Float = origin.x + layout.getLineRight(line)

        fun left(line: Int): Float = origin.x + layout.getLineLeft(line)

        fun baseline(line: Int): Float = origin.y + layout.getLineBaseline(line)

        fun middle(line: Int): Float = origin.y + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
    }

    private fun id(page: CitedPage) = Drawn(compose.onNodeWithTag(lyricsCitedPageIdTag(page.id)).fetchSemanticsNode())

    private fun link(page: CitedPage) =
        Drawn(
            compose
                .onNode(hasText(siteOf(page.url)) and hasAnyAncestor(hasTestTag(lyricsCitedPageTag(page.id))))
                .fetchSemanticsNode(),
        )

    private fun mark(page: CitedPage) =
        compose.onNodeWithTag(lyricsCitedPageMarkTag(page.id)).fetchSemanticsNode().boundsInRoot

    private val tolerance = 1f

    private fun numbersRightAlignedLinksLeftAligned() =
        show {
            val ids = pages.map(::id)
            val links = pages.map(::link)
            val right = ids.maxOf { it.right(0) }
            for ((page, drawn) in pages.zip(ids)) {
                assertEquals("${page.id} ends at the ids' right edge", right, drawn.right(0), tolerance)
            }
            val left = links.first().left(0)
            for ((page, drawn) in pages.zip(links)) {
                assertEquals("${page.id}'s link starts at the links' left edge", left, drawn.left(0), tolerance)
            }
            // The ids' column is as wide as the widest: its id ends 8 dp before the links start.
            val gap = compose.density.run { 8.dp.toPx() }
            assertEquals(gap, left - right, tolerance)
        }

    private fun numberAndMarkOnAWrappingLinksFirstLine() =
        show {
            val long = pages.last()
            val link = link(long)
            assertTrue("${siteOf(long.url)} wraps", link.layout.lineCount > 1)
            assertEquals("the id on the link's first line", link.baseline(0), id(long).baseline(0), tolerance)
            assertEquals("the mark on the link's first line", link.middle(0), mark(long).center.y, tolerance)
            // And on a link of one line, as on that line.
            val short = pages[1]
            assertEquals(1, link(short).layout.lineCount)
            assertEquals(link(short).baseline(0), id(short).baseline(0), tolerance)
            assertEquals(link(short).middle(0), mark(short).center.y, tolerance)
        }

    @Test
    fun theNumbersAreRightAlignedInTheirColumnAndTheLinksLeftAlignedInTheirs() = numbersRightAlignedLinksLeftAligned()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheNumbersAreRightAlignedAndTheLinksLeftAligned() = numbersRightAlignedLinksLeftAligned()

    @Test
    fun onAWrappingLinkTheNumberAndTheMarkSitOnItsFirstLine() = numberAndMarkOnAWrappingLinksFirstLine()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheNumberAndTheMarkSitOnAWrappingLinksFirstLine() = numberAndMarkOnAWrappingLinksFirstLine()
}
