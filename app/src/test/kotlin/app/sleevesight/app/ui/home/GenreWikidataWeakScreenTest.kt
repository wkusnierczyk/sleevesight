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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontStyle
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An Instrumental only Wikidata states (#1292), as *Genres* shows it: weak, read as any source's
 * weak genre, its line under the definition saying that only Wikidata's user-edited statements mark
 * the track so; with LRCLIB's mark backing it too and nothing else, that only the two do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenreWikidataWeakScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // Jazz from Last.fm's artist tags, Instrumental from Wikidata's statement on the song's item alone.
    private fun stated(singing: Singing): TrackState {
        val vocabulary = VocalsFixtures.vocabulary
        val jazz = vocabulary.labelsFor(listOf("jazz"), level = GenreLevel.ARTIST, artist = ARTIST)
        val instrumental = vocabulary.labelsFor(listOf("instrumental"), ITEM, GenreLevel.TRACK)
        return HomeFixtures.track(VocalsFixtures.bite.nowPlaying).copy(
            genres =
                combineGenres(
                    mapOf(LabelSource.LASTFM to jazz, LabelSource.WIKIDATA to instrumental),
                    listOf(ARTIST),
                    singing,
                ),
        )
    }

    private fun weakLine(track: TrackState): String {
        var text = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(track, Settings(), notes = PanelNotes(VocalsFixtures.Notes(null)))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(CHIP).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Instrumental, weak evidence").assertExists()
            val line =
                compose
                    .onNodeWithTag(GENRE_WEAK_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val italic = line.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
            assertEquals(listOf("Instrumental"), italic.map { line.text.substring(it.start, it.end) })
            text = line.text
        }
        return text
    }

    @Test
    fun anInstrumentalOnlyWikidataStatesIsWeakWithItsLine() {
        assertEquals(
            "Only Wikidata’s user-edited statements mark this track Instrumental.",
            weakLine(stated(Singing())),
        )
    }

    @Test
    fun withLrclibsMarkAndWikidataAloneTheLineNamesBoth() {
        assertEquals(
            "Only LRCLIB’s user-submitted records and Wikidata’s user-edited statements mark this track Instrumental.",
            weakLine(stated(Singing(library = LibraryLyrics.INSTRUMENTAL))),
        )
    }

    private companion object {
        const val ARTIST = "Shay Hazan"
        const val ITEM = "https://www.wikidata.org/wiki/Q1"
        const val CHIP = GENRE_PREFIX + "track:instrumental"
    }
}
