package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
 * for the texts' real widths. The rows close together, each link still answering a tap across 48
 * dp; the sites in *Sources*' small type and the ids as the text's chips (#1344).
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
            // A site narrower than a link's touch target.
            CitedPage("S2", "https://x.com/good-love", "Good Love"),
            CitedPage("S10", "https://musicasparaouvirnaestrada.blogspot.com/good-love", "Good Love"),
        )

    // The pages opened, by a tap on their links.
    private val opened = mutableListOf<String>()

    private val browser =
        object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

    // The theme's chip fill and small label type, as the explanation had them.
    private var chipFill = Color.Unspecified
    private var small = TextStyle.Default

    // In a column [width] wide: by default narrow enough for the long site to wrap, and wide enough
    // for the others to take one line, at the font size the test has.
    private fun show(
        width: Dp? = null,
        check: () -> Unit,
    ) {
        val reply = "It is about good times. [S5] [S7] [S2] It is a song for the road. [S10]"
        val grounded =
            (GroundingCheck.check(reply, pages.map { it.id }.toSet(), allowModel = false) as InsightReply.Written)
                .grounded
        val answer = SearchAnswer.Explanation(grounded, pages)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val column = width ?: if (LocalDensity.current.fontScale > 1f) 300.dp else 240.dp
                    SleevesightTheme(darkTheme = false) {
                        chipFill = MaterialTheme.colorScheme.secondaryContainer
                        small = MaterialTheme.typography.labelSmall
                        CompositionLocalProvider(LocalUriHandler provides browser) {
                            Surface { Column(Modifier.width(column)) { AiExplanation(answer) } }
                        }
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

    private val zeroWidthSpace = "\u200B"

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
            // The mark right after the first line's text, the row's 4 dp on: past a wrapping site's
            // first line, not the link's full width, and past a short one, not its touch target.
            val space = compose.density.run { 4.dp.toPx() }
            for (page in listOf(long, short, pages[2])) {
                val after = link(page).right(0) + space
                assertEquals("${page.id}'s mark after its text", after, mark(page).left, tolerance)
            }
        }

    @Test
    fun aSiteBreaksAfterADotAndAScreenReaderGetsItWithoutTheBreaks() =
        show {
            val long = pages.last()
            val layout = link(long).layout
            assertTrue(layout.lineCount > 1)
            val first =
                layout.layoutInput.text.text
                    .substring(0, layout.getLineEnd(0))
            assertEquals("musicasparaouvirnaestrada.", first.replace(zeroWidthSpace, ""))
            // No text a screen reader gets carries the breaks; the link's is the site as it is.
            val broken =
                compose
                    .onAllNodes(
                        SemanticsMatcher("text with a zero-width space") { node ->
                            node.config
                                .getOrNull(SemanticsProperties.Text)
                                .orEmpty()
                                .any { zeroWidthSpace in it.text }
                        },
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
            assertTrue(broken.isEmpty())
            for (page in pages) {
                val node =
                    compose
                        .onNode(hasTestTag(lyricsCitedPageTag(page.id)), useUnmergedTree = true)
                        .fetchSemanticsNode()
                val texts = node.children.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                assertTrue("${page.id}: $texts", texts.any { it.text == siteOf(page.url) })
            }
        }

    @Test
    fun theNumbersAreRightAlignedInTheirColumnAndTheLinksLeftAlignedInTheirs() = numbersRightAlignedLinksLeftAligned()

    private fun linkBounds(page: CitedPage) =
        compose
            .onNode(hasText(siteOf(page.url)) and hasAnyAncestor(hasTestTag(lyricsCitedPageTag(page.id))))
            .fetchSemanticsNode()
            .boundsInRoot

    private fun tap(point: Offset) {
        opened.clear()
        compose.onRoot().performTouchInput { click(point) }
        compose.waitForIdle()
    }

    // In a phone's full width, where the two sites take a line each at either font size.
    private fun rowsCloseAndEachLinkAnswersAcrossATouchTarget() =
        show(width = 411.dp) {
            val target = compose.density.run { 48.dp.toPx() }
            val (upper, lower) = pages[1] to pages[2]
            // The blank between the two sites' lines is the rows' gap, not a touch target's room
            // around a smaller text: a row is as high as its text.
            val blank =
                link(lower).let { it.origin.y + it.layout.getLineTop(0) } -
                    link(upper).let { it.origin.y + it.layout.getLineBottom(it.layout.lineCount - 1) }
            assertTrue("$blank px between the rows", blank < compose.density.run { 16.dp.toPx() })
            val above = linkBounds(upper)
            val below = linkBounds(lower)
            assertTrue(above.height < target && below.height < target)
            // Outside a link's bounds, but within the 48 dp Compose widens it to, and nearer it than
            // the next link: a tap opens it.
            val x = above.left + 4f
            val step = compose.density.run { 2.dp.toPx() }
            tap(Offset(x, above.bottom + step))
            assertEquals(listOf(upper.url), opened)
            tap(Offset(x, below.top - step))
            assertEquals(listOf(lower.url), opened)
            // Above the first link, where only the note "Pages cited" is: still the first link's.
            val first = linkBounds(pages.first())
            val reach = (target - first.height) / 2
            tap(Offset(x, first.top - reach + step))
            assertEquals(listOf(pages.first().url), opened)
        }

    @Test
    fun theRowsSitCloseAndEachLinkStillAnswersATapAcrossATouchTarget() = rowsCloseAndEachLinkAnswersAcrossATouchTarget()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheRowsSitCloseAndEachLinkStillAnswersATap() = rowsCloseAndEachLinkAnswersAcrossATouchTarget()

    @Test
    fun theSitesAreInSourcesSmallTypeAndTheIdsAreTheTextsChips() =
        show {
            for (page in pages) {
                val link = link(page).layout.layoutInput
                assertEquals("${page.id}'s site in the small label type", small.fontSize, link.style.fontSize)
                val id = id(page).layout.layoutInput
                // The text's chip: its fill over the whole id, at the sites' size.
                val chip = id.text.spanStyles.single()
                assertEquals(chipFill, chip.item.background)
                assertEquals(0 until id.text.length, chip.start until chip.end)
                assertEquals(TextUnit.Unspecified, chip.item.fontSize)
                assertEquals(link.style.fontSize, id.style.fontSize)
                assertEquals(page.id, id.text.text.trim('\u00A0'))
            }
        }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheNumbersAreRightAlignedAndTheLinksLeftAligned() = numbersRightAlignedLinksLeftAligned()

    @Test
    fun onAWrappingLinkTheNumberAndTheMarkSitOnItsFirstLine() = numberAndMarkOnAWrappingLinksFirstLine()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheNumberAndTheMarkSitOnAWrappingLinksFirstLine() = numberAndMarkOnAWrappingLinksFirstLine()
}
