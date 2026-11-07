package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.PanelLibrary
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LRCLIB not reached when asked about the track's Instrumental (#997): *Genres*' panel says so under
 * the chips, with *Retry*, which asks LRCLIB again for the run under its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenresLibraryScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val retried = mutableListOf<Triple<RunKey, SourceId, NowPlaying>>()

    private val instrumental =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    listOf(
                        TrackLabel(
                            GenreLabel(
                                "instrumental",
                                "Instrumental",
                                "instrumental",
                                level = GenreLevel.TRACK,
                            ),
                            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/instrumental")),
                        ),
                    ),
                ),
            ),
        )

    private fun state(unreachable: Boolean): NowPlayingState {
        val track =
            track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done()))
                .copy(genres = instrumental, libraryUnreachable = unreachable)
        return NowPlayingState(
            PipelineView(
                track,
                aiReady = true,
                askAgainKey = RunKey.of(normalizer, sundown),
                askable = setOf(SourceId.LRCLIB),
            ),
            track.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = Settings(),
        )
    }

    // The panel's own LRCLIB lookups (#997's review): the AI's list adds Instrumental to a track whose
    // sources gave none; LRCLIB, asked for it, could not be reached; *Retry*s counted.
    private val panelRetries = mutableListOf<TrackState>()
    private val notes =
        object : GenreNotes by GenreNotes.None {
            override fun aiGenres(track: TrackState): Flow<AiGenreList?> =
                flowOf(
                    AiGenreList(
                        listOf(
                            instrumental.rows
                                .single()
                                .labels
                                .single()
                                .genre,
                        ),
                        "Model",
                    ),
                )

            override fun libraryLyrics(track: TrackState): Flow<PanelLibrary?> = flowOf(PanelLibrary.Unreachable)

            override fun retryLibrary(track: TrackState) {
                panelRetries += track
            }
        }

    private fun show(
        state: NowPlayingState,
        genreNotes: GenreNotes = GenreNotes.None,
        block: () -> Unit,
    ) {
        val actions =
            NowPlayingActions(normalizer::normalize, genreNotes = genreNotes, askAgain = { key, source, shown ->
                retried +=
                    Triple(key, source, shown)
            })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                    ) { NowPlayingContent(state, actions) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(GENRES_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun lrclibNotReachedSaysSoWithRetryWhichAsksItAgain() =
        show(state(unreachable = true)) {
            val line = app.getString(R.string.home_genre_source_unreachable, "LRCLIB")
            assertEquals("LRCLIB could not be reached.", line)
            compose.onNodeWithText(line).assertExists()
            compose.onNodeWithTag(RETRY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), SourceId.LRCLIB, sundown)), retried)
        }

    @Test
    fun lrclibReachedSaysNothing() =
        show(state(unreachable = false)) {
            compose.onNodeWithText("LRCLIB could not be reached.").assertDoesNotExist()
        }

    @Test
    fun anAiAddedInstrumentalWhoseLookupFailedSaysSoWithThePanelsRetry() {
        val track =
            track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done()))
                .copy(genres = TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, listOf(folk)))))
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        show(state, notes) {
            compose.onNodeWithText("LRCLIB could not be reached.").assertExists()
            compose.onNodeWithTag(RETRY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, panelRetries.size)
            assertEquals(emptyList<Triple<RunKey, SourceId, NowPlaying>>(), retried)
        }
    }

    private val folk =
        TrackLabel(
            GenreLabel("folk", "Folk", "folk", level = GenreLevel.TRACK),
            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/folk")),
        )
}
