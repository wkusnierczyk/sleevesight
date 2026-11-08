package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Sources* when a match was rejected only for want of MusicBrainz, which could not be reached
 * (#1069: *Take California* by Propellerheads): Wikipedia's row says it was not confirmed without
 * MusicBrainz, with grounding details or not, and the note under the table says MusicBrainz could
 * not be reached. Any other rejection, the same rejection with MusicBrainz answering, and a source
 * with no article read as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesWithoutMusicBrainzScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val details = Settings(showGroundingDetails = true)

    private val artist =
        Rejection(
            "Propellerheads",
            "no MusicBrainz id for Propellerheads to confirm “Propellerheads”",
            Scope.ARTIST,
            withoutMusicBrainz = true,
        )
    private val song =
        Rejection(
            "Take California",
            "“Take California” names no performer or writer credited on the track",
            Scope.SONG,
            withoutMusicBrainz = true,
        )
    private val namesake = Rejection("Propellerheads", "“Propellerheads” is a disambiguation page", Scope.ARTIST)

    private fun wikipedia(
        vararg rejections: Rejection,
        cached: Boolean = false,
    ) = done(Outcome.Rejected(rejections.first().reason), cached = cached, rejections = rejections.toList())

    // The track with MusicBrainz's and Wikipedia's states, and the [resolution] Wikipedia's was checked
    // against: MusicBrainz's own outcome, by default.
    private fun state(
        musicBrainz: SourceState.Done,
        wikipedia: SourceState,
        resolution: Resolution = Resolution(outcome = musicBrainz.result.outcome),
    ): TrackState =
        track(sources = mapOf(SourceId.MUSICBRAINZ to musicBrainz, SourceId.WIKIPEDIA to wikipedia), evidence = null)
            .copy(resolution = resolution)

    private val unreached = done(Outcome.Unreachable)

    private fun show(
        track: TrackState,
        settings: Settings = Settings(),
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
            )
        val actions = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            scenario.onActivity {
                compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
                compose.waitForIdle()
                block()
            }
        }
    }

    /** What the row of [tag] says in words a screen reader reads as text, in order. */
    private fun said(tag: String): List<String> {
        val words = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.let { texts -> words += texts.map { it.text } }
            node.children.forEach(::walk)
        }
        walk(compose.onNodeWithTag(ROW_PREFIX + tag).fetchSemanticsNode())
        return words
    }

    /** The note under the table, as its text. */
    private fun note(): List<String> =
        compose
            .onNodeWithTag(SOURCES_NOTE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text }

    private fun countText(text: String) =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private val name get() = text(R.string.home_source_chip_wikipedia)
    private val unconfirmed get() = text(R.string.home_source_without_musicbrainz)

    @Test
    fun aMatchRejectedForWantOfMusicBrainzWhileItCouldNotBeReachedIsNotConfirmedWithoutIt() =
        show(state(unreached, wikipedia(song, artist))) {
            assertEquals(listOf(name, unconfirmed), said("W"))
            assertEquals(0, countText(text(R.string.home_source_none)))
            assertEquals(
                listOf(text(R.string.home_source_note_unreachable, text(R.string.settings_source_musicbrainz))),
                note(),
            )
            assertEquals("MusicBrainz could not be reached.", note().single())
        }

    @Test
    fun withGroundingDetailsTheRowSaysTheSameNotMatchRejected() =
        show(state(unreached, wikipedia(song, artist)), details) {
            assertEquals(listOf(name, unconfirmed), said("W"))
            assertEquals(0, countText(text(R.string.home_source_rejected)))
            assertEquals("MusicBrainz could not be reached.", note().single())
        }

    @Test
    fun aRejectionForAnotherReasonAmongThemReadsAsBefore() {
        show(state(unreached, wikipedia(song, namesake))) {
            assertEquals(listOf(name, text(R.string.home_source_none)), said("W"))
            assertEquals(0, countText(unconfirmed))
        }
        show(state(unreached, wikipedia(song, namesake)), details) {
            assertEquals(listOf(name, text(R.string.home_source_rejected)), said("W"))
        }
    }

    @Test
    fun withMusicBrainzAnsweringAMarkedRejectionReadsAsBefore() {
        show(state(done(Outcome.None), wikipedia(song, artist))) {
            assertEquals(listOf(name, text(R.string.home_source_none)), said("W"))
            assertEquals(0, countText(unconfirmed))
        }
        show(state(done(Outcome.None), wikipedia(song, artist)), details) {
            assertEquals(listOf(name, text(R.string.home_source_rejected)), said("W"))
        }
    }

    @Test
    fun aMarkedRejectionKeptFromBeforeReadsAsBeforeDuringALaterOutage() {
        // Kept, so made while MusicBrainz answered (a result made during an outage is never kept):
        // the outage now is not why its match was rejected.
        show(state(unreached, wikipedia(song, artist, cached = true))) {
            assertEquals(listOf(name, text(R.string.home_source_none)), said("W"))
            assertEquals(0, countText(unconfirmed))
        }
        show(state(unreached, wikipedia(song, artist, cached = true)), details) {
            val kept = text(R.string.home_source_cached, text(R.string.home_source_rejected))
            assertEquals(listOf(name, kept), said("W"))
        }
    }

    @Test
    fun aRetryOfWikipediaAloneAfterMusicBrainzAnswersReadsAsBefore() {
        // Both could not be reached; Wikipedia's Retry resolved again, and MusicBrainz answered with
        // no recording, while its row stays as it was: the outage is not why the match was rejected.
        val answered = Resolution(outcome = Outcome.None)
        show(state(unreached, wikipedia(song, artist), answered)) {
            assertEquals(listOf(name, text(R.string.home_source_none)), said("W"))
            assertEquals(0, countText(unconfirmed))
        }
        show(state(unreached, wikipedia(song, artist), answered), details) {
            assertEquals(listOf(name, text(R.string.home_source_rejected)), said("W"))
        }
    }

    @Test
    fun aSourceWithNoArticleReadsAsBefore() =
        show(state(unreached, done(Outcome.None))) {
            assertEquals(listOf(name, text(R.string.home_source_none)), said("W"))
            assertEquals(0, countText(unconfirmed))
        }
}
