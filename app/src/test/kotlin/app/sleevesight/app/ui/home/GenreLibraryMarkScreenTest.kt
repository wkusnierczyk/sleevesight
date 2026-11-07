package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.pipeline.withLibraryMark
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Instrumental LRCLIB's mark adds for a song whose work credits composers alone (#1024), as
 * *Genres* shows it: a source's chip on the track's row, not weak, not marked AI, read as any
 * source's genre; under *Tag sources*, "LRCLIB", not linked; no check of the track's vocals. Alone,
 * it leaves *Search with AI* offered, and "None found." unsaid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenreLibraryMarkScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // *A Bite of Sand* as the run gives it once LRCLIB marked it instrumental.
    private val marked: TrackState =
        VocalsFixtures.bite.copy(
            genres =
                VocalsFixtures.bite.genres
                    .withLibrary(LibraryLyrics.INSTRUMENTAL)
                    .withLibraryMark(VocalsFixtures.vocabulary.instrumental()),
        )

    // The same with no source's tag: the mark's Instrumental alone.
    private val untagged: TrackState =
        HomeFixtures.track(VocalsFixtures.bite.nowPlaying).copy(
            genres =
                combineGenres(
                    emptyMap(),
                    listOf("Shay Hazan"),
                    Singing(composersOnly = true, library = LibraryLyrics.INSTRUMENTAL),
                ).withLibraryMark(VocalsFixtures.vocabulary.instrumental()),
        )

    // *Search with AI* answering Jazz for the genres.
    private val feed =
        object : AiSearchFeed by AiSearchFeed.None {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> =
                flowOf(
                    if (kind == SearchKind.GENRES) {
                        val jazz = VocalsFixtures.vocabulary.labelsFor(listOf("jazz"), level = GenreLevel.TRACK)
                        AiSearch.Answered(SearchAnswer.Genres(jazz), "Model")
                    } else {
                        AiSearch.None
                    },
                )

            override fun answered(
                track: TrackState,
                kind: SearchKind,
            ): Flow<AiSearch> = search(track, kind)
        }

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(marked, Settings(), notes = PanelNotes(VocalsFixtures.Notes(null)))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(CHIP).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun textOf(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    @Test
    fun theMarksInstrumentalIsASourcesChipCreditedToLrclib() =
        show {
            compose.onNodeWithTag(CHIP).assertExists()
            compose.onNodeWithContentDescription("Instrumental, weak evidence").assertDoesNotExist()
            compose
                .onNodeWithContentDescription("Instrumental, from", substring = true)
                .assertDoesNotExist()
            assertEquals("Track, Instrumental, selected", compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading())
            assertEquals("Track, LRCLIB", textOf(GENRE_SOURCES_ROW_PREFIX + "0"))
            // Settled by the mark: no check, and no line of one.
            compose.onNodeWithText("Checking whether this track has vocals…").assertDoesNotExist()
        }

    @Test
    fun withTheMarksInstrumentalAloneSearchWithAiIsStillOfferedAndNoneFoundIsNotSaid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            untagged,
                            Settings(),
                            notes = PanelNotes(VocalsFixtures.Notes(null)),
                            search = PanelSearch(feed, untagged),
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(CHIP).assertExists()
            // The search's genres under it, as under an empty *Genres*.
            compose.onNodeWithTag(GENRE_PREFIX + "track:jazz").assertExists()
            compose.onNodeWithText("The sources give no genres.").assertDoesNotExist()
        }
    }

    private companion object {
        const val CHIP = GENRE_PREFIX + "track:instrumental"
    }
}
