package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.model.NowPlaying
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
 * row say "Cleared with the history.", without the turning record; a hold on another track leaves
 * the waiting texts, which are then true.
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

    private fun show(
        cleared: NowPlaying?,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track = null, aiReady = true, cleared = cleared),
                playing,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PAUSED),
                settings = Settings(),
            )
        val actions = NowPlayingActions(HomeFixtures.normalizer::normalize, lookUpAgain = { lookedUp += it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
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
}
