package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's, the artist's and the album's rows under *Key insights* (#522): folded by default, each
 * unfolding in place under *Expandable boxes*, several at once, or opening its page under *Separate
 * pages*; written on a tap under *On demand*, at once under an automatic mode.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)
    private val automatic = Settings(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)

    // Each item's state, as the test sets it; the writes asked for.
    private class Notes : ItemNotes {
        val shown = MutableStateFlow<Map<InsightItem, ItemShown>>(emptyMap())
        val writes = mutableListOf<InsightItem>()

        override fun item(
            track: TrackState,
            item: InsightItem,
        ): Flow<ItemShown> = shown.map { it[item] ?: ItemShown.Offered }

        val asks = mutableListOf<ItemAsk>()
        val stops = mutableListOf<InsightItem>()

        override fun write(
            track: TrackState,
            item: InsightItem,
            ask: ItemAsk,
        ) {
            writes += item
            asks += ask
        }

        override fun stop(
            track: TrackState,
            item: InsightItem,
        ) {
            stops += item
        }
    }

    private val notes = Notes()
    private val opened = mutableListOf<HomePage>()

    private fun state(
        settings: Settings,
        track: TrackState = track(),
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    private fun show(
        initial: NowPlayingState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(
                            current.value,
                            NowPlayingActions(normalizer::normalize, itemNotes = notes, openPage = { page ->
                                opened +=
                                    page
                            }),
                        )
                    }
                }
            }
            compose.waitForIdle()
            block(current)
        }
    }

    private fun row(item: InsightItem) = compose.onNodeWithTag(itemRowTag(item))

    @Test
    fun theRowsAreFoldedByDefaultEachItsKindAndName() =
        show(state(onDemand)) {
            compose.onAllNodesWithTag(ITEM_ROWS_TAG).assertCountEquals(1)
            val artist = row(InsightItem.ARTIST).performScrollTo().fetchSemanticsNode()
            assertEquals("Artist, Gordon Lightfoot", artist.config[SemanticsProperties.ContentDescription].single())
            assertEquals(text(R.string.home_fold_collapsed), artist.config[SemanticsProperties.StateDescription])
            assertEquals(text(R.string.home_item_expand), artist.config[SemanticsActions.OnClick].label)
            row(InsightItem.SONG).assertExistsWith("Song, Sundown")
            row(InsightItem.ALBUM).assertExistsWith("Album, Sundown")
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun underOnDemandAnUnfoldedRowOffersWriteInsightWithTheAiMarkAndATapWrites() =
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
            val button = compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).fetchSemanticsNode()
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_write)),
                button.config[SemanticsProperties.ContentDescription].single(),
            )
            assertTrue(notes.writes.isEmpty())
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            val unfolded = row(InsightItem.ARTIST).fetchSemanticsNode()
            assertEquals(text(R.string.home_fold_expanded), unfolded.config[SemanticsProperties.StateDescription])
        }

    @Test
    fun underAnAutomaticModeUnfoldingARowWritesItAtOnceAndSeveralUnfold() =
        show(state(automatic)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            row(InsightItem.ALBUM).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(InsightItem.SONG, InsightItem.ALBUM), notes.writes)
            // Opened by a tap: through the coordinator's gates, as a tap.
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.OPENED), notes.asks)
            compose.onAllNodesWithTag(ITEM_WRITING_TAG, useUnmergedTree = true).assertCountEquals(2)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun aWrittenItemShowsItsModelReadAloudItsInsightAndReportAProblem() {
        val artist = written("Gordon Lightfoot was a Canadian singer [W1].")
        notes.shown.value =
            mapOf(
                InsightItem.ARTIST to
                    ItemShown.Shown(artist, ItemEvidence(evidence, mapOf("W1" to "W1")), "Claude Haiku 4.5"),
            )
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText("by Claude Haiku 4.5").assertExists()
            compose.onNodeWithText("a Canadian singer", substring = true).assertExists()
            // The key insight has none written here: the one is the artist's.
            compose.onAllNodesWithTag(REPORT_TAG).assertCountEquals(1)
            compose.onNodeWithTag(itemReadAloudTag(InsightItem.ARTIST)).assertDoesNotExist()
        }
    }

    @Test
    fun anAlbumTheSourcesSayNothingAboutSaysSo() {
        notes.shown.value =
            mapOf(InsightItem.ALBUM to ItemShown.Shown(InsightState.NoEvidence, ItemEvidence(evidence, emptyMap())))
        show(state(onDemand)) {
            row(InsightItem.ALBUM).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_item_no_evidence_album)).assertExists()
        }
    }

    @Test
    fun underSeparatePagesATapOpensTheItemsPage() =
        show(state(onDemand.copy(layout = Layout.SEPARATE_PAGES))) {
            val artist = row(InsightItem.ARTIST).performScrollTo().fetchSemanticsNode()
            assertEquals(text(R.string.home_page_open), artist.config[SemanticsActions.OnClick].label)
            row(InsightItem.ARTIST).performClick()
            assertEquals(listOf<HomePage>(HomePage.Item(InsightItem.ARTIST.name)), opened)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun aNewTrackStartsTheRowsAsTheSettingSays() {
        show(state(onDemand.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED))) { current ->
            // Unfolded on the first track, and on the next.
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(3)
            current.value = state(onDemand, track(carefree))
            compose.waitForIdle()
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }
        show(state(onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT))) { current ->
            row(InsightItem.SONG).performScrollTo().performClick()
            current.value = state(onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT), track(carefree))
            compose.waitForIdle()
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(1)
            assertEquals(
                text(R.string.home_fold_expanded),
                row(InsightItem.SONG).fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
        }
    }

    @Test
    fun anItemStartingUnfoldedIsWrittenByItselfOnlyWhenNothingHoldsTheTracksWritingBack() {
        val unfolded = automatic.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED)
        // No first action since the app started (D5): the track's own writing waits, and so do its items.
        val waiting =
            state(unfolded).let {
                NowPlayingState(it.view.copy(waiting = true), it.playing, it.playback, settings = unfolded)
            }
        show(waiting) {
            compose.waitForIdle()
            assertTrue(notes.writes.isEmpty())
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(3)
        }
        show(state(unfolded)) {
            compose.waitForIdle()
            assertEquals(InsightItem.entries.toSet(), notes.writes.toSet())
            assertEquals(setOf(ItemAsk.BY_ITSELF), notes.asks.toSet())
        }
    }

    @Test
    fun aFailedItemSaysWhyAndItsRetryCarriesTheAiMark() {
        notes.shown.value =
            mapOf(
                InsightItem.SONG to
                    ItemShown.Shown(InsightState.Failed("timed_out"), ItemEvidence(evidence, emptyMap())),
            )
        show(state(onDemand)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.onNodeWithTag(ITEM_FAILED_TAG).performScrollTo().assertExists()
            val retry = compose.onNodeWithTag(RETRY_TAG).fetchSemanticsNode()
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_retry)),
                retry.config[SemanticsProperties.ContentDescription].single(),
            )
            compose
                .onNode(
                    hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(RETRY_TAG)),
                    useUnmergedTree = true,
                ).assertExists()
            compose.onNodeWithTag(RETRY_TAG).performClick()
            assertEquals(listOf(ItemAsk.TAP), notes.asks)
        }
    }

    @Test
    fun aWritingItemOffersStopAndAStoppedOneWritesAgainOnATap() {
        notes.shown.value =
            mapOf(
                InsightItem.SONG to
                    ItemShown.Shown(InsightState.Writing("", model = "m"), ItemEvidence(evidence, emptyMap())),
                InsightItem.ARTIST to ItemShown.Shown(InsightState.Stopped, ItemEvidence(evidence, emptyMap())),
            )
        show(state(onDemand)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_insight_writing_stop)).assertExists()
            compose.onNodeWithTag(itemStopTag(InsightItem.SONG)).performScrollTo().performClick()
            assertEquals(listOf(InsightItem.SONG), notes.stops)

            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_insight_stopped)).assertExists()
            compose.onNodeWithText(text(R.string.home_insight_retry)).assertExists()
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            assertEquals(listOf(ItemAsk.TAP), notes.asks)
        }
    }

    private fun SemanticsNodeInteraction.assertExistsWith(description: String) {
        assertEquals(
            description,
            performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
        )
    }
}
