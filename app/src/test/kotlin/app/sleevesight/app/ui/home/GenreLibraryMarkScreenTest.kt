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
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Instrumental LRCLIB's mark adds for a song whose work credits composers alone (#1024), as
 * *Genres* shows it: a source's chip on the track's row, not weak, not marked AI, read as any
 * source's genre; under *Tag sources*, "LRCLIB", not linked; no check of the track's vocals.
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
                VocalsFixtures.bite.genres.withLibrary(
                    LibraryLyrics.INSTRUMENTAL,
                    VocalsFixtures.vocabulary.instrumental(),
                ),
        )

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

    private companion object {
        const val CHIP = GENRE_PREFIX + "track:instrumental"
    }
}
