package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.LyricsSite
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Genres of weak evidence (#875): every genre a chip, a weak one dimmed and in italics and read
 * "Country, weak evidence"; the panel opening on the first backed genre, else the first weak one,
 * never the AI's; a weak genre's line under its definition, naming its source and the levels
 * without it; the *Genre tags* table marking the genre open as for any other, and no genre weak.
 */
@RunWith(RobolectricTestRunner::class)
class GenreWeakScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private lateinit var colours: ColorScheme

    private fun label(
        tag: String,
        level: GenreLevel,
        artist: String? = null,
        source: LabelSource = LabelSource.LASTFM,
        votes: Int? = null,
    ): TrackLabel {
        val shown = tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val genre = GenreLabel(tag, "family", shown, tag.replace(" ", ""), level = level, artist = artist)
        return TrackLabel(genre, listOf(LabelCredit(source, "https://example.org/$tag")), votes)
    }

    private fun artist(
        name: String,
        vararg tags: String,
    ) = GenreRow(GenreLevel.ARTIST, name, tags.map { label(it, GenreLevel.ARTIST, name) })

    private fun album(vararg tags: String) = GenreRow(GenreLevel.ALBUM, null, tags.map { label(it, GenreLevel.ALBUM) })

    private fun trackRow(vararg labels: TrackLabel) = GenreRow(GenreLevel.TRACK, null, labels.toList())

    // A one-vote MusicBrainz tag on the track, and one only Last.fm gives it.
    private fun oneVote(tag: String) = label(tag, GenreLevel.TRACK, source = LabelSource.MUSICBRAINZ, votes = 1)

    private fun lastFm(tag: String) = label(tag, GenreLevel.TRACK)

    // The case reported in #875: a one-vote MusicBrainz Country on the track, and the artist's
    // Last.fm Acoustic, Folk and Indie Folk.
    private val reported =
        TrackGenres(listOf(trackRow(oneVote("country")), artist("A", "acoustic", "folk", "indie folk")))

    // A genre's notes with its definition as [definition] gives it (found, by default) and *Why is
    // this …?* offered.
    private fun notesWith(
        definition: (String) -> DefinitionState? = { genre ->
            DefinitionState.Found(GenreDefinition("What $genre is.", genre, "https://example.org/$genre"))
        },
    ) = object : GenreNotes {
        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(definition(genre))

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.Offered)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }

    private val notes = notesWith()

    // *Search with AI* offered for a definition, asking nothing.
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
        shown: PanelNotes = PanelNotes(),
        content: @Composable (TrackState) -> Unit = { GenresContent(it, Settings(), shown) },
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    colours = MaterialTheme.colorScheme
                    Column(Modifier.verticalScroll(rememberScrollState())) { content(track().copy(genres = genres)) }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    // The style [text] is drawn in, in the chip or the cell tagged [tag].
    private fun styleIn(
        tag: String,
        text: String,
    ): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)) or (hasText(text) and hasTestTag(tag)), true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().layoutInput.style
    }

    private fun description(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

    // The weak genre's line, as text, and the part of it in italics.
    private fun weakLine(): Pair<String, List<String>> {
        val text =
            compose
                .onNodeWithTag(GENRE_WEAK_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        val italic = text.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        return text.text to italic.map { text.text.substring(it.start, it.end) }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun aWeakChipIsDimmedAndItalicAndReadWithWeakEvidenceSelectedDimmedOnTheFill() =
        show(reported) {
            val country = styleIn(COUNTRY, "Country")
            assertEquals(FontStyle.Italic, country.fontStyle)
            assertEquals(FontWeight.Normal, country.fontWeight)
            assertEquals(colours.onSurfaceVariant, country.color)
            assertEquals("Country, weak evidence", app.getString(R.string.genre_weak, "Country"))
            assertEquals("Country, weak evidence", description(COUNTRY))
            // A backed chip: upright, in the text's colour, read by its name alone.
            val folk = GENRE_PREFIX + "artist:A:folk"
            assertNotEquals(FontStyle.Italic, styleIn(folk, "Folk").fontStyle)
            assertEquals(null, description(folk))

            tap(COUNTRY)
            compose.onNodeWithTag(COUNTRY).assertIsSelected()
            val selected = styleIn(COUNTRY, "Country")
            assertEquals(FontStyle.Italic, selected.fontStyle)
            assertEquals(colours.surfaceContainer.copy(alpha = 0.72f), selected.color)
            assertEquals("Country, weak evidence", description(COUNTRY))
        }

    @Test
    fun thePanelOpensOnTheFirstBackedGenreElseTheFirstWeakNeverTheAisAndWithNoChipNone() {
        show(reported) {
            compose.onNodeWithTag(GENRE_PREFIX + "artist:A:acoustic").assertIsSelected()
            compose.onNodeWithTag(COUNTRY).assertIsNotSelected()
            assertEquals(1, count(GENRE_PANEL_TAG))
        }
        // Every source's genre weak, and one the AI's list added: the first weak one.
        val weak =
            TrackGenres(listOf(trackRow(lastFm("soul"), oneVote("rock")))).withAi(
                listOf(GenreLabel("ambient", "family", "Ambient", "ambient", level = GenreLevel.TRACK)),
                "Claude Haiku 5.5",
            )
        show(weak) {
            compose.onNodeWithTag(GENRE_PREFIX + "track:rock").assertIsSelected()
            compose.onNodeWithTag(GENRE_PREFIX + "track:ambient").assertIsNotSelected()
        }
        show(TrackGenres()) { assertEquals(0, count(GENRE_PANEL_TAG)) }
    }

    @Test
    fun aWeakGenresLineIsUnderItsDefinitionsCreditAndAboveWhyWithTheGenreInItalics() =
        show(reported, PanelNotes(notes)) {
            // The backed genre the panel opens on has no line.
            assertEquals(0, count(GENRE_WEAK_TAG))
            tap(COUNTRY)
            assertEquals(
                "Few MusicBrainz contributors tagged this track Country, and the artist’s tags don’t include it." to
                    listOf("Country"),
                weakLine(),
            )
            assertTrue(bounds(GENRE_WEAK_TAG).top >= bounds(GENRE_DEFINITION_TAG).bottom)
            assertTrue(bounds(GENRE_WEAK_TAG).bottom <= bounds(GENRE_WHY_TAG).top)
        }

    @Test
    fun theLineIsUnderWhateverStandsInTheDefinitionsPlaceAndAboveWhy() {
        // Each state of the definition, with the element it ends with; with Wikipedia off, none.
        val states =
            listOf(
                DefinitionState.Looking to listOf(GENRE_DEFINITION_LOOKING_TAG),
                DefinitionState.Failed to listOf(GENRE_DEFINITION_FAILED_TAG),
                DefinitionState.NotFound to listOf(GENRE_NO_DEFINITION_TAG, AI_SEARCH_TAG),
                null to emptyList(),
            )
        for ((state, above) in states) {
            val shown = PanelNotes(notesWith { state })
            show(
                reported,
                content = { GenresContent(it, Settings(), shown, search = PanelSearch(feed, it, it.nowPlaying)) },
            ) {
                tap(COUNTRY)
                val line = bounds(GENRE_WEAK_TAG)
                for (tag in above) assertTrue("$state: under $tag", line.top >= bounds(tag).bottom)
                assertTrue("$state: above Why", line.bottom <= bounds(GENRE_WHY_TAG).top)
            }
        }
    }

    @Test
    fun theLineNamesItsSourceAndTheLevelsWithoutTheGenre() {
        val cases =
            listOf(
                TrackGenres(listOf(trackRow(lastFm("soul")), album("jazz"))) to
                    "Only Last.fm’s tags for this track give Soul, and the album’s tags don’t include it.",
                TrackGenres(listOf(trackRow(oneVote("soul")), artist("A", "folk"), album("jazz"))) to
                    "Few MusicBrainz contributors tagged this track Soul, and neither the artist’s nor the album’s " +
                    "tags include it.",
                TrackGenres(listOf(trackRow(lastFm("soul")), artist("A", "folk"), artist("B", "pop"))) to
                    "Only Last.fm’s tags for this track give Soul, and the artists’ tags don’t include it.",
                TrackGenres(
                    listOf(trackRow(oneVote("soul")), artist("A", "folk"), artist("B", "pop"), album("jazz")),
                ) to
                    "Few MusicBrainz contributors tagged this track Soul, and neither the artists’ nor the album’s " +
                    "tags include it.",
                TrackGenres(listOf(trackRow(lastFm("soul")))) to "Only Last.fm’s tags for this track give Soul.",
            )
        for ((genres, line) in cases) {
            show(genres) {
                if (genres
                        .opening()
                        ?.label
                        ?.genre
                        ?.identity != "soul"
                ) {
                    tap(GENRE_PREFIX + "track:soul")
                }
                assertEquals(line to listOf("Soul"), weakLine())
            }
        }
    }

    @Test
    fun theTableMarksTheWeakGenreOpenAsAnyGenreAndNoGenreWeak() =
        show(reported) {
            tap(COUNTRY)
            assertEquals("Track, Country, selected", compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading())
            // Marked as any genre open: the marked cell's colours, upright.
            val cell = styleIn(GENRE_CELL_PREFIX + "track:country", "Country")
            assertEquals(colours.surfaceContainer, cell.color)
            assertNotEquals(FontStyle.Italic, cell.fontStyle)
            tap(GENRE_PREFIX + "artist:A:folk")
            val unmarked = styleIn(GENRE_CELL_PREFIX + "track:country", "Country")
            assertEquals(colours.onSurface, unmarked.color)
            assertNotEquals(FontStyle.Italic, unmarked.fontStyle)
        }

    @Test
    fun theGenresPageShowsEveryGenreInTheChipsOrderTheWeakOneWithItsLine() =
        show(reported, content = { GenresPage(it, Settings()) }) {
            val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            val headings =
                compose
                    .onAllNodes(heading and hasTestTag(GENRE_PANEL_HEADING_TAG))
                    .fetchSemanticsNodes()
                    .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
            assertEquals(listOf("Acoustic", "Country", "Folk", "Indie Folk"), headings)
            assertEquals(1, count(GENRE_WEAK_TAG))
            assertEquals(
                "Few MusicBrainz contributors tagged this track Country, and the artist’s tags don’t include it." to
                    listOf("Country"),
                weakLine(),
            )
        }

    @Test
    fun instrumentalsLineNamesTheSignThatTheTrackIsSung() {
        // Artist A tagged Instrumental and Funk; B credited too, with no tags. The featured artist's
        // line names no genre, so it has no italics.
        val tagged = listOf(artist("A", "instrumental", "funk"))
        val cases =
            listOf(
                Singing(vocalists = listOf("Yurika Hanashima"), languages = listOf("jpn")) to
                    (
                        "MusicBrainz credits Yurika Hanashima with vocals on this track, " +
                            "most likely it is not Instrumental." to listOf("Instrumental")
                    ),
                Singing(languages = listOf("jpn")) to
                    (
                        "MusicBrainz lists this song’s lyrics as Japanese, so it is likely not Instrumental." to
                            listOf("Instrumental")
                    ),
                Singing(languages = listOf("mul")) to
                    (
                        "MusicBrainz lists lyrics for this song, so it is likely not Instrumental." to
                            listOf("Instrumental")
                    ),
                Singing.NONE to ("This track also credits B and may feature vocals." to emptyList()),
            )
        for ((singing, line) in cases) {
            show(TrackGenres(tagged, credited = listOf("A", "B"), singing = singing)) {
                tap(INSTRUMENTAL)
                assertEquals(line, weakLine())
            }
        }
    }

    @Test
    fun lyricsFindTheLyricsFoundDropInstrumentalFromTheChips() {
        val found =
            AiSearch.Answered(
                SearchAnswer.Lyrics(listOf(LyricsLink(LyricsSite.GENIUS, "https://genius.com/A-song-lyrics"))),
                "Model",
            )
        val lyrics =
            object : AiSearchFeed by feed {
                override fun search(
                    track: TrackState,
                    kind: SearchKind,
                    genre: String?,
                ): Flow<AiSearch> = flowOf(if (kind == SearchKind.LYRICS) found else AiSearch.None)
            }
        val genres = TrackGenres(listOf(artist("A", "instrumental", "funk")), credited = listOf("A"))
        show(genres, content = { GenresContent(it, Settings(), search = PanelSearch(lyrics, it, it.nowPlaying)) }) {
            assertEquals(0, count(INSTRUMENTAL))
            assertEquals(1, count(GENRE_PREFIX + "artist:A:funk"))
        }
        // Without the answer, the chip is there.
        show(genres, content = { GenresContent(it, Settings(), search = PanelSearch(feed, it, it.nowPlaying)) }) {
            assertEquals(1, count(INSTRUMENTAL))
        }
    }

    private companion object {
        const val COUNTRY = GENRE_PREFIX + "track:country"
        const val INSTRUMENTAL = GENRE_PREFIX + "artist:A:instrumental"
    }
}
