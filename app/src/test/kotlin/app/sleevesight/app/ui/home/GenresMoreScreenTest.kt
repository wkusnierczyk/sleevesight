package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The genres past each row's five (#1281): under *Genres*' chips, *Show more*, read with their count
 * and as collapsed, shows them as chips under "Weaker evidence", each once and none a chip already;
 * *Show less* under them folds them, and an extra genre open with them; a new track starts folded;
 * a row with exactly five has no pill; an extra genre open shows each row's extras after its five in
 * *Genre tags*, outlined, read after "weaker evidence:"; what a prompt reads keeps the five.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GenresMoreScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun label(
        tag: String,
        level: GenreLevel,
        source: LabelSource = LabelSource.LASTFM,
        artist: String? = null,
    ): TrackLabel {
        val shown = tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val genre = GenreLabel(tag, shown, tag.replace(" ", ""), level = level, artist = artist)
        // Backed (#875), so no chip is weak.
        val votes = TrackGenres.MIN_VOTES.takeIf { level == GenreLevel.TRACK }
        return TrackLabel(genre, listOf(LabelCredit(source, "https://example.org/$tag")), votes)
    }

    private val five = listOf("alternative rock", "art rock", "progressive rock", "rock", "experimental")

    // The track's five, then Electronic, Britpop and the MusicBrainz-only Psychedelic Rock past them;
    // the artist's five, Electronic among them, then Britpop and Trip Hop.
    private val trackRow =
        GenreRow(
            GenreLevel.TRACK,
            null,
            five.map { label(it, GenreLevel.TRACK) },
            extras =
                listOf(
                    label("electronic", GenreLevel.TRACK),
                    label("britpop", GenreLevel.TRACK),
                    label("psychedelic rock", GenreLevel.TRACK, LabelSource.MUSICBRAINZ),
                ),
        )

    private val artistRow =
        GenreRow(
            GenreLevel.ARTIST,
            "Radiohead",
            listOf("alternative rock", "art rock", "electronic", "rock", "experimental").map {
                label(it, GenreLevel.ARTIST, artist = "Radiohead")
            },
            extras = listOf("britpop", "trip hop").map { label(it, GenreLevel.ARTIST, artist = "Radiohead") },
        )

    private val rich = TrackGenres(listOf(trackRow, artistRow), credited = listOf("Radiohead"))

    // Every row with five or fewer: nothing past them.
    private val plain = TrackGenres(listOf(trackRow.copy(extras = emptyList()), artistRow.copy(extras = emptyList())))

    private fun show(
        genres: TrackGenres,
        playing: MutableState<TrackState> = mutableStateOf(track().copy(genres = genres)),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { GenresContent(playing.value, Settings()) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.config() = fetchSemanticsNode().config

    private fun chip(
        row: GenreRow,
        tag: String,
    ) = tagOf(GENRE_PREFIX, PlacedLabel(row, (row.labels + row.extras).single { it.genre.identity == tag }))

    private fun cell(
        row: GenreRow,
        tag: String,
    ) = tagOf(GENRE_CELL_PREFIX, PlacedLabel(row, (row.labels + row.extras).single { it.genre.identity == tag }))

    @Test
    fun aRowWithExactlyFiveHasNoPill() =
        show(plain) {
            assertFalse(exists(GENRES_MORE_TAG))
            assertFalse(exists(GENRES_WEAKER_TAG))
        }

    @Test
    fun showMoreIsReadWithItsCountAndCollapsedAndExpandsOnADoubleTap() =
        show(rich) {
            val more = compose.onNodeWithTag(GENRES_MORE_TAG).config()
            // Britpop, Psychedelic Rock and Trip Hop: Electronic is a chip already, Britpop once.
            assertEquals(
                "Show more, 3 genres with weaker evidence",
                more.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
            )
            assertEquals("collapsed", more.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(Role.Button, more.getOrNull(SemanticsProperties.Role))
            assertEquals("expand", more[SemanticsActions.OnClick].label)
            // Folded: no extra chip yet.
            assertFalse(exists(chip(trackRow, "britpop")))
        }

    @Test
    fun showMoreShowsTheExtrasOnceUnderWeakerEvidenceThenShowLess() =
        show(rich) {
            tap(GENRES_MORE_TAG)
            assertFalse(exists(GENRES_MORE_TAG))
            assertTrue(node(GENRES_WEAKER_TAG).config().contains(SemanticsProperties.Heading))
            assertEquals("Weaker evidence", node(GENRES_WEAKER_TAG).config()[SemanticsProperties.Text].joinToString())
            // Each once, placed on the first row whose extras have it; none a chip already.
            assertTrue(exists(chip(trackRow, "britpop")))
            assertTrue(exists(chip(trackRow, "psychedelicrock")))
            assertTrue(exists(chip(artistRow, "triphop")))
            assertFalse(exists(chip(artistRow, "britpop")))
            assertFalse(exists(chip(trackRow, "electronic")))
            val less = compose.onNodeWithTag(GENRES_LESS_TAG).config()
            assertEquals("Show less", less[SemanticsProperties.Text].joinToString())
            assertFalse(less.contains(SemanticsProperties.ContentDescription))
            assertEquals("expanded", less.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(Role.Button, less.getOrNull(SemanticsProperties.Role))
            assertEquals("collapse", less[SemanticsActions.OnClick].label)
            // Under the extras.
            assertTrue(
                node(GENRES_LESS_TAG).fetchSemanticsNode().boundsInRoot.top >=
                    node(chip(artistRow, "triphop")).fetchSemanticsNode().boundsInRoot.bottom,
            )
            tap(GENRES_LESS_TAG)
            assertTrue(exists(GENRES_MORE_TAG))
            assertFalse(exists(GENRES_WEAKER_TAG))
            assertFalse(exists(chip(trackRow, "britpop")))
        }

    @Test
    fun anExtraOpensAsTheOthersDoAndEachRowShowsItsExtrasOutlinedAfterItsFive() =
        show(rich) {
            tap(GENRES_MORE_TAG)
            tap(chip(trackRow, "britpop"))
            assertTrue(exists(GENRE_PANEL_TAG))
            // Each row's extras after its five, read after "weaker evidence:", the genre open selected.
            assertEquals(
                "Track, Alternative Rock, Art Rock, Experimental, Progressive Rock, Rock; " +
                    "weaker evidence: Electronic, Britpop, selected, Psychedelic Rock",
                node(GENRES_ROW_PREFIX + 0).config()[SemanticsProperties.ContentDescription].joinToString(),
            )
            assertEquals(
                "Artist, Alternative Rock, Art Rock, Electronic, Experimental, Rock; " +
                    "weaker evidence: Britpop, selected, Trip Hop",
                node(GENRES_ROW_PREFIX + 1).config()[SemanticsProperties.ContentDescription].joinToString(),
            )
            assertTrue(exists(cell(trackRow, "electronic")))
            assertTrue(exists(cell(artistRow, "triphop")))
            // Tag sources count them: MusicBrainz's credit for the extra only it gives.
            assertTrue(exists(GENRE_TAGS_CREDIT_TAG))
            // The cells past the five follow them, on the row's lines.
            val fifth = node(cell(trackRow, "rock")).fetchSemanticsNode().boundsInRoot
            val extra = node(cell(trackRow, "psychedelicrock")).fetchSemanticsNode().boundsInRoot
            assertTrue(extra.top > fifth.top || extra.left > fifth.left)
            // Folded with the extras, the extra genre open folds too, and the focus goes to the pill.
            tap(GENRES_LESS_TAG)
            assertFalse(exists(GENRE_PANEL_TAG))
            assertTrue(node(GENRES_MORE_TAG).config()[SemanticsProperties.Focused])
        }

    @Test
    fun withTheExtrasFoldedTheGenreTagsRowsKeepTheirFive() =
        show(rich) {
            assertEquals(
                "Track, Alternative Rock, selected, Art Rock, Experimental, Progressive Rock, Rock",
                node(GENRES_ROW_PREFIX + 0).config()[SemanticsProperties.ContentDescription].joinToString(),
            )
            assertFalse(exists(cell(trackRow, "britpop")))
            assertFalse(exists(GENRE_TAGS_CREDIT_TAG))
        }

    @Test
    fun aNewTrackStartsFolded() {
        val playing = mutableStateOf(track().copy(genres = rich))
        show(rich, playing) {
            tap(GENRES_MORE_TAG)
            assertTrue(exists(GENRES_LESS_TAG))
            compose.runOnUiThread {
                val next = playing.value.nowPlaying.copy(title = "Karma Police")
                playing.value = track(playing = next).copy(genres = rich)
            }
            compose.waitForIdle()
            assertTrue(exists(GENRES_MORE_TAG))
            assertFalse(exists(GENRES_LESS_TAG))
            assertFalse(exists(chip(trackRow, "britpop")))
        }
    }

    @Test
    fun whatAPromptReadsKeepsTheFiveAndAnMbOnlyExtraNeverReachesOne() {
        val playing = track().copy(genres = rich)
        val without = track().copy(genres = TrackGenres(rich.rows.map { it.copy(extras = emptyList()) }))
        // *Search with AI*'s genres, sent with the question: the same with the extras as without.
        assertEquals(searchFacts(without, without.nowPlaying), searchFacts(playing, playing.nowPlaying))
        // *Why is this …?* for an extra a source other than MusicBrainz gives; never for a MusicBrainz-only one.
        assertTrue(GenreReasons.prompted(playing, "britpop"))
        assertFalse(GenreReasons.prompted(playing, "psychedelicrock"))
        assertFalse(GenreReasons.prompted(without, "britpop"))
    }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontThePillsFitAndTheExtrasWrap() =
        show(rich) {
            val width =
                compose
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.width
            val more = node(GENRES_MORE_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(more.right <= width)
            tap(GENRES_MORE_TAG)
            for (tag in listOf(chip(trackRow, "psychedelicrock"), chip(artistRow, "triphop"), GENRES_LESS_TAG)) {
                assertTrue(tag, node(tag).fetchSemanticsNode().boundsInRoot.right <= width)
            }
        }
}
