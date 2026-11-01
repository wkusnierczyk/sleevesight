package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Home's in-progress messages with the turning record before each (#785): a source's *Looking…*
 * in *Sources*' table and the line under it, *Dates*' and *Lyrics*' "Looking on MusicBrainz…",
 * *Genres*' "Looking on …", and *Key insights*' "Gathering evidence…"; and none by what a finished
 * lookup says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class BusyMessagesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    // Every source still asked: nothing found yet, no evidence, no dates.
    private val looking: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to SourceState.Pending,
                    SourceId.WIKIPEDIA to SourceState.Pending,
                    SourceId.LASTFM to SourceState.Pending,
                ),
            evidence = null,
        ).copy(genresAssembled = false)

    // Every source landed: MusicBrainz with the track and its dates, Wikipedia with nothing found.
    private val finished: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to done(evidence = listOf(HomeFixtures.musicBrainzLine)),
                    SourceId.WIKIPEDIA to done(Outcome.None),
                ),
        ).copy(
            resolution =
                Resolution(
                    firstRelease = FirstRelease("1974-01", "Sundown", "g-sundown", "r-sundown", "Album"),
                    releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "g-sundown")),
                ),
        )

    private fun show(
        track: TrackState,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(HomeFixtures.normalizer::normalize))
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

    private fun records(): List<Rect> =
        compose
            .onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.placed() }

    /** [message] has the record before it, 8 dp away, beside its first line: one, and only one. */
    private fun assertRecordBefore(message: SemanticsNodeInteraction) {
        val bounds = message.fetchSemanticsNode().placed()
        val gap = 8f * compose.density.density
        val beside =
            records().filter {
                abs(bounds.left - it.right - gap) <= 1f && it.center.y > bounds.top && it.center.y < bounds.bottom
            }
        assertEquals("records before the message at $bounds", 1, beside.size)
    }

    private fun messages(line: String): List<SemanticsNodeInteraction> {
        val count = compose.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().size
        return (0 until count).map { compose.onAllNodesWithText(line, useUnmergedTree = true)[it] }
    }

    @Test
    fun aSourceStillAskedAndTheLineUnderTheTableHaveTheRecordBeforeLooking() =
        show(looking) {
            tap(SOURCES_CHIP_TAG)
            for (source in listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM)) {
                val status = compose.onNodeWithTag(STATUS_PREFIX + source.tag, useUnmergedTree = true)
                assertEquals(text(R.string.home_source_pending), status.fetchSemanticsNode().config.text())
                assertRecordBefore(status)
            }
            // The rows' *Looking…* and the line under the table's, each with its own record.
            val lines = messages(text(R.string.home_evidence_looking))
            assertEquals(4, lines.size)
            lines.forEach(::assertRecordBefore)
        }

    @Test
    fun keyInsightsGatheringEvidenceHasTheRecordBeforeIt() =
        show(looking) { assertRecordBefore(compose.onNodeWithText(text(R.string.home_insight_gathering))) }

    @Test
    fun datesLookingOnMusicBrainzHasTheRecordBeforeIt() =
        show(looking) {
            tap(DATES_CHIP_TAG)
            assertRecordBefore(compose.onNodeWithText(text(R.string.home_musicbrainz_looking)))
        }

    @Test
    fun lyricsLookingOnMusicBrainzHasTheRecordBeforeIt() =
        show(looking) {
            tap(LYRICS_CHIP_TAG)
            assertRecordBefore(compose.onNodeWithText(text(R.string.home_musicbrainz_looking)))
        }

    @Test
    fun genresLookingHasTheRecordBeforeIt() =
        show(looking) {
            tap(GENRES_CHIP_TAG)
            assertRecordBefore(compose.onNodeWithText(text(R.string.home_genres_looking, "Last.fm and MusicBrainz")))
        }

    @Test
    fun whatAFinishedLookupSaysHasNoRecord() =
        show(finished) {
            tap(SOURCES_CHIP_TAG)
            tap(DATES_CHIP_TAG)
            compose.onNodeWithText(text(R.string.home_source_none)).assertExists()
            compose.onNodeWithText("This album was released in January 1974.").assertExists()
            assertEquals(emptyList<Rect>(), records())
        }

    @Test
    fun theOneMessagePerRecordWhileEverySourceIsAsked() =
        show(looking) {
            tap(SOURCES_CHIP_TAG)
            // Three rows and the line under the table, and Key insights' line: a record each.
            assertEquals(5, records().size)
        }
}

// Where [this] is laid out in the root, clipped or not (the screen scrolls, and clips what is off it).
private fun SemanticsNode.placed(): Rect = Rect(positionInRoot, size.toSize())

private fun SemanticsConfiguration.text(): String = this[SemanticsProperties.Text].joinToString { it.text }
