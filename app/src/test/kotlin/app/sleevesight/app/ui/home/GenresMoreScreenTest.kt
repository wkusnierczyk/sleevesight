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
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.genresWithNoDefinition
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    // No definition from Wikipedia for any genre, and *Search with AI* offered for one, asking nothing.
    private val noDefinitions =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> = flowOf(DefinitionState.NotFound)

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    private val feed =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = flowOf(if (kind == SearchKind.DEFINITION) AiSearch.Offered else AiSearch.None)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ) = false

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    private fun show(
        genres: TrackGenres,
        playing: MutableState<TrackState> = mutableStateOf(track().copy(genres = genres)),
        searching: Boolean = false,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val now = playing.value
                        if (searching) {
                            GenresContent(
                                now,
                                Settings(),
                                PanelNotes(noDefinitions),
                                search = PanelSearch(feed, now, now.nowPlaying),
                            )
                        } else {
                            GenresContent(now, Settings())
                        }
                    }
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

    @Test
    fun noDefinitionSearchWithAiForAGenreOnlyMusicBrainzGivesOfTheFiveOrPastThem() {
        // The track's Rock only MusicBrainz gives, among the five; Psychedelic Rock past them.
        val mbRock = label("rock", GenreLevel.TRACK, LabelSource.MUSICBRAINZ)
        val row = trackRow.copy(labels = trackRow.labels.map { if (it.genre.identity == "rock") mbRock else it })
        val genres =
            TrackGenres(
                listOf(
                    row,
                    artistRow.copy(
                        labels =
                            artistRow.labels.filter {
                                it.genre.identity !=
                                    "rock"
                            },
                    ),
                ),
            )
        show(genres, searching = true) {
            tap(GENRES_MORE_TAG)
            // A Last.fm extra: offered.
            tap(chip(row, "britpop"))
            assertTrue(exists(AI_SEARCH_TAG))
            // MusicBrainz's alone: none, of the five or past them.
            tap(chip(row, "psychedelicrock"))
            assertTrue(exists(GENRE_NO_DEFINITION_TAG))
            assertFalse(exists(AI_SEARCH_TAG))
            tap(chip(row, "rock"))
            assertTrue(exists(GENRE_NO_DEFINITION_TAG))
            assertFalse(exists(AI_SEARCH_TAG))
        }
        // Nor searched by itself: only the genres that may reach a prompt.
        val playing = track().copy(genres = genres)
        assertEquals(
            setOf("alternativerock", "artrock", "progressiverock", "experimental", "electronic"),
            genresWithNoDefinition(playing) { _, _ -> true },
        )
    }

    @Test
    fun whenALaterRowTakesTheLastExtraTheExtrasFoldAndTheRowsShowNone() {
        // Britpop alone past the cap, on the track's row; then the album's labels come with it.
        val one = trackRow.copy(extras = trackRow.extras.filter { it.genre.identity == "britpop" })
        val before = TrackGenres(listOf(one))
        val album = GenreRow(GenreLevel.ALBUM, null, listOf(label("britpop", GenreLevel.ALBUM)))
        val playing = mutableStateOf(track().copy(genres = before))
        show(before, playing) {
            tap(GENRES_MORE_TAG)
            assertTrue(exists(cell(one, "britpop")))
            compose.runOnUiThread { playing.value = playing.value.copy(genres = TrackGenres(listOf(one, album))) }
            compose.waitForIdle()
            assertFalse(exists(GENRES_LESS_TAG))
            assertFalse(exists(GENRES_WEAKER_TAG))
            assertFalse(exists(GENRES_MORE_TAG))
            // Britpop a chip of the five now; the track's row shows its five alone.
            assertFalse(exists(cell(one, "britpop")))
            // A later extra starts folded.
            val next = one.copy(extras = one.extras + label("trip hop", GenreLevel.TRACK))
            compose.runOnUiThread { playing.value = playing.value.copy(genres = TrackGenres(listOf(next, album))) }
            compose.waitForIdle()
            assertTrue(exists(GENRES_MORE_TAG))
            assertFalse(exists(GENRES_LESS_TAG))
        }
    }

    @Test
    fun anExtraTheAisListGivesIsCreditedToTheModelAndMayReachAPromptAsItsGenreAlone() {
        val psychedelic =
            GenreLabel("psychedelic rock", "Psychedelic Rock", "psychedelicrock", level = GenreLevel.TRACK)
        val listed = rich.withAi(listOf(psychedelic), "Model")
        // The track's row is full: the genre stays past its cap, credited to the model there.
        assertEquals(
            "Model",
            listed.rows
                .first()
                .extras
                .single { it.genre.identity == "psychedelicrock" }
                .aiModel,
        )
        assertEquals(
            rich.rows.map {
                it.labels.map { l ->
                    l.genre.identity
                }
            },
            listed.rows.map { it.labels.map { l -> l.genre.identity } },
        )
        val before = track().copy(genres = rich)
        val after = track().copy(genres = listed)
        assertFalse(GenreReasons.prompted(before, "psychedelicrock"))
        assertTrue(GenreReasons.prompted(after, "psychedelicrock"))
        // What *Search with AI* lists stays the five.
        assertEquals(searchFacts(before, before.nowPlaying), searchFacts(after, after.nowPlaying))
    }

    @Test
    fun whileTheSourcesAreAskedAgainTheExtrasShownStayShown() {
        val playing = mutableStateOf(track().copy(genres = rich))
        show(rich, playing) {
            tap(GENRES_MORE_TAG)
            // *Retry* asks a source again: its genres gone meanwhile, no extra left.
            val asking = playing.value.sources + (SourceId.MUSICBRAINZ to SourceState.Pending)
            compose.runOnUiThread { playing.value = playing.value.copy(sources = asking, genres = plain) }
            compose.waitForIdle()
            assertFalse(exists(GENRES_LESS_TAG))
            // Back with them: shown as before, not folded.
            compose.runOnUiThread { playing.value = track().copy(genres = rich) }
            compose.waitForIdle()
            assertTrue(exists(GENRES_LESS_TAG))
            assertTrue(exists(chip(trackRow, "britpop")))
        }
    }
}
