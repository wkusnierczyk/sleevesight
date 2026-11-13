package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertTrue
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

    // The track's tag from Last.fm, Blues, linked; KALEO's, from MusicBrainz: Blues Rock and Rock.
    private val playing: TrackState =
        track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.TRACK,
                            null,
                            listOf(
                                TrackLabel(
                                    label("blues", "Blues", GenreLevel.TRACK),
                                    listOf(LabelCredit(LabelSource.LASTFM, LASTFM_PAGE)),
                                    votes = TrackGenres.MIN_VOTES,
                                ),
                            ),
                        ),
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
    private val opened = mutableListOf<String>()

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
                    val uris =
                        object : UriHandler {
                            override fun openUri(uri: String) {
                                opened += uri
                            }
                        }
                    CompositionLocalProvider(LocalUriHandler provides uris) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            GenresContent(
                                playing,
                                Settings(),
                                notes =
                                    PanelNotes(Notes(list), onCite = { line ->
                                        cited += line
                                    }, lines = sources.evidence),
                            )
                        }
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

    @Test
    fun theRowTheAisGenresFromTheWebJoinedCitesTheirPagesInSources() =
        show(AiGenreList(listOf(folkRock, popRock), MODEL, pages.map { it.url }, pages)) {
            // The track's row (0): its sources, then the pages' citations, S7 to S11, spaces between.
            val row = GENRE_SOURCES_ROW_PREFIX + "0"
            compose.onNodeWithTag(row).performScrollTo()
            compose
                .onNodeWithTag(row)
                .assertContentDescriptionEquals(
                    "Track: Last.fm · $MODEL, AI. From: Source S7, Source S8, Source S9, Source S10, Source S11",
                )
            // One item in place of its parts, which it clears from the accessibility tree, so nothing
            // under it is read again; the unmerged tree still holds them, as drawn.
            val node = compose.onNodeWithTag(row, useUnmergedTree = true).fetchSemanticsNode().config
            assertTrue(node.isClearingSemantics)
            assertNull(node.getOrNull(SemanticsProperties.Text))
            assertEquals(
                listOf("From: S7 S8 S9 S10 S11"),
                compose
                    .onNodeWithTag(GENRE_SOURCES_FROM_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .map { it.text },
            )
            // Each citation an action that opens its line in *Sources*; each link one that opens its page.
            val actions = node[SemanticsActions.CustomActions]
            assertEquals((7..11).map { "Source S$it" } + "Open Last.fm in your browser", actions.map { it.label })
            actions[2].action()
            actions.last().action()
            assertEquals(listOf("S9"), cited)
            assertEquals(listOf(LASTFM_PAGE), opened)
        }

    @Test
    fun aRowWhoseAiGenresRestOnNoPageNamesTheModelAlone() =
        show(AiGenreList(listOf(folkRock, popRock), MODEL)) {
            compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "0").performScrollTo()
            compose.onNodeWithText("Last.fm · $MODEL,", substring = true, useUnmergedTree = true).assertExists()
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
        const val LASTFM_PAGE = "https://www.last.fm/music/KALEO/_/Way+down+We+Go"
    }
}
