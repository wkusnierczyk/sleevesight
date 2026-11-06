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
import app.sleevesight.core.settings.Settings
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
                                "family",
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

    private fun show(
        state: NowPlayingState,
        block: () -> Unit,
    ) {
        val actions =
            NowPlayingActions(normalizer::normalize, askAgain = { key, source, shown ->
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
}
