package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The genres (#439): the fold, the table and its rows, one level, none, and where a label comes from. */
@RunWith(RobolectricTestRunner::class)
class GenresScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun label(
        tag: String,
        level: GenreLevel,
        artist: String? = null,
        source: LabelSource = LabelSource.LASTFM,
    ): TrackLabel {
        val shown = tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val genre = GenreLabel(tag, "family", shown, tag.replace(" ", ""), level = level, artist = artist)
        return TrackLabel(genre, listOf(LabelCredit(source, "https://example.org/$tag")))
    }

    private fun row(
        level: GenreLevel,
        vararg tags: String,
        artist: String? = null,
    ) = GenreRow(level, artist, tags.map { label(it, level, artist) })

    private val track = row(GenreLevel.TRACK, "baroque pop", "ballad")
    private val beatles = row(GenreLevel.ARTIST, "rock", "pop", artist = "The Beatles")
    private val album = row(GenreLevel.ALBUM, "folk rock", "pop")

    private fun show(
        genres: TrackGenres,
        settings: Settings = Settings(),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Genres(track().copy(genres = genres), settings)
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun chips(level: GenreLevel): List<String> =
        compose
            .onAllNodes(
                SemanticsMatcher("a ${level.name} chip") {
                    it.config
                        .getOrNull(
                            SemanticsProperties.TestTag,
                        )?.startsWith(GENRE_PREFIX + level.name.lowercase()) ==
                        true
                },
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    private fun stateOfFold(): String? =
        compose
            .onNodeWithTag(GENRES_FOLD_TAG)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun rowWords(): List<String?> =
        compose
            .onAllNodes(
                SemanticsMatcher("a row's name") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(GENRES_ROW_PREFIX) == true
                },
            ).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.single() }

    @Test
    fun foldedTheMostSpecificLevelShowsAndUnfoldedATableInTheHeadersOrder() {
        show(TrackGenres(listOf(track, beatles, album))) {
            assertEquals(listOf("Baroque Pop", "Ballad"), chips(GenreLevel.TRACK))
            assertEquals(emptyList<String>(), chips(GenreLevel.ARTIST))
            assertEquals(text(R.string.home_genres_collapsed), stateOfFold())

            compose.onNodeWithTag(GENRES_FOLD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(text(R.string.home_genres_expanded), stateOfFold())
            assertEquals(
                listOf("Track: Baroque Pop, Ballad", "Artist, The Beatles: Rock, Pop", "Album: Folk Rock, Pop"),
                rowWords(),
            )
            // A genre on two levels shows on each.
            assertEquals(listOf("Rock", "Pop"), chips(GenreLevel.ARTIST))
            assertEquals(listOf("Folk Rock", "Pop"), chips(GenreLevel.ALBUM))

            compose.onNodeWithTag(GENRES_FOLD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(emptyList<String?>(), rowWords())
        }
    }

    @Test
    fun withNoTrackRowFoldedTheAlbumComesBeforeTheArtistsAndUnfoldedARowForEachArtist() {
        val second = row(GenreLevel.ARTIST, "jazz", artist = "Miles Davis")
        show(TrackGenres(listOf(beatles, second, album))) {
            assertEquals(listOf("Folk Rock", "Pop"), chips(GenreLevel.ALBUM))
            compose.onNodeWithTag(GENRES_FOLD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(
                listOf("Artist, The Beatles: Rock, Pop", "Artist, Miles Davis: Jazz", "Album: Folk Rock, Pop"),
                rowWords(),
            )
        }
    }

    @Test
    fun oneLevelShowsItsChipsWithNoFold() {
        val second = row(GenreLevel.ARTIST, "rock", "jazz", artist = "Miles Davis")
        show(TrackGenres(listOf(beatles, second))) {
            assertEquals(0, compose.onAllNodesWithTag(GENRES_FOLD_TAG).fetchSemanticsNodes().size)
            // The artists' together, each genre once.
            assertEquals(listOf("Rock", "Pop", "Jazz"), chips(GenreLevel.ARTIST))
            assertEquals(emptyList<String?>(), rowWords())
        }
    }

    @Test
    fun noneFoundOrOffInSettings() {
        show(TrackGenres()) {
            compose.onNodeWithText(text(R.string.home_genres_none)).assertExists()
        }
        show(TrackGenres(), Settings(labelSources = emptySet())) {
            compose.onNodeWithText(text(R.string.home_genres_off)).assertExists()
            compose.onNodeWithText(text(R.string.home_genres_none)).assertDoesNotExist()
        }
    }

    @Test
    fun aTapOnALabelSaysWhichLevelsTagsGaveIt() {
        show(TrackGenres(listOf(track, beatles, album))) {
            val chip = compose.onNodeWithTag(GENRE_PREFIX + "track:baroquepop")
            chip.assertIsNotSelected().performClick()
            chip.assertIsSelected()
            compose.onNodeWithText(text(R.string.home_genre_from_track, "Baroque Pop")).assertExists()

            // The selection survives unfolding.
            compose.onNodeWithTag(GENRES_FOLD_TAG).performClick()
            compose.onNodeWithTag(GENRE_PREFIX + "track:baroquepop").assertIsSelected()
            compose.onNodeWithTag(GENRE_PREFIX + "album:folkrock").performClick()
            compose.onNodeWithText(text(R.string.home_genre_from_album, "Folk Rock")).assertExists()
            compose.onNodeWithTag(GENRE_PREFIX + "artist:rock").performClick()
            compose.onNodeWithText(text(R.string.home_genre_from, "Rock")).assertExists()

            // Folded, an artist's chip is not shown, nor its credits.
            compose.onNodeWithTag(GENRES_FOLD_TAG).performClick()
            compose.onNodeWithText(text(R.string.home_genre_from, "Rock")).assertDoesNotExist()
        }
    }

    @Test
    fun aSelectionOutlastsLabelsThatLandLater() {
        val genres = mutableStateOf(TrackGenres(listOf(beatles)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { Genres(track().copy(genres = genres.value), Settings()) }
            }
            scenario.onActivity {
                compose.onNodeWithTag(GENRE_PREFIX + "artist:rock").performClick()
                genres.value =
                    TrackGenres(
                        listOf(
                            beatles.copy(
                                labels =
                                    beatles.labels + label("jazz", GenreLevel.ARTIST, "The Beatles"),
                            ),
                        ),
                    )
                compose.waitForIdle()
                compose.onNodeWithTag(GENRE_PREFIX + "artist:rock").assertIsSelected()
                compose.onNodeWithText(text(R.string.home_genre_from, "Rock")).assertExists()
            }
        }
    }

    @Test
    fun aLabelOnlyMusicBrainzGivesShowsItsLicence() {
        val folk = label("folk", GenreLevel.ARTIST, "Gordon Lightfoot", LabelSource.MUSICBRAINZ)
        show(TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(folk))))) {
            val chip = compose.onNodeWithTag(GENRE_PREFIX + "artist:folk")
            chip.assertIsNotSelected().performClick()
            chip.assertIsSelected()
            compose.onNodeWithText(LabelSource.MUSICBRAINZ.credit, substring = true).assertExists()
        }
    }
}
