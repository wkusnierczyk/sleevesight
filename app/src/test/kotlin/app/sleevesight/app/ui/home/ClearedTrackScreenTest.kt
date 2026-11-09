package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home for a track *Clear history* holds (#1112, *Look up the track again*'s *On request*): no field
 * waits on a run that does not exist. *Key insights* says the findings were cleared, with *Look up
 * again*, whose tap asks for the track shown; *Sources*, *Genres*, *Dates*, *Lyrics* and the song's
 * row say "Cleared with the history.", without the turning record, as do the pages of *Separate
 * pages*; the cover's back does not: turning the cover over asks for the look-up, once; a back left
 * turned over from before the clear asks nothing, as Home is composed again or comes back, and says
 * the track was cleared, until the cover is turned over again (#1148); a model found unavailable is
 * still said; a hold on another track leaves the waiting texts, which are then true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class ClearedTrackScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val playing: NowPlaying = track().nowPlaying
    private val lookedUp = mutableListOf<NowPlaying>()

    private fun text(id: Int) = app.getString(id)

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // The track the pipeline holds, which a test may change while Home shows.
    private var held by mutableStateOf<NowPlaying?>(null)

    // Home's saved state, as an activity keeps it: [leave] saves it and drops Home, as going to
    // *Settings* does, and [comeBack] composes it afresh from what was saved, as `rememberSaveable`
    // restores it (what `StateRestorationTester` does, which needs a content rule these tests do not use).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var saved: Map<String, List<Any?>> = emptyMap()

    private fun leave() {
        saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
    }

    private fun comeBack() {
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    // Home for [playing], the pipeline holding [cleared]; on [page] of its own under *Separate pages*,
    // else Home itself.
    private fun show(
        cleared: NowPlaying?,
        page: HomePage? = null,
        unavailable: UnavailableModel? = null,
        block: () -> Unit,
    ) {
        held = cleared
        val actions = NowPlayingActions(HomeFixtures.normalizer::normalize, lookUpAgain = { lookedUp += it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val state =
                        NowPlayingState(
                            PipelineView(track = null, aiReady = true, cleared = held),
                            playing,
                            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PAUSED),
                            settings = Settings(),
                            unavailable = unavailable,
                        )
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            if (page != null) {
                                NowPlayingPageContent(page, state, actions) {}
                            } else {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    NowPlayingContent(state, actions)
                                }
                            }
                        }
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

    private fun records(): Int =
        compose.onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun assertNoWaitingText() {
        val waiting =
            listOf(
                text(R.string.home_insight_gathering),
                text(R.string.home_insight_preparing),
                text(R.string.home_evidence_looking),
                text(R.string.home_musicbrainz_looking),
                text(R.string.home_source_pending),
            ).filter(::shows)
        assertEquals(emptyList<String>(), waiting)
        assertTrue("no Looking on … for the genres", !shows(text(R.string.home_genres_looking).substringBefore("%")))
        assertEquals("no turning record", 0, records())
    }

    @Test
    fun keyInsightsSaysTheFindingsWereClearedAndLookUpAgainAsksForTheTrackShown() =
        show(cleared = playing) {
            compose.onNodeWithText(text(R.string.home_insight_cleared)).assertExists()
            tap(LOOK_UP_AGAIN_TAG)
            assertEquals(listOf(playing), lookedUp)
            assertNoWaitingText()
        }

    @Test
    fun everyPanelSaysClearedWithTheHistoryAndNoneWaits() =
        show(cleared = playing) {
            for (tag in listOf(SOURCES_CHIP_TAG, GENRES_CHIP_TAG, DATES_CHIP_TAG, LYRICS_CHIP_TAG)) tap(tag)
            tap(itemRowTag(InsightItem.SONG))
            val said = compose.onAllNodesWithText(text(R.string.home_cleared)).fetchSemanticsNodes().size
            assertEquals("Sources, Genres, Dates, Lyrics and the song's row", 5, said)
            assertNoWaitingText()
        }

    @Test
    fun aHoldOnAnotherTrackLeavesTheWaitingTexts() =
        show(cleared = playing.copy(title = "Another")) {
            compose.onNodeWithText(text(R.string.home_insight_gathering)).assertExists()
            assertTrue(compose.onAllNodesWithTag(LOOK_UP_AGAIN_TAG).fetchSemanticsNodes().isEmpty())
            assertTrue(!shows(text(R.string.home_insight_cleared)))
        }

    @Test
    fun theSeparatePagesSayClearedWithTheHistoryAndNoneWaits() {
        for (page in listOf(HomePage.Sources(), HomePage.Genres, HomePage.Item(InsightItem.SONG.name))) {
            show(cleared = playing, page = page) {
                assertTrue("$page says it was cleared", shows(text(R.string.home_cleared)))
                assertNoWaitingText()
            }
        }
    }

    @Test
    fun turningTheCoverOverAsksForTheLookUpOnceAndItsBackSaysItIsLooking() =
        show(cleared = playing) {
            tap(COVER_TAG)
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            assertEquals("the turn asks, as Look up again does", listOf(playing), lookedUp)
            backSays(R.string.home_musicbrainz_looking, notSaid = R.string.home_cleared)
            // Back to the cover, and over again: no second ask.
            tap(COVER_TAG)
            tap(COVER_TAG)
            assertEquals(listOf(playing), lookedUp)
        }

    private val onBack = hasAnyAncestor(hasTestTag(COVER_BACK_TAG))

    // What the back says: [said] once, and not [notSaid].
    private fun backSays(
        said: Int,
        notSaid: Int,
    ) {
        compose.onAllNodes(hasText(text(said)) and onBack, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasText(text(notSaid)) and onBack, useUnmergedTree = true).assertCountEquals(0)
    }

    // The cover turned over before the clear, then [clear] holds the track; then a fresh turn asks once.
    private fun aBackLeftTurnedOverAsksNothingUntilTurnedAgain(clear: () -> Unit) =
        show(cleared = null) {
            tap(COVER_TAG)
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            clear()
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            assertEquals("nothing asked for a back left turned over", emptyList<NowPlaying>(), lookedUp)
            backSays(R.string.home_cleared, notSaid = R.string.home_musicbrainz_looking)
            assertEquals("no turning record", 0, records())
            // Turned back and over again: that turn asks, once.
            tap(COVER_TAG)
            assertEquals(emptyList<NowPlaying>(), lookedUp)
            tap(COVER_TAG)
            assertEquals("the new turn asks", listOf(playing), lookedUp)
            backSays(R.string.home_musicbrainz_looking, notSaid = R.string.home_cleared)
            tap(COVER_TAG)
            tap(COVER_TAG)
            assertEquals("once while the hold lasts", listOf(playing), lookedUp)
        }

    @Test
    fun aBackLeftTurnedOverAsksNothingWhenTheClearComesWithHomeShown() =
        aBackLeftTurnedOverAsksNothingUntilTurnedAgain {
            held = playing
            compose.waitForIdle()
        }

    @Test
    fun aBackLeftTurnedOverAsksNothingWhenHomeComesBackAfterTheClear() =
        aBackLeftTurnedOverAsksNothingUntilTurnedAgain {
            leave()
            held = playing
            comeBack()
        }

    @Test
    fun aScreenReadersTurnOfTheCoverOverAsksForTheLookUpOnce() =
        show(cleared = playing) {
            compose.onNodeWithTag(COVER_TAG).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            assertEquals("the screen reader's turn asks, as a tap does", listOf(playing), lookedUp)
        }

    @Test
    fun turningTheCoverWithNoClearHoldingTheTrackAsksNothing() =
        show(cleared = null) {
            tap(COVER_TAG)
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            assertEquals(emptyList<NowPlaying>(), lookedUp)
        }

    @Test
    fun aModelFoundUnavailableIsStillSaidUnderTheClearedNote() =
        show(cleared = playing, unavailable = UnavailableModel(ProviderId.ANTHROPIC, "claude-haiku-5-5")) {
            compose.onNodeWithText(text(R.string.home_insight_cleared)).assertExists()
            compose.onNodeWithTag(LOOK_UP_AGAIN_TAG).assertExists()
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performScrollTo().assertExists()
        }
}
