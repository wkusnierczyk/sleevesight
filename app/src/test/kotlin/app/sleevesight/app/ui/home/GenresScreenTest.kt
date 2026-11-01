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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.LinkAnnotation
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
 * The genres under *Expandable boxes* (#439, #558, #786): the chips alone; a chip unfolds its genre
 * under them and a rule, with no heading or *Close*, another replaces it, the same chip folds it; its
 * tags and their sources in one grid of two columns, its rows named *Track*, *Artist* (*Artist 1*,
 * *Artist 2* with several artists, by their place in the credit) and *Album* (with album tags), each
 * one item to a screen reader, the genre marked wherever it shows, each source linked, MusicBrainz's
 * credit and licence only with its tags; none, and off.
 * Inside *Genres*' panel (#772), as [GenresContent] draws it; the button, the panel and why it has no
 * chip are `GenresButtonScreenTest`'s.
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
                        GenresContent(playing.copy(genres = genres), settings)
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    // The texts of the nodes tagged with [prefix], each node's joined: in the merged tree, a grid's
    // row reads as one item, its name then its chips or sources; [unmerged], each node alone.
    private fun tagged(
        prefix: String,
        unmerged: Boolean = false,
    ): List<String> =
        compose
            .onAllNodes(
                SemanticsMatcher("tagged $prefix") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
                },
                useUnmergedTree = unmerged,
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    // The chips on Home, and the labels in the grid, of [level].
    private fun chips(level: GenreLevel) = tagged(GENRE_PREFIX + level.name.lowercase())

    private fun cells(level: GenreLevel) = tagged(GENRE_CELL_PREFIX + level.name.lowercase(), unmerged = true)

    // The grid's rows of tags, and of sources, as a screen reader reads each.
    private fun rows() = tagged(GENRES_ROW_PREFIX)

    private fun sources() = tagged(GENRE_SOURCES_ROW_PREFIX)

    private fun panels() = compose.onAllNodesWithTag(GENRE_PANEL_TAG).fetchSemanticsNodes().size

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

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

    private fun bounds(
        tag: String,
        unmerged: Boolean = true,
    ) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    // The addresses the text [line] links, in order.
    private fun links(line: String): List<String> {
        val text =
            compose
                .onNodeWithText(line, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        return text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
    }

    @Test
    fun atRestTheMostSpecificLevelsChipsShowAloneWithNoTitleAndNoGenre() {
        show(three) {
            assertEquals(listOf("Baroque Pop", "Ballad"), chips(GenreLevel.TRACK))
            assertEquals(emptyList<String>(), chips(GenreLevel.ARTIST))
            val titles = compose.onAllNodesWithText(text(R.string.home_genres), substring = true)
            assertEquals(0, titles.fetchSemanticsNodes().size)
            assertEquals(0, panels())
            assertEquals(emptyList<String>(), rows())
        }
    }

    @Test
    fun aChipUnfoldsItsGenreUnderTheChipsAnotherReplacesItAndTheSameChipFoldsIt() {
        show(three) {
            val baroque = GENRE_PREFIX + "track:baroquepop"
            val ballad = GENRE_PREFIX + "track:ballad"
            tap(baroque)
            assertEquals(1, panels())
            compose.onNodeWithTag(baroque).assertIsSelected()
            // Under the chips, with no heading or *Close* of its own (#786).
            assertTrue(bounds(GENRE_PANEL_TAG).top >= bounds(baroque).bottom)
            assertEquals(0, count(GENRE_PANEL_HEADING_TAG))
            assertEquals(0, compose.onAllNodesWithText(text(R.string.page_close)).fetchSemanticsNodes().size)

            tap(ballad)
            assertEquals(1, panels())
            compose.onNodeWithTag(baroque).assertIsNotSelected()
            compose.onNodeWithTag(ballad).assertIsSelected()
            assertEquals(
                "Track, Baroque Pop, Ballad, selected",
                compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading(),
            )

            tap(ballad)
            assertEquals(0, panels())
            compose.onNodeWithTag(ballad).assertIsNotSelected()
        }
    }

    @Test
    fun theGridNamesItsRowsTrackArtistAndAlbumAndReadsEachRowAsOneItem() {
        show(three) {
            tap(GENRE_PREFIX + "track:baroquepop")
            compose
                .onNodeWithText(
                    text(R.string.home_genre_tags),
                ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose
                .onNodeWithText(
                    text(R.string.home_genre_tag_sources),
                ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertEquals("Genre tags", text(R.string.home_genre_tags))
            assertEquals("Tag sources", text(R.string.home_genre_tag_sources))
            // Each row one item: its name, then its chips (not buttons), or its sources.
            assertEquals(listOf("Track, Baroque Pop, Ballad", "Artist, Rock, Pop", "Album, Folk Rock, Pop"), rows())
            assertEquals(listOf("Track, Last.fm", "Artist, Last.fm", "Album, Last.fm"), sources())
            assertEquals(listOf("Baroque Pop", "Ballad"), cells(GenreLevel.TRACK))
            assertEquals(listOf("Rock", "Pop"), cells(GenreLevel.ARTIST))
            assertEquals(listOf("Folk Rock", "Pop"), cells(GenreLevel.ALBUM))
            // The tags above the sources, each under its heading.
            assertTrue(bounds(GENRES_ROW_PREFIX + "2").bottom <= bounds(GENRE_SOURCES_ROW_PREFIX + "0").top)
        }
    }

    @Test
    fun albumShowsOnlyWithAlbumTags() {
        show(TrackGenres(listOf(track, beatles))) {
            tap(GENRE_PREFIX + "track:baroquepop")
            assertEquals(listOf("Track, Baroque Pop, Ballad", "Artist, Rock, Pop"), rows())
            assertEquals(
                0,
                compose
                    .onAllNodesWithText(
                        text(R.string.home_genres_album),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .size,
            )
        }
    }

    // Native graphics, so the text is measured as on a phone.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theFirstColumnIsTheWidestNamesAndSharedByTheTagsAndTheSources() {
        show(three) {
            tap(GENRE_PREFIX + "track:baroquepop")
            // Every row's right column starts at the same place: the first chip of each row of tags,
            // and each row's sources.
            val starts =
                listOf("track:baroquepop", "artist:The Beatles:rock", "album:folkrock").map {
                    bounds(GENRE_CELL_PREFIX + it).left
                } +
                    listOf(0, 1, 2).map {
                        compose
                            .onAllNodesWithText(
                                "Last.fm",
                                useUnmergedTree = true,
                            ).fetchSemanticsNodes()[it]
                            .boundsInRoot.left
                    }
            assertEquals(1, starts.distinct().size)
            // Each name whole on one line, right-aligned against that column.
            for (name in listOf(
                R.string.home_genres_track,
                R.string.home_genres_artist_row,
                R.string.home_genres_album,
            )) {
                val nodes = compose.onAllNodesWithText(text(name), useUnmergedTree = true).fetchSemanticsNodes()
                for (node in nodes) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    assertEquals(1, layouts.single().lineCount)
                    assertTrue(!layouts.single().isLineEllipsized(0))
                    assertTrue(node.boundsInRoot.right <= starts.first())
                }
            }
        }
    }

    @Test
    fun theTappedGenreIsMarkedWhereverItShowsInTheTags() {
        // Pop is the track's, and on the artist's and the album's rows.
        val pop = TrackGenres(listOf(row(GenreLevel.TRACK, "pop"), beatles, album))
        show(pop) {
            tap(GENRE_PREFIX + "track:pop")
            // Each row one item that names the tag marked, the chosen chip's wording; no row itself
            // selected, which would not say which tag is.
            assertEquals("Rock, selected", text(R.string.home_genre_tag_selected, "Rock"))
            assertEquals(
                listOf("Track, Pop, selected", "Artist, Rock, Pop, selected", "Album, Folk Rock, Pop, selected"),
                (0..2).map { compose.onNodeWithTag(GENRES_ROW_PREFIX + it).reading() },
            )
            for (index in 0..2) {
                val row = compose.onNodeWithTag(GENRES_ROW_PREFIX + index).fetchSemanticsNode().config
                assertEquals(null, row.getOrNull(SemanticsProperties.Selected))
            }
            for (other in listOf("track:pop", "artist:The Beatles:rock", "album:folkrock")) {
                val config =
                    compose
                        .onNodeWithTag(
                            GENRE_CELL_PREFIX + other,
                            useUnmergedTree = true,
                        ).fetchSemanticsNode()
                        .config
                assertEquals(null, config.getOrNull(SemanticsProperties.Selected))
                // The grid's labels are not buttons.
                assertEquals(null, config.getOrNull(SemanticsActions.OnClick))
            }
        }
    }

    @Test
    fun aScreenReaderReadsTheChosenChipAsSelectedWithFoldAsItsAction() {
        show(three) {
            val baroque = GENRE_PREFIX + "track:baroquepop"
            compose
                .onNodeWithTag(baroque)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertIsNotSelected()
            assertEquals(text(R.string.home_genre_show), clickLabel(baroque))
            tap(baroque)
            // "Baroque Pop, selected", "double-tap to fold".
            compose.onNodeWithTag(baroque).assertIsSelected().assertTextEquals("Baroque Pop")
            assertEquals("fold", text(R.string.home_genre_fold))
            assertEquals(text(R.string.home_genre_fold), clickLabel(baroque))
            assertEquals(text(R.string.home_genre_show), clickLabel(GENRE_PREFIX + "track:ballad"))
        }
    }

    // The track's folk from Last.fm; the artist's folk from MusicBrainz, and pop from both.
    private fun twoSources(): TrackGenres {
        val folk = label("folk", GenreLevel.ARTIST, "Gordon Lightfoot", LabelSource.MUSICBRAINZ)
        val both =
            TrackLabel(
                GenreLabel("pop", "family", "Pop", "pop", level = GenreLevel.ARTIST, artist = "Gordon Lightfoot"),
                listOf(
                    LabelCredit(LabelSource.LASTFM, "https://www.last.fm/music/Gordon+Lightfoot"),
                    LabelCredit(LabelSource.MUSICBRAINZ, "https://musicbrainz.org/artist/gl"),
                ),
            )
        return TrackGenres(
            listOf(row(GenreLevel.TRACK, "folk"), GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(folk, both))),
        )
    }

    @Test
    fun aRowOfSourcesIsOneItemWhoseLinksStayLinksAScreenReaderReachesAndOpens() =
        show(twoSources()) {
            tap(GENRE_PREFIX + "track:folk")
            // Each row of sources one item, its links still links of their own: in the merged tree,
            // one node per source under the row, each a screen reader can focus and open.
            val linked =
                compose
                    .onAllNodes(hasAnyAncestor(hasTestTag(GENRE_SOURCES_ROW_PREFIX + "1")))
                    .fetchSemanticsNodes()
                    .filter { SemanticsActions.OnClick in it.config }
            assertEquals(2, linked.size)
            assertTrue(linked.all { SemanticsActions.RequestFocus in it.config })
            assertEquals(
                1,
                compose
                    .onAllNodes(
                        hasAnyAncestor(hasTestTag(GENRE_SOURCES_ROW_PREFIX + "0")) and hasClickAction(),
                    ).fetchSemanticsNodes()
                    .size,
            )
        }

    @Test
    fun eachSourceLinksTheRowsPageAndMusicBrainzsCreditAndLicenceShowOnceUnderThem() =
        show(twoSources()) {
            tap(GENRE_PREFIX + "track:folk")
            assertEquals(listOf("Track, Last.fm", "Artist, Last.fm · MusicBrainz"), sources())
            assertEquals(listOf("https://example.org/folk"), links("Last.fm"))
            assertEquals(
                listOf("https://www.last.fm/music/Gordon+Lightfoot", "https://example.org/folk"),
                links("Last.fm · MusicBrainz"),
            )
            // Once, in the right column, under the sources: the credit, then the licence, linked.
            assertEquals("MusicBrainz tags by contributors", text(R.string.home_genre_tags_credit))
            assertEquals(listOf("MusicBrainz tags by contributors, CC BY-NC-SA 3.0"), tagged(GENRE_TAGS_CREDIT_TAG))
            assertEquals(listOf(LabelSource.MUSICBRAINZ.licenceUrl), links(text(R.string.home_genre_tags_licence)))
            assertTrue(bounds(GENRE_TAGS_CREDIT_TAG).top >= bounds(GENRE_SOURCES_ROW_PREFIX + "1").bottom)
            assertEquals(
                compose
                    .onNodeWithText(
                        "Last.fm · MusicBrainz",
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
                    .boundsInRoot.left,
                compose
                    .onNodeWithText(
                        text(R.string.home_genre_tags_credit),
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
                    .boundsInRoot.left,
            )
        }

    @Test
    fun withNoMusicBrainzTagsNoCreditOrLicence() {
        show(three) {
            tap(GENRE_PREFIX + "track:baroquepop")
            assertEquals(0, count(GENRE_TAGS_CREDIT_TAG))
            assertEquals(
                0,
                compose
                    .onAllNodesWithText(
                        text(R.string.home_genre_tags_licence),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .size,
            )
        }
    }

    @Test
    fun withSeveralArtistsTheRowsAreArtist1AndArtist2AndTheSourcesNameEachArtist() {
        val two = track(HomeFixtures.sundown.copy(artists = "The Beatles, Billy Preston"))
        val preston = row(GenreLevel.ARTIST, "soul", artist = "Billy Preston")
        show(TrackGenres(listOf(track, beatles, preston)), playing = two) {
            tap(GENRE_PREFIX + "track:baroquepop")
            assertEquals(listOf("Track, Baroque Pop, Ballad", "Artist 1, Rock, Pop", "Artist 2, Soul"), rows())
            assertEquals(
                listOf("Track, Last.fm", "Artist 1, The Beatles, Last.fm", "Artist 2, Billy Preston, Last.fm"),
                sources(),
            )
            // The artist's name above its sources.
            val name = compose.onNodeWithText("The Beatles", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val beatlesSources =
                compose
                    .onAllNodesWithText(
                        "Last.fm",
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()[1]
                    .boundsInRoot
            assertTrue(name.bottom <= beatlesSources.top)
        }
    }

    @Test
    fun anArtistsRowIsNumberedByItsPlaceInTheCreditNotAmongTheRowsWithTags() {
        // The first credited artist has no tags: the second's row is still *Artist 2*.
        val two = track(HomeFixtures.sundown.copy(artists = "The Beatles, Billy Preston"))
        val preston = row(GenreLevel.ARTIST, "soul", artist = "Billy Preston")
        show(TrackGenres(listOf(track, preston)), playing = two) {
            tap(GENRE_PREFIX + "track:baroquepop")
            assertEquals(listOf("Track, Baroque Pop, Ballad", "Artist 2, Soul"), rows())
            assertEquals(listOf("Track, Last.fm", "Artist 2, Billy Preston, Last.fm"), sources())
        }
    }

    @Test
    fun withOneLevelEachChipUnfoldsTheGenreWithARowPerArtist() {
        val second = row(GenreLevel.ARTIST, "rock", "jazz", artist = "Miles Davis")
        show(TrackGenres(listOf(beatles, second))) {
            // The artists' together, each genre once.
            assertEquals(listOf("Rock", "Pop", "Jazz"), chips(GenreLevel.ARTIST))
            tap(GENRE_PREFIX + "artist:The Beatles:rock")
            assertEquals(listOf("Artist, Rock, Pop", "Artist, Rock, Jazz"), rows())
            assertEquals("Artist, Rock, selected, Jazz", compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").reading())
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
    fun anOpenGenreOutlastsLabelsThatLandLaterAndANewTrackStartsFolded() {
        val genres = mutableStateOf(TrackGenres(listOf(beatles)))
        val playing = mutableStateOf(track())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { Column { GenresContent(playing.value.copy(genres = genres.value), Settings()) } }
            }
            scenario.onActivity {
                tap(GENRE_PREFIX + "artist:The Beatles:rock")
                // The album's labels land: the chips are now the album's, and the genre stays.
                genres.value = TrackGenres(listOf(beatles, album))
                compose.waitForIdle()
                assertEquals(listOf("Folk Rock", "Pop"), chips(GenreLevel.ALBUM))
                assertEquals("Artist, Rock, selected, Pop", compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading())
                assertEquals(listOf("Artist, Rock, Pop", "Album, Folk Rock, Pop"), rows())

                playing.value = track(HomeFixtures.carefree)
                compose.waitForIdle()
                assertEquals(0, panels())
            }
        }
    }
}
