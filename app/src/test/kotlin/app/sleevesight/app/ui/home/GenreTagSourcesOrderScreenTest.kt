package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Tag sources* names a row's sources alphabetically (#1098), whatever order they are declared or
 * their labels combine in: "Last.fm · MusicBrainz · Wikidata".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenreTagSourcesOrderScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val vocabulary = VocalsFixtures.vocabulary

    // Pop on the track's row from all three sources, each given in the map's (and the declaration's) other order.
    private val track =
        HomeFixtures.track(VocalsFixtures.bite.nowPlaying).copy(
            genres =
                combineGenres(
                    mapOf(
                        LabelSource.WIKIDATA to vocabulary.labelsFor(listOf("pop"), "https://wd", GenreLevel.TRACK),
                        LabelSource.MUSICBRAINZ to vocabulary.labelsFor(listOf("pop"), "https://mb", GenreLevel.TRACK),
                        LabelSource.LASTFM to vocabulary.labelsFor(listOf("pop"), "https://lf", GenreLevel.TRACK),
                    ),
                    emptyList(),
                ),
        )

    @Test
    fun theRowsSourcesAreNamedAlphabetically() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(track, Settings(), notes = PanelNotes(VocalsFixtures.Notes(null)))
                    }
                }
            }
            compose.waitForIdle()
            // The panel opens on Pop, the first backed genre, its tags and sources shown.
            val row =
                compose
                    .onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .joinToString { it.text }
            assertEquals("Track, Last.fm · MusicBrainz · Wikidata", row)
        }
    }
}
