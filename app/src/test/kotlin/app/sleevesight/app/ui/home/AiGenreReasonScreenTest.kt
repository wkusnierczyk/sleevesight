package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
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
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.speech.FakeFocus
import app.sleevesight.app.speech.FakeSpeech
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.SearchReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A genre *Ask AI* names says why (#952): unfolded, its chip shows the definition, then *Why is this
 * …?* as a heading, the reason cited (the page's chip from the web, the **AI** chip from the model's
 * knowledge, with no line saying so), and *Read aloud* alone at the right under it, which reads the
 * question, then the reason; the answer's credit once, under the chips. A genre the answer gives no
 * reason for has no chip. The answers are read from replies as the app reads them ([AiSearchAnswers]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class AiGenreReasonScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val model = "Claude Haiku 5.5"

    private val feed =
        object : AiSearchFeed {
            // The spelling check not offered (#1293), so *Ask AI* shows.
            val states =
                SearchKind.entries.associateWith {
                    MutableStateFlow(if (it == SearchKind.SPELLING) AiSearch.None else AiSearch.Offered)
                }

            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = states.getValue(kind)

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

    private val definition = "Electronic music broadly is a group of music genres that employ electronic instruments."

    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> =
                flowOf(
                    DefinitionState.Found(
                        GenreDefinition(definition, "Electronic music", "https://en.wikipedia.org/wiki/$genre"),
                    ),
                )

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    private val speech = FakeSpeech()
    private val readAloud =
        ReadAloud(
            speech,
            FakeFocus(),
            MutableStateFlow(Settings()),
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Main.immediate),
        ) { Locale.UK }

    private val actions =
        NowPlayingActions(normalizer::normalize, genreNotes = notes, aiSearches = feed, readAloud = readAloud)

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val answers = AiSearchAnswers(vocabulary, JvmAccentFolder)
    private val electronic = vocabulary.labelsFor(listOf("electronic"), level = GenreLevel.TRACK).single()
    private val chip = GENRE_PREFIX + "track:" + electronic.identity
    private val discogs = "https://www.discogs.com/release/1"

    // No date and no genre from the sources.
    private val empty =
        track(sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None), SourceId.LASTFM to done()))
    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
    private val current =
        mutableStateOf(
            NowPlayingState(PipelineView(empty, aiReady = true), empty.nowPlaying, playback, settings = Settings()),
        )

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    // *Genres* opened, *Ask AI* answered with [reply] as the app reads it ([pages] from the web), and
    // Electronic's chip tapped.
    private fun answered(
        reply: String,
        pages: List<String>? = null,
    ): SearchAnswer.Genres {
        tap(GENRES_CHIP_TAG)
        val reading = answers.read(SearchKind.GENRES, reply, emptyList(), pages) as SearchReading.Found
        val answer = reading.answer as SearchAnswer.Genres
        val way = SearchWay(web = pages != null, cost = QuestionCost(if (pages != null) 1 else 0, 1))
        feed.states.getValue(SearchKind.GENRES).value = AiSearch.Answered(answer, model, way)
        compose.waitForIdle()
        tap(chip)
        return answer
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun topOf(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun said(tag: String): String? =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")

    private val knowledge =
        "{\"genres\": [{\"name\": \"electronic\", \"reason\": " +
            "\"Built from synthesisers and drum machines. [AI] Its beat is programmed. [AI]\"}, " +
            "{\"name\": \"downtempo\"}]}"

    @Test
    fun fromKnowledgeTheReasonShowsUnderTheDefinitionWithTheAiChipAndReadAloudAtTheRight() =
        show {
            answered(knowledge)
            val question = app.getString(R.string.genre_why, "electronic")
            // The definition, then the question as a heading, then the reason, then *Read aloud*.
            val heading = compose.onNodeWithText(question).fetchSemanticsNode()
            assertTrue("a heading", heading.config.contains(SemanticsProperties.Heading))
            assertTrue("definition first", topOf(definition).bottom <= heading.boundsInRoot.top)
            assertTrue("then the reason", heading.boundsInRoot.bottom <= top(AI_GENRE_REASON_TEXT_TAG).top)
            val button = top(GENRE_READ_ALOUD_TAG)
            assertTrue("then Read aloud", top(AI_GENRE_REASON_TEXT_TAG).bottom <= button.top)
            // Alone at the right of its row (its 48 dp target wider than the 40 dp it draws): no credit beside it.
            assertTrue("at the right", button.right >= top(AI_GENRE_REASON_TAG).right - EDGE)
            assertTrue("alone", button.left > top(AI_GENRE_REASON_TAG).center.x)
            // Each sentence ends with the AI chip, read as the model's own knowledge; no line says so.
            val model = app.getString(R.string.home_cite_model)
            assertEquals(
                "Built from synthesisers and drum machines ($model). Its beat is programmed ($model).",
                said(AI_GENRE_REASON_TEXT_TAG),
            )
            assertFalse(shows(app.getString(R.string.genre_why_from_model)))
            // The answer's credit alone, once, under the chips.
            assertEquals(1, compose.onAllNodesWithText("Written by Claude Haiku 5.5.").fetchSemanticsNodes().size)
            assertTrue("credit last", top(AI_GENRE_REASON_TAG).bottom <= topOf("Written by Claude Haiku 5.5.").top)
        }

    @Test
    fun aGenreTheAnswerGivesNoReasonForHasNoChip() =
        show {
            val answer = answered(knowledge)
            assertEquals(listOf("Electronic"), answer.genres.map { it.label })
            val downtempo = vocabulary.labelsFor(listOf("downtempo"), level = GenreLevel.TRACK).single()
            val none = compose.onAllNodesWithTag(GENRE_PREFIX + "track:" + downtempo.identity)
            assertEquals(0, none.fetchSemanticsNodes().size)
        }

    @Test
    fun readAloudReadsTheQuestionThenTheReason() =
        show {
            answered(knowledge)
            compose.onNodeWithTag(GENRE_READ_ALOUD_TAG).performScrollTo().assertContentDescriptionEquals("Read aloud")
            tap(GENRE_READ_ALOUD_TAG)
            val parts = speech.spoken.single()
            assertEquals("Why is this electronic?", parts.first())
            // Each sentence from the model's own knowledge said to be unverified, as a genre's reason is.
            assertEquals(
                "Unverified: Built from synthesisers and drum machines. Unverified: Its beat is programmed.",
                parts.drop(1).joinToString(" "),
            )
        }

    @Test
    fun fromTheWebTheReasonCitesItsPageAndTheSiteIsNamedOnceUnderTheChips() =
        show {
            val reply =
                "{\"genres\": [{\"name\": \"electronic\", \"reason\": " +
                    "\"The album's page lists it under electronic. [S1]\", \"source\": \"$discogs\"}]}"
            answered(reply, pages = listOf(discogs))
            assertEquals("The album's page lists it under electronic (Source S1).", said(AI_GENRE_REASON_TEXT_TAG))
            // The page's chip is an action too, as *Explain the song*'s are.
            val actions =
                compose
                    .onNodeWithTag(AI_GENRE_REASON_TEXT_TAG)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsActions.CustomActions)
                    .orEmpty()
                    .map { it.label }
            assertEquals(listOf("Source S1"), actions)
            assertEquals(1, compose.onAllNodesWithTag(AI_SEARCH_FROM_TAG).fetchSemanticsNodes().size)
            assertFalse(shows(app.getString(R.string.home_cite_model)))
        }

    @Test
    fun aReasonsPageJoinsSourcesSoItsChipNeverNamesAnotherPagesLine() {
        // The track has a web line of its own, S1, another page: the reason's page is listed after it.
        val track = empty.copy(evidence = HomeFixtures.withWeb)
        current.value =
            NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())
        show {
            val reply =
                "{\"genres\": [{\"name\": \"electronic\", \"reason\": " +
                    "\"The album's page lists it under electronic. [S1]\", \"source\": \"$discogs\"}]}"
            answered(reply, pages = listOf(discogs))
            assertEquals("The album's page lists it under electronic (Source S2).", said(AI_GENRE_REASON_TEXT_TAG))
            // The chip opens *Sources* at that line, the page's, not the track's S1.
            compose
                .onNodeWithTag(AI_GENRE_REASON_TEXT_TAG)
                .performScrollTo()
                .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "S2" }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "S2").performScrollTo()
            assertTrue(compose.onAllNodesWithText("discogs.com", substring = true).fetchSemanticsNodes().isNotEmpty())
        }
    }
}

// Within this much of the edge, in pixels: an icon button's touch target is wider than its node.
private const val EDGE = 16f
