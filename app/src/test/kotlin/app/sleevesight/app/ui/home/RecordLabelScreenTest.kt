package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.TracklistFeed
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line under Home's album (#729): the release's tracklist looked up with no flip of the cover;
 * while it is, the line held back, its height kept, then filled once with the label and the year;
 * with no answer in [NAMING_WAIT], the year alone, the label added once it comes; MusicBrainz off, the
 * player's year alone at once, nothing looked up. On virtual time: the clock advances only as a test
 * says.
 */
@RunWith(RobolectricTestRunner::class)
class RecordLabelScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private val group = "g-sundown"
    private val key = TracklistKey(group, "rec-sundown")

    private val resolved: TrackState =
        track(sundown).copy(
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec-sundown",
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", group, "Album")),
                ),
        )

    // The tracklists as a test answers them, and what was asked.
    private class Feed : TracklistFeed {
        val shown = MutableStateFlow<Map<TracklistKey, TracklistState>>(emptyMap())
        val asked = mutableListOf<TracklistKey>()
        override val states: StateFlow<Map<TracklistKey, TracklistState>> = shown

        override fun look(key: TracklistKey) {
            asked += key
            shown.update { if (key in it) it else it + (key to TracklistState.Looking) }
        }

        override fun retry(key: TracklistKey) = Unit

        val dropped = mutableListOf<TracklistKey>()

        override fun dropAhead(key: TracklistKey) {
            dropped += key
            shown.update { if (it[key] == TracklistState.Looking) it - key else it }
        }
    }

    // The next song of the album, on another release of its group.
    private val nextKey = TracklistKey(group, "rec-carefree")
    private val next: TrackState =
        track(carefree).copy(
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec-carefree",
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", group, "Album")),
                ),
        )

    private val feed = Feed()

    private fun state(
        track: TrackState,
        settings: Settings = this.settings,
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
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, tracklists = feed))
                    }
                }
            }
            frames()
            block(current)
        }
    }

    private fun frames() {
        Snapshot.sendApplyNotifications()
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun after(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        frames()
    }

    private fun answer(vararg labels: String) {
        val release = MbTracklist("r", emptyList(), "1974", labels = labels.toList())
        feed.shown.update { it + (key to TracklistState.Found(release)) }
        frames()
    }

    private fun line(): String =
        compose
            .onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun assertHeld() {
        compose.onNodeWithTag(RELEASE_HELD_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theReleaseIsLookedUpWithNoFlipAndTheLineIsHeldUntilItsLabelThenFilledOnce() =
        show(state(resolved)) {
            assertEquals(listOf(key), feed.asked.distinct())
            assertHeld()
            val height =
                compose
                    .onNodeWithTag(RELEASE_HELD_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .size.height
            assertEquals(true, height > 0)
            after(3_000)
            assertHeld()
            answer("Reprise")
            compose.onNodeWithTag(RELEASE_HELD_TAG, useUnmergedTree = true).assertDoesNotExist()
            assertEquals("Reprise · 1974", line())
        }

    @Test
    fun aSkipGivesUpTheSkippedTracksLookupAndTheNextSongWaitsForItsOwnRelease() =
        show(state(resolved)) { current ->
            current.value = state(next)
            frames()
            // The first song's release, still asked, given up; the next one's asked.
            assertEquals(listOf(key), feed.dropped)
            assertEquals(listOf(key, nextKey), feed.asked.distinct())
            assertHeld()
            // Another song's release of the album found meanwhile does not end the wait.
            answer("Reprise")
            assertHeld()
            val release = MbTracklist("r2", emptyList(), "1974", labels = listOf("Warner"))
            feed.shown.update { it + (nextKey to TracklistState.Found(release)) }
            frames()
            assertEquals("Warner · 1974", line())
        }

    @Test
    fun withNoAnswerInTheWaitTheYearAloneThenTheLabelOnceItComes() =
        show(state(resolved)) {
            after(NAMING_WAIT.inWholeMilliseconds - 1_000)
            assertHeld()
            after(1_000)
            assertEquals("1974", line())
            answer("Reprise")
            assertEquals("Reprise · 1974", line())
        }

    @Test
    fun aReleaseWithNoLabelGivesTheYearAloneAtOnce() =
        show(state(resolved)) {
            answer()
            assertEquals("1974", line())
        }

    @Test
    fun withMusicBrainzOffThePlayersYearAloneAtOnceAndNothingLookedUp() {
        val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.MUSICBRAINZ)
        val played = sundown.copy(year = 1974)
        val track = track(played, sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.SourceOff)))
        show(state(track, off)) {
            assertEquals("1974", line())
            assertEquals(emptyList<TracklistKey>(), feed.asked)
        }
    }

    private companion object {
        const val FRAMES = 3
    }
}
