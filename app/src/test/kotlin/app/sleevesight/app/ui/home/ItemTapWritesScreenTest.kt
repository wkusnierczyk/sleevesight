package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's, the artist's and the album's buttons as AI buttons (#948): each carries the **AI** mark
 * while *Show insights* is on, and a tap that opens one writes what it opens, in every mode, *On
 * demand* included, once, where nothing is written; a written, stopped or failed one opens as it is.
 * A panel left open at a new track offers *Write insight* under *On demand*, and says why an automatic
 * mode held it back. A panel closed while it writes lets the call go on, its row showing the turning
 * record meanwhile; under *Separate pages* the button shows its page's write the same way.
 */
@RunWith(RobolectricTestRunner::class)
class ItemTapWritesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private val notes = FakeItemNotes()

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
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            block(current)
        }
    }

    private fun row(item: InsightItem) = compose.onNodeWithTag(itemRowTag(item))

    // The turning records in the rows, not the key insight's.
    private fun rowRecords() =
        compose.onAllNodes(hasTestTag(SPINNING_RECORD_TAG) and hasAnyAncestor(hasTestTag(ITEM_ROWS_TAG)), true)

    @Test
    fun underOnDemandATapThatOpensARowWritesItAtOnceAsTheTapsConsent() =
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            compose.onAllNodesWithTag(ITEM_WRITING_TAG, useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
            // Open in its row's place: the row and its button hidden meanwhile.
            row(InsightItem.ARTIST).assertDoesNotExist()
            compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST)).assertExists()
        }

    @Test
    fun underOnDemandAWriteTheGatesRefuseOffersWriteInsightWithTheAiMark() {
        notes.takenUp = false
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
            val button = compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).fetchSemanticsNode()
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_write)),
                button.config[SemanticsProperties.ContentDescription].single(),
            )
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.TAP), notes.asks)
        }
    }

    @Test
    fun underOnDemandTheTapsWriteIsMadeOnceNotAgainOnRecompositionOrAReturnToTheScreen() {
        notes.takenUp = false
        val current = mutableStateOf(state(onDemand))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            // Refused, offered again: a recomposition, the key insight streaming, a return to the screen.
            current.value = state(onDemand, track(insight = InsightState.Writing("It", model = "m")))
            compose.waitForIdle()
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
        }
    }

    @Test
    fun underOnDemandAWrittenStoppedOrFailedItemOpensAsItIsWithNoCall() {
        val evidenceOf = ItemEvidence(evidence, mapOf("W1" to "W1"))
        notes.shown.value =
            mapOf(
                InsightItem.SONG to
                    ItemShown.Shown(written("It is a song [W1]."), evidenceOf, "Claude Haiku 4.5"),
                InsightItem.ARTIST to ItemShown.Shown(InsightState.Stopped, evidenceOf),
                InsightItem.ALBUM to ItemShown.Shown(InsightState.Failed("timed_out"), evidenceOf),
            )
        show(state(onDemand)) {
            for (item in InsightItem.entries) row(item).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(notes.writes.isEmpty())
            compose.onNodeWithText("by Claude Haiku 4.5").assertExists()
            compose.onNodeWithText(text(R.string.home_insight_stopped)).assertExists()
            compose.onNodeWithTag(ITEM_FAILED_TAG).assertExists()
        }
    }

    @Test
    fun underOnDemandAPanelLeftOpenAtANewTrackOffersWriteInsightAndWritesNothing() {
        val left = onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT)
        show(state(left)) { current ->
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            notes.shown.value = emptyMap()
            current.value = state(left, track(carefree))
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
            compose.onNodeWithTag(itemWriteTag(InsightItem.SONG)).assertExists()
        }
    }

    @Test
    fun aPanelHeldBackUnderAnAutomaticModeSaysWhyAsTheKeyInsightDoes() {
        val skip =
            Settings(writeAutomatically = WriteAutomatically.WHEN_I_SKIP, itemsOnNewTrack = NewTrackFold.UNFOLDED)
        notes.takenUp = false
        val reasons =
            listOf(
                PipelineView(track(), aiReady = true, followed = true) to R.string.home_insight_followed,
                PipelineView(track(), aiReady = true, lapsed = true) to R.string.home_insight_resumed,
                PipelineView(track(), aiReady = true) to R.string.home_item_tap_write,
            )
        for ((view, line) in reasons) {
            val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
            show(NowPlayingState(view, view.track!!.nowPlaying, playback, settings = skip)) {
                compose.waitForIdle()
                compose.onAllNodesWithText(text(line)).assertCountEquals(3)
            }
        }
        assertTrue(notes.asks.all { it == ItemAsk.BY_ITSELF })
    }

    @Test
    fun closingAPanelWhileItWritesLetsTheCallGoOnAndTheRowShowsTheTurningRecord() =
        show(state(onDemand)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemPanelTag(InsightItem.SONG) + ":close").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(notes.stops.isEmpty())
            row(InsightItem.SONG).assertExistsWith("Song, Sundown, uses AI, writing")
            rowRecords().assertCountEquals(1)
            // The others were never opened: not followed, nothing gathered for them.
            assertEquals(setOf(InsightItem.SONG), notes.followed.toSet())
            // The insight arrives, the panel closed: kept, the record gone, shown when reopened, no call.
            notes.shown.value =
                mapOf(
                    InsightItem.SONG to
                        ItemShown.Shown(written("It is a song [W1]."), ItemEvidence(evidence, mapOf("W1" to "W1"))),
                )
            compose.waitForIdle()
            rowRecords().assertCountEquals(0)
            row(InsightItem.SONG).assertExistsWith("Song, Sundown, uses AI")
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText("It is a song", substring = true).assertExists()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
        }

    @Test
    fun aRowClosedWhileItWritesStillShowsItOnTheNextTrackOfTheSameArtist() =
        show(state(onDemand)) { current ->
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST) + ":close").performScrollTo().performClick()
            compose.waitForIdle()
            // The next track, by the same artist: the artist's write goes on, and its row shows it.
            current.value = state(onDemand, track(carefree))
            compose.waitForIdle()
            row(InsightItem.ARTIST).assertExistsWith("Artist, Gordon Lightfoot, uses AI, writing")
            rowRecords().assertCountEquals(1)
            row(InsightItem.SONG).assertExistsWith("Song, Carefree Highway, uses AI")
        }

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as for a recreated activity (as `SourcesFoldScreenTest`).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun showSaved(
        current: MutableState<NowPlayingState>,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(
                                    current.value,
                                    NowPlayingActions(normalizer::normalize, itemNotes = notes),
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun underOnDemandATapsWriteStillDueOutlivesARecreatedScreenAndIsMadeOnce() {
        // The evidence or the cache still read: nothing known yet when the panel opens.
        notes.shown.value = mapOf(InsightItem.SONG to ItemShown.Gathering)
        val current = mutableStateOf(state(onDemand))
        showSaved(current) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(notes.asks.isEmpty())
            restore()
            compose.onNodeWithTag(itemPanelTag(InsightItem.SONG)).assertExists()
            notes.shown.value = emptyMap()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            restore()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
        }
    }

    @Test
    fun aTapsWriteDueOnOneTrackNeverWritesOnTheNext() {
        notes.shown.value = mapOf(InsightItem.SONG to ItemShown.Gathering)
        val left = onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT)
        val current = mutableStateOf(state(left))
        showSaved(current) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            restore()
            // The next track, the panel left open: its write was the last track's, so nothing is asked.
            current.value = state(left, track(carefree))
            notes.shown.value = emptyMap()
            compose.waitForIdle()
            assertTrue(notes.asks.isEmpty())
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
        }
    }

    @Test
    fun withShowInsightsOffTheButtonsCarryNoMarkAndReadAsBefore() =
        show(state(onDemand.copy(writeInsights = false))) {
            row(InsightItem.SONG).assertExistsWith("Song, Sundown")
            compose
                .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(ITEM_ROWS_TAG)), true)
                .assertCountEquals(0)
            notes.shown.value = InsightItem.entries.associateWith { ItemShown.Off }
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(notes.writes.isEmpty())
        }

    @Test
    fun everyButtonCarriesTheMarkAfterItsKind() =
        show(state(onDemand)) {
            for (item in InsightItem.entries) {
                compose
                    .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(itemRowTag(item))), true)
                    .assertCountEquals(1)
            }
        }

    @Test
    fun underSeparatePagesTheButtonShowsItsPagesWriteUnderWay() =
        show(state(onDemand.copy(layout = Layout.SEPARATE_PAGES))) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.waitForIdle()
            // The page writes it (as below); back on Home, its row shows the write under way.
            notes.shown.value = mapOf(InsightItem.ARTIST to ItemShown.Asking(ITEM_KEY))
            compose.waitForIdle()
            row(InsightItem.ARTIST).assertExistsWith("Artist, Gordon Lightfoot, uses AI, writing")
            rowRecords().assertCountEquals(1)
        }

    @Test
    fun underOnDemandAPageWritesOnItsOpeningOnceAndOffersWriteInsightOnTheNextTrack() {
        val current = mutableStateOf(state(onDemand))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    NowPlayingPageContent(
                        HomePage.Item(InsightItem.ARTIST.name),
                        current.value,
                        NowPlayingActions(normalizer::normalize, itemNotes = notes),
                    ) {}
                }
            }
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            notes.shown.value = emptyMap()
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            current.value = state(onDemand, track(carefree))
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
        }
    }

    private fun SemanticsNodeInteraction.assertExistsWith(description: String) {
        assertEquals(
            description,
            performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
        )
    }
}
