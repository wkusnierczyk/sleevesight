package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * *Search with AI* in the app (#773): asked on a tap, one call, kept with the track (in memory by its
 * key, so it shows again with no call; in the synthesis cache, so it shows again when the track plays
 * later), dropped past *Keep history for*, forgotten on *Clear history*; *Stop* ends the call, and
 * nothing from it is shown or kept; a failure is shown and not kept; the model named as the settings
 * name it.
 */
class AppAiSearchesTest {
    private val provider = CountingProvider().apply { reply = { "{\"genres\": [\"folk rock\"]}" } }
    private val clock = MovedClock()
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val cache = CardCache(clock = clock) { settings.value?.historyLifetime }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
            ),
        )
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder, clock)
    private val track: TrackState = HomeFixtures.track()
    private val facts = SearchFacts(track.normalized.title, track.normalized.artists)

    private fun TestScope.searches(epoch: ClearEpoch = ClearEpoch()) =
        AppAiSearches(parts, settings, answers, this, epoch, modelName = { "Named $it" }, clock = clock)

    private suspend fun AppAiSearches.now(kind: SearchKind = SearchKind.GENRES): AiSearch = search(track, kind).first()

    @Test
    fun `a search is offered, asked once on a tap, and shows its answer credited to the model by name`() =
        runTest {
            val searches = searches()
            assertEquals(AiSearch.Offered, searches.now())
            assertTrue(searches.ask(track, SearchKind.GENRES, facts))
            // A second tap, or the setting's start, while it runs asks nothing more.
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            val answered = searches.now() as AiSearch.Answered
            assertEquals("Named ${Settings().model(ProviderId.ANTHROPIC)}", answered.model)
            assertEquals(listOf("Folk Rock"), (answered.answer as SearchAnswer.Genres).genres.map { it.label })
            assertEquals(1, provider.requests)
            // Each panel its own search.
            assertEquals(AiSearch.Offered, searches.now(SearchKind.DATES))
        }

    @Test
    fun `an answer shows again with no call when the panel is opened again`() =
        runTest {
            val searches = searches()
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            val answered = searches.now()
            // The panel closed and opened, or the page left: a new subscription, the same answer.
            assertEquals(answered, searches.now())
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `an answer kept shows again when the track plays later, with no call`() =
        runTest {
            searches().ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            // A process later: nothing in memory, the cache keeps it.
            val later = searches()
            val search = later.search(track, SearchKind.GENRES)
            search.first()
            advanceUntilIdle()
            assertTrue(search.first() is AiSearch.Answered)
            // Asked again (the setting's start), the kept answer, no call.
            val other = searches()
            other.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertTrue(other.now() is AiSearch.Answered)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `an answer with nothing usable says so and is kept as an answer is`() =
        runTest {
            provider.reply = { "{}" }
            val searches = searches()
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertTrue(searches.now() is AiSearch.NothingFound)
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertEquals(1, cache.held)
        }

    @Test
    fun `a failure is shown, not kept, and offered again`() =
        runTest {
            provider.error = AiErrorKind.UNAUTHORIZED
            val searches = searches()
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertEquals(AiSearch.Failed("unauthorized"), searches.now())
            assertEquals(0, cache.held)
            provider.error = null
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertTrue(searches.now() is AiSearch.Answered)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `Stop ends the call, and nothing from it is shown or kept`() =
        runTest {
            provider.streaming = 10.seconds
            val searches = searches()
            searches.ask(track, SearchKind.GENRES, facts)
            advanceTimeBy(1.seconds)
            assertTrue(searches.now() is AiSearch.Searching)
            searches.stop(track, SearchKind.GENRES)
            advanceUntilIdle()
            assertEquals(AiSearch.Offered, searches.now())
            assertEquals(0, provider.completed)
            assertEquals(0, cache.held)
            // A tap after it asks afresh.
            provider.streaming = 0.seconds
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertTrue(searches.now() is AiSearch.Answered)
        }

    @Test
    fun `leaving the panel does not stop a call`() =
        runTest {
            provider.streaming = 10.seconds
            val searches = searches()
            val watching = launch { searches.search(track, SearchKind.GENRES).collect {} }
            searches.ask(track, SearchKind.GENRES, facts)
            advanceTimeBy(1.seconds)
            // The panel closes: no one collects it any more.
            watching.cancel()
            advanceUntilIdle()
            assertEquals(1, provider.completed)
            assertTrue(searches.now() is AiSearch.Answered)
        }

    @Test
    fun `an answer shown is offered again past Keep history for`() =
        runTest {
            settings.value = settings.value?.copy(historyLifetime = 7.days)
            val searches = searches()
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertTrue(searches.now() is AiSearch.Answered)
            clock.at += 7.days
            assertEquals(AiSearch.Offered, searches.now())
            // The cache serves it no longer either: a tap asks again.
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `Clear history forgets the answers shown, and a call under way shows and keeps nothing`() =
        runTest {
            val epoch = ClearEpoch()
            val searches = searches(epoch)
            searches.ask(track, SearchKind.GENRES, facts)
            advanceUntilIdle()
            cache.clearCards()
            epoch.advance()
            searches.forget()
            assertEquals(AiSearch.Offered, searches.now())
            // One asked, then cleared while it runs.
            provider.streaming = 10.seconds
            searches.ask(track, SearchKind.DATES, facts)
            advanceTimeBy(1.seconds)
            epoch.advance()
            searches.forget()
            advanceUntilIdle()
            assertEquals(AiSearch.Offered, searches.now(SearchKind.DATES))
            assertEquals(0, cache.held)
        }

    @Test
    fun `with Show insights off no search is offered at all, and none is asked`() =
        runTest {
            val searches = searches()
            settings.value = settings.value?.copy(writeInsights = false)
            assertEquals(AiSearch.None, searches.now())
            assertFalse(searches.ask(track, SearchKind.GENRES, facts))
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `with no AI service ready the search is offered disabled, and a tap asks nothing`() =
        runTest {
            val searches = searches()
            parts.value =
                parts.value?.let { PipelineParts(it.normalizer, it.adapters, it.resolver, it.hasher, null, it.cache) }
            assertEquals(AiSearch.Unready, searches.now())
            assertFalse(searches.ask(track, SearchKind.GENRES, facts))
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // Before the pipeline or the settings are in, nothing at all.
            parts.value = null
            assertEquals(AiSearch.None, searches.now())
            assertEquals(AiSearch.None, AiSearchFeed.None.search(track, SearchKind.GENRES).first())
        }
}
