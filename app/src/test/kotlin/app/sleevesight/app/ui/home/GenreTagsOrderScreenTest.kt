package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The *Genre tags* grid's rows in the order of *Genres*' chips (#1159): a row whose tags arrive in the
 * sources' order (*Thriller*'s track row: Pop, Dance, Contemporary R&B, Disco, Dance-Pop) shows and
 * reads them as the chips are sorted, alphabetically, on every row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenreTagsOrderScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun label(
        tag: String,
        shown: String,
        level: GenreLevel,
        artist: String? = null,
    ): TrackLabel {
        val genre = GenreLabel(tag, shown, tag.filter(Char::isLetter), level = level, artist = artist)
        // Backed (#875), so the order is the names' alone.
        val votes = TrackGenres.MIN_VOTES.takeIf { level == GenreLevel.TRACK }
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")), votes)
    }

    // The track's tags in the sources' order, as *Thriller*'s came.
    private val trackRow =
        GenreRow(
            GenreLevel.TRACK,
            null,
            listOf(
                label("pop", "Pop", GenreLevel.TRACK),
                label("dance", "Dance", GenreLevel.TRACK),
                label("contemporary r&b", "Contemporary R&B", GenreLevel.TRACK),
                label("disco", "Disco", GenreLevel.TRACK),
                label("dance-pop", "Dance-Pop", GenreLevel.TRACK),
            ),
        )

    // The artist's, out of order too.
    private val artistRow =
        GenreRow(
            GenreLevel.ARTIST,
            "Michael Jackson",
            listOf(
                label("soul", "Soul", GenreLevel.ARTIST, "Michael Jackson"),
                label("funk", "Funk", GenreLevel.ARTIST, "Michael Jackson"),
                label("pop", "Pop", GenreLevel.ARTIST, "Michael Jackson"),
            ),
        )

    private val genres = TrackGenres(listOf(trackRow, artistRow))

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TagsGrid(track().copy(genres = genres), genre = "pop")
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    // [row]'s tags as the grid draws them: its cells by place, line by line, left to right.
    private fun drawn(row: GenreRow): List<String> =
        row.labels
            .map { label ->
                val tag = tagOf(GENRE_CELL_PREFIX, PlacedLabel(row, label))
                val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                Triple(bounds.top, bounds.left, label.genre.label)
            }.sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }

    @Test
    fun aRowWhoseTagsArriveOutOfOrderShowsThemInTheChipsOrder() =
        show {
            val chips = listOf("Contemporary R&B", "Dance", "Dance-Pop", "Disco", "Pop")
            assertEquals(chips, drawn(trackRow))
            assertEquals(
                "Track, Contemporary R&B, Dance, Dance-Pop, Disco, Pop, selected",
                compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading(),
            )
            assertEquals(listOf("Funk", "Pop", "Soul"), drawn(artistRow))
            assertEquals(
                "Artist, Funk, Pop, selected, Soul",
                compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").reading(),
            )
        }

    @Test
    fun eachRowFollowsTheChipsOwnOrder() {
        val chips = genres.chips().map { it.label.genre.identity }
        for (row in inChipOrder(genres.table, genres.chips())) {
            val ids = row.labels.map { it.genre.identity }
            assertEquals(chips.filter { it in ids }, ids)
        }
    }
}
