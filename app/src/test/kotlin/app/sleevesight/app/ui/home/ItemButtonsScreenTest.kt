package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The song's, the artist's and the album's rows as buttons (#894): a table of two columns, the
 * buttons all as wide as the widest and the names in one column; a button opening its panel in its
 * row's place, its heading the kind and the name, *Close* on the kind's line; the focus handed as the
 * line under the album's; a long name shown whole on a tap; and the dim line above *Sources*.
 * Where it measures text, drawn with real fonts on a phone's screen ([PHONE]), so a long name is
 * clipped as on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class ItemButtonsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private val opened = mutableListOf<HomePage>()

    private val current = mutableStateOf(state(onDemand))

    private fun state(
        settings: Settings,
        playing: NowPlaying = sundown,
    ): NowPlayingState {
        val track = track(playing)
        return NowPlayingState(
            PipelineView(track, aiReady = true),
            track.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = settings,
        )
    }

    private fun show(
        initial: NowPlayingState,
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(
                            current.value,
                            NowPlayingActions(normalizer::normalize, openPage = { page -> opened += page }),
                        )
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    // Unclipped and with no scroll in between, so bounds read one after another compare.
    private fun bounds(
        tag: String,
        unmerged: Boolean = false,
    ): Rect = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).getUnclippedBoundsInRoot().toRect()

    private fun androidx.compose.ui.unit.DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun name(item: InsightItem) = itemNameTag(item)

    private fun close(item: InsightItem) = itemPanelTag(item) + ":close"

    private fun near(
        a: Float,
        b: Float,
        within: Dp = 1.dp,
    ) = abs(a - b) <= within.value

    @Test
    fun aRowIsOneStopItsButtonReadWithItsNameAndItsActionShowingItsInsight() =
        show(state(onDemand)) {
            val actions =
                mapOf(
                    InsightItem.SONG to ("Song, Sundown, uses AI" to R.string.home_item_show_song),
                    InsightItem.ARTIST to ("Artist, Gordon Lightfoot, uses AI" to R.string.home_item_show_artist),
                    InsightItem.ALBUM to ("Album, Sundown, uses AI" to R.string.home_item_show_album),
                )
            assertEquals("show the song’s insight", app.getString(R.string.home_item_show_song))
            for ((item, said) in actions) {
                compose
                    .onNodeWithTag(itemRowTag(item))
                    .performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(said.first)))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                    .assert(
                        SemanticsMatcher("click labelled") {
                            it.config.getOrNull(SemanticsActions.OnClick)?.label == app.getString(said.second)
                        },
                    ).assertTouchHeightIsEqualTo(48.dp)
                // The name beside it is not a stop of its own.
                val beside = compose.onNodeWithTag(name(item), useUnmergedTree = true).fetchSemanticsNode()
                assertFalse(SemanticsProperties.Text in beside.config)
                assertFalse(SemanticsActions.OnClick in beside.config)
            }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE)
    fun theButtonsAreAllAsWideAsTheWidestAndTheNamesStayInOneColumnWhenOneOpens() =
        show(state(onDemand)) {
            val buttons = InsightItem.entries.map { bounds(itemRowTag(it)) }
            assertTrue(buttons.all { near(it.width, buttons[0].width) && near(it.left, buttons[0].left) })
            val names = InsightItem.entries.map { bounds(name(it), unmerged = true).left }
            assertTrue(names.toString(), names.all { near(it, names[0]) })
            tap(itemRowTag(InsightItem.ARTIST))
            assertTrue(near(bounds(name(InsightItem.SONG), unmerged = true).left, names[0]))
            assertTrue(near(bounds(name(InsightItem.ALBUM), unmerged = true).left, names[0]))
            assertTrue(near(bounds(itemRowTag(InsightItem.SONG)).width, buttons[0].width))
        }

    @Test
    fun aButtonOpensItsPanelInItsRowsPlaceAndCloseFoldsItBack() =
        show(state(onDemand)) {
            tap(itemRowTag(InsightItem.ARTIST))
            assertEquals(0, count(itemRowTag(InsightItem.ARTIST)))
            val panel = bounds(itemPanelTag(InsightItem.ARTIST))
            assertTrue(bounds(itemRowTag(InsightItem.SONG)).bottom <= panel.top)
            assertTrue(panel.bottom <= bounds(itemRowTag(InsightItem.ALBUM)).top)
            // Several open, in the rows' order.
            tap(itemRowTag(InsightItem.SONG))
            assertTrue(bounds(itemPanelTag(InsightItem.SONG)).bottom <= bounds(itemPanelTag(InsightItem.ARTIST)).top)
            tap(close(InsightItem.ARTIST))
            assertEquals(0, count(itemPanelTag(InsightItem.ARTIST)))
            assertEquals(1, count(itemRowTag(InsightItem.ARTIST)))
            assertEquals(1, count(itemPanelTag(InsightItem.SONG)))
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE)
    fun thePanelsHeadingReadsTheKindThenTheNameWithCloseCentredOnTheKindsLine() = headingWithCloseOnTheKindsLine()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE, fontScale = 2f)
    fun atTwiceTheFontCloseStaysCentredOnTheKindsLine() = headingWithCloseOnTheKindsLine()

    private fun headingWithCloseOnTheKindsLine() =
        show(state(onDemand)) {
            tap(itemRowTag(InsightItem.ARTIST))
            val heading = itemPanelTag(InsightItem.ARTIST) + ":heading"
            val node = compose.onNodeWithTag(heading).fetchSemanticsNode()
            assertEquals(listOf("Artist", "Gordon Lightfoot"), node.config[SemanticsProperties.Text].map { it.text })
            assertTrue(SemanticsProperties.Heading in node.config)
            val kind =
                compose
                    .onNode(hasText("Artist") and hasAnyAncestor(hasTestTag(heading)), useUnmergedTree = true)
                    .getUnclippedBoundsInRoot()
                    .toRect()
            val subtitle = bounds(itemPanelTag(InsightItem.ARTIST) + ":subtitle", unmerged = true)
            val closing = bounds(close(InsightItem.ARTIST))
            assertTrue(kind.bottom <= subtitle.top + 1)
            // On the kind's line, not between the kind and the name.
            assertTrue("$kind $closing", near(closing.center.y, kind.center.y, within = 3.dp))
        }

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheButtonOnClosing() =
        show(state(onDemand)) {
            tap(itemRowTag(InsightItem.ALBUM))
            compose.onNodeWithTag(close(InsightItem.ALBUM)).assertIsFocused()
            tap(close(InsightItem.ALBUM))
            compose.onNodeWithTag(itemRowTag(InsightItem.ALBUM)).assertIsFocused()
        }

    @Test
    fun aNewTrackClosingAPanelWithTheFocusInItHandsTheFocusToItsButton() =
        show(state(onDemand)) {
            tap(itemRowTag(InsightItem.SONG))
            compose.onNodeWithTag(close(InsightItem.SONG)).assertIsFocused()
            current.value = state(onDemand, carefree)
            compose.waitForIdle()
            assertEquals(0, count(itemPanelTag(InsightItem.SONG)))
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).assertIsFocused()
        }

    @Test
    fun aNewTrackUnfoldedOpensEveryPanelAndAsLeftKeepsThoseOpen() {
        show(state(onDemand.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED))) {
            assertEquals(3, InsightItem.entries.sumOf { count(itemPanelTag(it)) })
            assertEquals(0, InsightItem.entries.sumOf { count(itemRowTag(it)) })
        }
        val left = onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT)
        show(state(left)) {
            tap(itemRowTag(InsightItem.ALBUM))
            current.value = state(left, carefree)
            compose.waitForIdle()
            assertEquals(1, count(itemPanelTag(InsightItem.ALBUM)))
            assertEquals(2, count(itemRowTag(InsightItem.SONG)) + count(itemRowTag(InsightItem.ARTIST)))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE)
    fun aTapOnALongNameShowsItWholeAnotherClipsItAndANewTrackStartsClipped() {
        val long = sundown.copy(title = LONG_TITLE)
        show(state(onDemand, long)) {
            val clipped = bounds(name(InsightItem.SONG), unmerged = true).height
            tap(name(InsightItem.SONG))
            val whole = bounds(name(InsightItem.SONG), unmerged = true).height
            assertTrue("$clipped $whole", whole > clipped * 1.5f)
            tap(name(InsightItem.SONG))
            assertTrue(near(bounds(name(InsightItem.SONG), unmerged = true).height, clipped))
            // Whole again, then the next track: clipped.
            tap(name(InsightItem.SONG))
            current.value = state(onDemand, carefree.copy(title = long.title + " II"))
            compose.waitForIdle()
            assertTrue(near(bounds(name(InsightItem.SONG), unmerged = true).height, clipped))
            // The tap opens nothing.
            assertEquals(0, count(itemPanelTag(InsightItem.SONG)))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE)
    fun aTapOnANameThatFitsDoesNothing() {
        // The rows alone, over a memory the test reads: a tap on a name that fits keeps it clipped.
        val memory = ItemFoldMemory()
        val long = sundown.copy(title = LONG_TITLE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val context =
                        ItemContext(null, onDemand, Notes, false, {}, { _, _, _ -> }, readAloud = null)
                    val folds = itemFolds(memory, long, NewTrackFold.FOLDED)
                    ItemRows(itemRows(long, "Gordon Lightfoot"), false, folds, context) {}
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(name(InsightItem.ALBUM)).performClick()
            compose.waitForIdle()
            assertEquals(emptySet<InsightItem>(), memory.whole)
            assertEquals(0, count(itemPanelTag(InsightItem.ALBUM)))
            // A tap reaches a name that does not fit.
            compose.onNodeWithTag(name(InsightItem.SONG)).performClick()
            compose.waitForIdle()
            assertEquals(setOf(InsightItem.SONG), memory.whole)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = PHONE)
    fun aTapAnywhereInTheNamesCellAsTallAsItsButtonShowsItWhole() =
        show(state(onDemand, sundown.copy(title = LONG_TITLE))) {
            val clipped = bounds(name(InsightItem.SONG), unmerged = true).height
            val cell = compose.onNodeWithTag(name(InsightItem.SONG) + ":cell").performScrollTo()
            assertTrue(cell.getUnclippedBoundsInRoot().toRect().height >= 48f)
            // Near the cell's top, above the text's line.
            cell.performTouchInput { click(Offset(centerX, 2f)) }
            compose.waitForIdle()
            assertTrue(bounds(name(InsightItem.SONG), unmerged = true).height > clipped * 1.5f)
        }

    @Test
    fun aDimLineSitsBetweenTheRowsAndSourcesUnderEitherLayoutReadByNoScreenReader() {
        for (layout in Layout.entries) {
            val pages = layout == Layout.SEPARATE_PAGES
            show(state(onDemand.copy(layout = layout))) {
                val line = bounds(SOURCES_LINE_TAG)
                assertTrue(bounds(itemRowTag(InsightItem.ALBUM)).bottom <= line.top)
                assertTrue(line.bottom <= bounds(if (pages) SOURCES_PAGE_LINK_TAG else SOURCES_CHIP_TAG).top)
                val node = compose.onNodeWithTag(SOURCES_LINE_TAG).fetchSemanticsNode()
                assertFalse(SemanticsProperties.Text in node.config)
                assertFalse(SemanticsProperties.ContentDescription in node.config)
            }
        }
    }

    @Test
    fun underSeparatePagesAButtonOpensItsPageAndStays() =
        show(state(onDemand.copy(layout = Layout.SEPARATE_PAGES))) {
            tap(itemRowTag(InsightItem.SONG))
            assertEquals(listOf<HomePage>(HomePage.Item(InsightItem.SONG.name)), opened)
            assertEquals(1, count(itemRowTag(InsightItem.SONG)))
            assertEquals(0, count(itemPanelTag(InsightItem.SONG)))
            val song = compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).fetchSemanticsNode()
            assertEquals(app.getString(R.string.home_page_open), song.config[SemanticsActions.OnClick].label)
        }
}

// A phone's screen, for the tests that measure text with real fonts.
private const val PHONE = "w411dp-h891dp-420dpi"

// A title too long for a row's line on [PHONE].
private const val LONG_TITLE = "Sundown (Live at Massey Hall, Toronto, 1974, Remastered Deluxe Edition)"

// Items that are offered, never written.
private object Notes : ItemNotes {
    override fun item(
        track: TrackState,
        item: InsightItem,
    ): Flow<ItemShown> = flowOf(ItemShown.Offered)

    override fun write(
        track: TrackState,
        item: InsightItem,
        ask: ItemAsk,
    ) = Unit
}
