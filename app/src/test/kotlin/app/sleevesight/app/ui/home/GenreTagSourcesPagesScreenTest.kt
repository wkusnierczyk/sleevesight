package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The pages behind the AI's genres in *Tag sources* (#1205), as on *Way down We Go*: the row the AI's
 * genres from the web joined names the model, then "From:" and a citation of each page they rest on,
 * as *Sources* numbers it, spaces between, each a link to its line there, read "Track: Claude Haiku
 * 5.5, AI. From: Source S7, …" with each an action; a row whose AI genres rest on no page names the
 * model alone. *Sources* lists the pages after its own web lines, each named *genres*.
 */
@RunWith(RobolectricTestRunner::class)
class GenreTagSourcesPagesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-13T00:00:00Z")

    private fun label(
        tag: String,
        shown: String,
        level: GenreLevel,
    ) = GenreLabel(
        tag,
        shown,
        tag.filter(Char::isLetter),
        level = level,
        artist = KALEO.takeIf { level == GenreLevel.ARTIST },
    )

    // KALEO's tags, from MusicBrainz: Blues Rock and Rock.
    private val playing: TrackState =
        track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            KALEO,
                            listOf("blues rock" to "Blues Rock", "rock" to "Rock").map { (tag, shown) ->
                                TrackLabel(
                                    label(tag, shown, GenreLevel.ARTIST),
                                    listOf(LabelCredit(LabelSource.MUSICBRAINZ, "https://musicbrainz.org/artist/1")),
                                )
                            },
                        ),
                    ),
                ),
        )

    // The five pages the AI's search read, as *Genres* lists them under the chips.
    private val pages =
        listOf(
            "https://en.wikipedia.org/wiki/Way_Down_We_Go",
            "https://www.albumoftheyear.org/album/kaleo-ab",
            "https://lyricstories.com/way-down-we-go",
            "https://nl.wikipedia.org/wiki/Way_Down_We_Go",
            "https://songs.klang.io/kaleo/way-down-we-go",
        ).map { WebPage(it, null, "A page on $it.") }

    // The track's six web lines, S1 to S6, as *Sources* shows them, the genres' pages after them.
    private val sources =
        withPages(
            SourcesLines(
                EvidenceSet.of(
                    (1..6).map {
                        EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it.", "https://example.org/$it", at)
                    },
                    JvmHasher,
                ),
            ),
            pages,
            LinePurpose.GENRES,
        )

    private val folkRock = label("folk rock", "Folk Rock", GenreLevel.TRACK)
    private val popRock = label("pop rock", "Pop Rock", GenreLevel.TRACK)

    private val cited = mutableListOf<String>()

    private class Notes(
        private val list: AiGenreList,
    ) : GenreNotes {
        override fun definition(genre: String): Flow<DefinitionState?> = flowOf(null)

        override fun aiGenres(track: TrackState): Flow<AiGenreList?> = flowOf(list)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.Offered)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }

    private fun show(
        list: AiGenreList,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            playing,
                            Settings(),
                            notes =
                                PanelNotes(Notes(list), onCite = { line -> cited += line }, lines = sources.evidence),
                        )
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkrock").unfold()
            compose.waitForIdle()
            block()
        }
    }

    private fun froms() =
        compose.onAllNodesWithTag(GENRE_SOURCES_FROM_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun textOf(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    @Test
    fun theRowTheAisGenresFromTheWebJoinedCitesTheirPagesInSources() =
        show(AiGenreList(listOf(folkRock, popRock), MODEL, pages.map { it.url }, pages)) {
            // The track's row (0) names the model, then the pages' citations, S7 to S11, spaces between.
            compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0").performScrollTo()
            compose.onNodeWithText("$MODEL, AI", useUnmergedTree = true).assertExists()
            assertEquals("From: S7 S8 S9 S10 S11", textOf(GENRE_SOURCES_FROM_TAG))
            assertEquals(1, froms())
            compose
                .onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0")
                .assertContentDescriptionEquals(
                    "Track: $MODEL, AI. From: Source S7, Source S8, Source S9, Source S10, Source S11",
                )
            // Each citation a link to its line in *Sources*, and an action too.
            compose.onNodeWithTag(GENRE_SOURCES_FROM_TAG, useUnmergedTree = true).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_CITE_PREFIX + "S9"
            }
            compose.waitForIdle()
            assertEquals(listOf("S9"), cited)
            val actions =
                compose
                    .onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
            assertEquals((7..11).map { "Source S$it" }, actions.map { it.label })
            actions.last().action()
            assertEquals(listOf("S9", "S11"), cited)
        }

    @Test
    fun aRowWhoseAiGenresRestOnNoPageNamesTheModelAlone() =
        show(AiGenreList(listOf(folkRock, popRock), MODEL)) {
            compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0").performScrollTo()
            compose.onNodeWithText("$MODEL, AI", useUnmergedTree = true).assertExists()
            assertEquals(0, froms())
            val row = compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0").fetchSemanticsNode().config
            assertNull(row.getOrNull(SemanticsActions.CustomActions))
        }

    @Test
    fun sourcesListsThePagesAfterItsOwnWebLinesNamedGenres() {
        val lines = sources.evidence!!.lines
        assertEquals((1..11).map { "S$it" }, lines.map { it.id })
        assertEquals(pages.map { it.url }, lines.drop(6).map { it.url })
        assertEquals((7..11).associate { "S$it" to LinePurpose.GENRES }, sources.purposes)
        // A page a web line has already is that line, kept as it is named, not listed twice.
        val page = WebPage("https://example.org/2/", null, "Again.")
        val again = withPages(sources, listOf(page) + pages, LinePurpose.GENRES)
        assertEquals(sources.evidence, again.evidence)
        assertEquals("S2", webLineOf("https://example.org/2", again.evidence))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column {
                        for (line in lines.takeLast(2)) Text(sources.what(line))
                        Text(SourcesLines(sources.evidence).what(lines.first()))
                    }
                }
            }
            compose.waitForIdle()
            assertEquals(2, compose.onAllNodes(hasText("genres")).fetchSemanticsNodes().size)
            compose.onNodeWithText("web").assertExists()
        }
    }

    private companion object {
        const val KALEO = "KALEO"
        const val MODEL = "Claude Haiku 5.5"
    }
}
