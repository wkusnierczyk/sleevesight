package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.VocalsFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WebSearchOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check of a track's vocals in the app (#998): started by itself with *Ask AI automatically* on
 * (once a play, only while its gates are open), or by a tap on *Why is this instrumental?*, which then
 * writes the reason; its searches and calls counted under *AI web search*; its answer kept per track,
 * so a replay asks nothing.
 */
class AppGenreVocalsTest {
    private val clock = MovedClock()
    private val cache = CardCache(clock = clock)
    private val queries = mutableListOf<String>()

    // The check answers vocals, citing the page found; any call after it is a reason.
    // What the check answers.
    private var said = "vocals"

    private val provider =
        CountingProvider().apply {
            reply = { n ->
                if (n == 1) {
                    """{"answer": "$said", "source": "${VocalsFixtures.page.url}"}"""
                } else {
                    "It is tagged so [MB1]."
                }
            }
        }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                webSearch = { query, _ ->
                    queries += query
                    WebSearchOutcome.Found(listOf(VocalsFixtures.page))
                },
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true))

    private val definitions =
        GenreDefinitions(
            object : GenreArticles {
                override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
            },
            VocalsFixtures.vocabulary,
        )

    private val paid = mutableListOf<QuestionCost>()
    private val events = mutableListOf<PipelineEvent>()
    private var open = true

    private fun TestScope.notes() =
        AppGenreNotes(
            definitions,
            parts,
            settings,
            this,
            clock = clock,
            paid = { paid += it },
            events = { events += it },
            automaticOpen = { open },
        )

    private val track = VocalsFixtures.nasnusa

    @Test
    fun `with Ask AI automatically on, the check runs by itself, once a play, counted under AI web search`() =
        runTest {
            val notes = notes()
            notes.checkVocals(track)
            notes.checkVocals(track)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertEquals(1, queries.size)
            assertTrue("instrumental" in queries.single() && "vocals" in queries.single())
            val shown = notes.vocals(track).first { it != null }
            assertEquals(VocalsAnswer.VOCALS, (shown?.state as VocalsState.Checked).answer)
            assertFalse(shown.tapped)
            assertEquals(listOf(QuestionCost.SEARCH, QuestionCost.CALL), paid)
            val asked = events.filterIsInstance<PipelineEvent.AiAsked>().single()
            assertEquals(AiQuestion.Vocals, asked.question)
            assertTrue(asked.automatic)
        }

    @Test
    fun `it does not run by itself with the setting off, its gate closed, or genres that do not call for it`() =
        runTest {
            val notes = notes()
            settings.value = settings.value?.copy(searchWithAi = false)
            notes.checkVocals(track)
            settings.value = settings.value?.copy(searchWithAi = true)
            open = false
            notes.checkVocals(track)
            open = true
            notes.checkVocals(VocalsFixtures.biteNoSign)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertTrue(queries.isEmpty())
        }

    @Test
    fun `a tap runs the check first, then the reason, and the answer is the tap's on this play`() =
        runTest {
            said = "instrumental"
            settings.value = settings.value?.copy(searchWithAi = false)
            val notes = notes()
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertTrue("Does this track have vocals?" in provider.prompts[0])
            assertFalse("Does this track have vocals?" in provider.prompts[1])
            val shown = notes.vocals(track).first { it != null }
            assertTrue(shown?.tapped == true)
            assertEquals(AiQuestion.Vocals, events.filterIsInstance<PipelineEvent.AiAsked>().first().question)
            assertFalse(events.filterIsInstance<PipelineEvent.AiAsked>().first().automatic)
        }

    @Test
    fun `a tap whose check finds vocals writes no reason, then or on a second tap`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            val notes = notes()
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(notes.vocals(track).first { it != null }?.tapped == true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(events.filterIsInstance<PipelineEvent.AiAsked>().none { it.question != AiQuestion.Vocals })
        }

    @Test
    fun `the answer is kept per track, so a replay asks nothing`() =
        runTest {
            notes().apply { checkVocals(track) }
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // The next play, in a new process: the answer is read from the cache, not asked.
            val replay = track.copy(nowPlaying = track.nowPlaying.copy(play = 2))
            val again = notes()
            val shown = again.vocals(replay).first { it != null }
            assertEquals(VocalsAnswer.VOCALS, (shown?.state as VocalsState.Checked).answer)
            assertFalse(shown.tapped)
            again.checkVocals(replay)
            again.explain(replay, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            // Nothing: the check is kept, and a track found sung gets no reason.
            assertEquals(1, provider.requests)
            assertEquals(1, queries.size)
        }

    @Test
    fun `with AI web search off there is no check`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = app.sleevesight.core.settings.AiWebSearch.OFF)
            val notes = notes()
            notes.checkVocals(track)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertNull(notes.vocals(track).first())
        }
}
