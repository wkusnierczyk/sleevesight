package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
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
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The genres under *Expandable boxes* (#439, #558): the chips alone; a chip opens its genre's
 * panel, another switches it, the same chip or *Close* closes it; the panel's table names its rows
 * by the track's title, the artist's name and the album's title, on one line each and read whole,
 * the genre marked wherever it shows; where the genre comes from; none, and off.
 */
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
    private val three = TrackGenres(listOf(track, beatles, album))

    private fun show(
        genres: TrackGenres,
        settings: Settings = Settings(),
        playing: TrackState = track(),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Genres(playing.copy(genres = genres), settings)
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun tagged(prefix: String): List<String> =
        compose
            .onAllNodes(
                SemanticsMatcher("tagged $prefix") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
                },
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    // The chips on Home, and the labels in the panel's table, of [level].
    private fun chips(level: GenreLevel) = tagged(GENRE_PREFIX + level.name.lowercase())

    private fun cells(level: GenreLevel) = tagged(GENRE_CELL_PREFIX + level.name.lowercase())

    private fun rowNames() = tagged(GENRES_ROW_PREFIX)

    private fun panels() = compose.onAllNodesWithTag(GENRE_PANEL_TAG).fetchSemanticsNodes().size

    private fun heading() = compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG)

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun clickLabel(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.OnClick)
            ?.label

    @Test
    fun atRestTheMostSpecificLevelsChipsShowAloneWithNoTitleAndNoPanel() {
        show(three) {
            assertEquals(listOf("Baroque Pop", "Ballad"), chips(GenreLevel.TRACK))
            assertEquals(emptyList<String>(), chips(GenreLevel.ARTIST))
            val titles = compose.onAllNodesWithText(text(R.string.home_genres), substring = true)
            assertEquals(0, titles.fetchSemanticsNodes().size)
            assertEquals(0, panels())
            assertEquals(emptyList<String>(), rowNames())
        }
    }

    @Test
    fun aChipOpensItsPanelAnotherSwitchesItAndTheSameChipOrCloseClosesIt() {
        show(three) {
            val baroque = GENRE_PREFIX + "track:baroquepop"
            val ballad = GENRE_PREFIX + "track:ballad"
            tap(baroque)
            assertEquals(1, panels())
            heading().assertTextEquals("Baroque Pop")
            compose.onNodeWithTag(baroque).assertIsSelected()

            tap(ballad)
            assertEquals(1, panels())
            heading().assertTextEquals("Ballad")
            compose.onNodeWithTag(baroque).assertIsNotSelected()
            compose.onNodeWithTag(ballad).assertIsSelected()

            tap(ballad)
            assertEquals(0, panels())
            compose.onNodeWithTag(ballad).assertIsNotSelected()

            tap(baroque)
            assertEquals(1, panels())
            tap(GENRE_PANEL_CLOSE_TAG)
            assertEquals(0, panels())
            compose.onNodeWithTag(baroque).assertIsNotSelected()
        }
    }

    @Test
    fun thePanelsTableNamesItsRowsByTheTracksTitleTheArtistAndTheAlbumsTitle() {
        show(three) {
            tap(GENRE_PREFIX + "track:baroquepop")
            // Sundown, by Gordon Lightfoot, from Sundown: the row names, not *Track*, *Artist*, *Album*.
            assertEquals(listOf("Sundown", "The Beatles", "Sundown"), rowNames())
            val levels = listOf(R.string.home_genres_track, R.string.home_genres_artist_row, R.string.home_genres_album)
            for (level in levels) {
                assertEquals(0, compose.onAllNodesWithText(text(level)).fetchSemanticsNodes().size)
            }
            assertEquals(listOf("Baroque Pop", "Ballad"), cells(GenreLevel.TRACK))
            assertEquals(listOf("Rock", "Pop"), cells(GenreLevel.ARTIST))
            assertEquals(listOf("Folk Rock", "Pop"), cells(GenreLevel.ALBUM))
        }
    }

    @Test
    fun theTappedGenreIsMarkedWhereverItShowsInTheTable() {
        // Pop is the track's, and on the artist's and the album's rows.
        val pop = TrackGenres(listOf(row(GenreLevel.TRACK, "pop"), beatles, album))
        show(pop) {
            tap(GENRE_PREFIX + "track:pop")
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "track:pop").assertIsSelected()
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "artist:The Beatles:pop").assertIsSelected()
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "album:pop").assertIsSelected()
            for (other in listOf("artist:The Beatles:rock", "album:folkrock")) {
                val config = compose.onNodeWithTag(GENRE_CELL_PREFIX + other).fetchSemanticsNode().config
                assertTrue("$other is not marked", config.getOrNull(SemanticsProperties.Selected) != true)
                // The table's labels are not buttons.
                assertEquals(null, config.getOrNull(SemanticsActions.OnClick))
            }
        }
    }

    // Native graphics, so the text is measured as on a phone.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLongRowNameIsClippedToOneLineAndAScreenReaderReadsItWhole() {
        val long = "Project Hail Mary (Original Motion Picture Soundtrack) [Deluxe Edition, Remastered 2026]"
        val playing = track(HomeFixtures.sundown.copy(album = long))
        show(three, playing = playing) {
            tap(GENRE_PREFIX + "track:baroquepop")
            val node = compose.onNodeWithTag(GENRES_ROW_PREFIX + "2")
            node.assertTextEquals(long)
            val layouts = mutableListOf<TextLayoutResult>()
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            assertTrue("the album's title is clipped with an ellipsis", layout.isLineEllipsized(0))
        }
    }

    @Test
    fun aScreenReaderReadsTheChipsAsButtonsWithTheirStateAndThePanelsHeading() {
        show(three) {
            val baroque = GENRE_PREFIX + "track:baroquepop"
            compose
                .onNodeWithTag(baroque)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsNotSelected()
            assertEquals(text(R.string.home_genre_show), clickLabel(baroque))
            tap(baroque)
            compose.onNodeWithTag(baroque).assertIsSelected()
            assertEquals(text(R.string.home_genre_close), clickLabel(baroque))
            assertEquals(text(R.string.home_genre_show), clickLabel(GENRE_PREFIX + "track:ballad"))
            heading().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun thePanelSaysWhereTheGenreComesFromALinePerLevelWithMusicBrainzsLicence() {
        val folk = label("folk", GenreLevel.ARTIST, "Gordon Lightfoot", LabelSource.MUSICBRAINZ)
        val genres =
            TrackGenres(
                listOf(
                    row(GenreLevel.TRACK, "folk"),
                    GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(folk)),
                ),
            )
        show(genres) {
            tap(GENRE_PREFIX + "track:folk")
            compose
                .onNodeWithText(text(R.string.home_genre_from_track) + " " + LabelSource.LASTFM.credit)
                .assertExists()
            compose
                .onNodeWithText(
                    text(R.string.home_genre_from) + " " + LabelSource.MUSICBRAINZ.credit + " · " +
                        text(R.string.home_genre_licence),
                ).assertExists()
        }
    }

    @Test
    fun thePanelCreditsEverySourceOfEveryLabelInTheTableNotOnlyTheOpenGenres() {
        // Pop only from Last.fm, on the track; Rock only from MusicBrainz, on the artist; the album's
        // two labels from one source each.
        val rock = label("rock", GenreLevel.ARTIST, "Gordon Lightfoot", LabelSource.MUSICBRAINZ)
        val albumRow =
            GenreRow(
                GenreLevel.ALBUM,
                null,
                listOf(
                    label("folk rock", GenreLevel.ALBUM),
                    label("country", GenreLevel.ALBUM, source = LabelSource.MUSICBRAINZ),
                ),
            )
        val genres =
            TrackGenres(
                listOf(
                    row(GenreLevel.TRACK, "pop"),
                    GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(rock)),
                    albumRow,
                ),
            )
        show(genres) {
            tap(GENRE_PREFIX + "track:pop")
            val licence = " · " + text(R.string.home_genre_licence)
            compose
                .onNodeWithText(text(R.string.home_genre_from_track) + " " + LabelSource.LASTFM.credit)
                .assertExists()
            compose
                .onNodeWithText(text(R.string.home_genre_from) + " " + LabelSource.MUSICBRAINZ.credit + licence)
                .assertExists()
            // Each source once a row, in the sources' order.
            compose
                .onNodeWithText(
                    text(R.string.home_genre_from_album) + " " + LabelSource.LASTFM.credit + " · " +
                        LabelSource.MUSICBRAINZ.credit + licence,
                ).assertExists()
        }
    }

    @Test
    fun withSeveralArtistsTheLineNamesTheArtistWhoseTagsGaveIt() {
        val two = track(HomeFixtures.sundown.copy(artists = "The Beatles, Billy Preston"))
        show(TrackGenres(listOf(beatles)), playing = two) {
            tap(GENRE_PREFIX + "artist:The Beatles:rock")
            compose.onNodeWithText(text(R.string.home_genre_from_named, "The Beatles"), substring = true).assertExists()
        }
    }

    @Test
    fun withOneLevelEachChipOpensThePanelWithARowPerArtist() {
        val second = row(GenreLevel.ARTIST, "rock", "jazz", artist = "Miles Davis")
        show(TrackGenres(listOf(beatles, second))) {
            // The artists' together, each genre once.
            assertEquals(listOf("Rock", "Pop", "Jazz"), chips(GenreLevel.ARTIST))
            tap(GENRE_PREFIX + "artist:The Beatles:rock")
            assertEquals(listOf("The Beatles", "Miles Davis"), rowNames())
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "artist:Miles Davis:rock").assertIsSelected()
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
    fun showGenresOffShowsNoGenresAtAll() {
        // Labels the run holds still show nothing: *Show genres* off (#444) hides them all.
        show(three, Settings(showGenres = false)) {
            compose.onNodeWithText(text(R.string.home_genres_off)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.home_genres_none)).assertDoesNotExist()
            assertEquals(0, compose.onAllNodesWithTag(GENRE_PREFIX + "track:baroquepop").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun anOpenPanelOutlastsLabelsThatLandLaterAndANewTrackStartsClosed() {
        val genres = mutableStateOf(TrackGenres(listOf(beatles)))
        val playing = mutableStateOf(track())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { Genres(playing.value.copy(genres = genres.value), Settings()) }
            }
            scenario.onActivity {
                tap(GENRE_PREFIX + "artist:The Beatles:rock")
                // The album's labels land: the chips are now the album's, and the panel stays.
                genres.value = TrackGenres(listOf(beatles, album))
                compose.waitForIdle()
                assertEquals(listOf("Folk Rock", "Pop"), chips(GenreLevel.ALBUM))
                heading().assertTextEquals("Rock")
                assertEquals(listOf("The Beatles", "Sundown"), rowNames())

                playing.value = track(HomeFixtures.carefree)
                compose.waitForIdle()
                assertEquals(0, panels())
            }
        }
    }
}
