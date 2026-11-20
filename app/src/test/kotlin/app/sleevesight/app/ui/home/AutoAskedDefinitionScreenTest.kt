package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What *Ask AI automatically* asked for a genre's definition says so (#956), as in the panels: "; asked
 * automatically." at the end of the credit or the nothing line, a link to the setting, which a screen
 * reader names "open the Ask AI automatically setting". Asked by a tap, the lines as before.
 */
@RunWith(RobolectricTestRunner::class)
class AutoAskedDefinitionScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val model = "Claude Haiku 5.5"

    private val jazz =
        TrackLabel(
            GenreLabel("jazz", "Jazz", "jazz", level = GenreLevel.ARTIST, artist = "Miles Davis"),
            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/jazz")),
        )

    private val playing: TrackState =
        track().copy(genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(jazz)))))

    // No definition from Wikipedia, so the definition is asked for.
    private val notes =
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

    private val state = MutableStateFlow<AiSearch>(AiSearch.Offered)

    private val feed =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = if (kind == SearchKind.DEFINITION) state else flowOf(AiSearch.None)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean = true

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    private val opened = mutableListOf<SettingsTarget>()

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(
                            playing,
                            Settings(),
                            notes = PanelNotes(notes),
                            search = PanelSearch(feed, playing, playing.nowPlaying) { target -> opened += target },
                        )
                    }
                }
            }
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Miles Davis:jazz").unfold()
            compose.waitForIdle()
            block()
        }
    }

    private fun set(search: AiSearch) {
        state.value = search
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun aDefinitionAskedAutomaticallySaysSoAtTheCreditsEndAndByATapDoesNot() =
        show {
            set(AiSearch.Answered(SearchAnswer.Definition("Jazz is a genre."), model, SearchWay(automatic = true)))
            compose
                .onNodeWithTag(AI_SEARCH_BY_TAG)
                .assertTextEquals("Written by Claude Haiku 5.5; asked automatically.")
            set(AiSearch.Answered(SearchAnswer.Definition("Jazz is a genre."), model))
            compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("Written by Claude Haiku 5.5.")
            assertEquals(0, count(AUTO_ASKED_LINK_TAG))
        }

    @Test
    fun nothingAskedAutomaticallyEndsWithTheNoteAndByATapAsBefore() =
        show {
            val known = "Claude Haiku 5.5 couldn’t say from its own knowledge"
            val web = "Claude Haiku 5.5 found nothing on the web"
            for ((way, line) in listOf(
                SearchWay(automatic = true) to "$known; asked automatically.",
                SearchWay(automatic = true, web = true) to "$web; asked automatically.",
                SearchWay() to "$known.",
                SearchWay(web = true) to "$web.",
            )) {
                set(AiSearch.NothingFound(model, way))
                compose.onNodeWithTag(AI_SEARCH_NOTHING_TAG).assertTextEquals(line)
                assertEquals("$way", if (way.automatic) 1 else 0, count(AUTO_ASKED_LINK_TAG))
            }
        }

    @Test
    fun theLinkIsNamedWithWhereItGoesAndOpensTheSetting() =
        show {
            set(AiSearch.NothingFound(model, SearchWay(automatic = true)))
            val link = compose.onNodeWithTag(AUTO_ASKED_LINK_TAG)
            link.assertTextEquals("asked automatically")
            val click = link.fetchSemanticsNode().config[SemanticsActions.OnClick]
            assertEquals("open the Ask AI automatically setting", click.label)
            link.performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.searchWithAi), opened)
        }
}
