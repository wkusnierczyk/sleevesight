package app.sleevesight.app.pipeline

import app.sleevesight.app.pipeline.AskAi.Companion.empty
import app.sleevesight.app.pipeline.AskAi.Companion.punk
import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.app.ui.home.searchFacts
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * *Ask AI* in the diagnostics' events (#851): each question that goes to the AI service, how it was
 * started, by which model, how long it took and how it ended; each automatic one not asked as its
 * gates closed; each kept answer served, with when it was written, once per track; *Why is this …?*
 * likewise; and nothing from work before *Clear history* after it.
 */
class AskAiEventsTest {
    private val provider = CountingProvider()
    private val on = Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true)
    private val off = on.copy(searchWithAi = false)
    private val settings = MutableStateFlow<Settings?>(on)
    private val cache = CardCache()
    private val model = checkNotNull(on.model(ProviderId.ANTHROPIC))
    private val definition = AiQuestion.Search(SearchKind.DEFINITION, "punk")

    init {
        provider.reply = { n ->
            val prompt = provider.prompts[n - 1]
            when {
                "Name: Punk" in prompt -> "{\"definition\": \"Punk rock is fast, raw rock music.\"}"
                listOf("\"album_date\"", "\"genres\"", "\"lyrics_urls\"").any { it in prompt } -> "{}"
                else -> "Its loud guitars are rock [MB1]."
            }
        }
    }

    // The test's virtual time, as the app's clock.
    private val TestScope.clock: Clock
        get() =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            }

    private fun TestScope.app() = AskAi(this, settings, cache, provider, clock = clock)

    private val TrackState.key: String get() = normalized.lookupKey

    @Test
    fun `a definition asked by itself is told, and its answer served on a later track says when it was written`() =
        runTest {
            provider.streaming = 2.seconds
            val app = app()
            val first = punk()
            app.notes.lookups.fetchAhead(first)
            advanceUntilIdle()
            app.definitions.follow(first, settings.value)
            advanceUntilIdle()
            val written = Instant.fromEpochMilliseconds(2000)
            assertEquals(
                listOf(
                    PipelineEvent.AiAsked(first.key, definition, true, ProviderId.ANTHROPIC, model),
                    PipelineEvent.AiEnded(first.key, definition, AiOutcome.ANSWERED, ONE_CALL, 2.seconds),
                ),
                app.events,
            )
            // Its own track's panel: the answer to its call, not one kept; with a new length of the
            // track too, which plays on.
            app.searches.search(first, SearchKind.DEFINITION, "punk").first { it is AiSearch.Answered }
            val length = first.nowPlaying.duration?.plus(2.seconds)
            val longer = first.copy(nowPlaying = first.nowPlaying.copy(duration = length))
            app.searches.search(longer, SearchKind.DEFINITION, "punk").first { it is AiSearch.Answered }
            assertEquals(2, app.events.size)
            // Off; a later track's panel, opened twice: served from what was kept, told once.
            settings.value = off
            val later = punk(HomeFixtures.track(HomeFixtures.carefree))
            repeat(2) { app.searches.search(later, SearchKind.DEFINITION, "punk").first { it is AiSearch.Answered } }
            assertEquals(
                PipelineEvent.AiKept(later.key, definition, ProviderId.ANTHROPIC, model, written, automatic = true),
                app.events.drop(2).single(),
            )
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a question asked by a tap is told as one, with how it ended, stopped and failed included`() =
        runTest {
            settings.value = off
            val app = app()
            val dates = AiQuestion.Search(SearchKind.DATES)
            app.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            assertEquals(
                listOf(
                    PipelineEvent.AiAsked(empty().key, dates, false, ProviderId.ANTHROPIC, model),
                    PipelineEvent.AiEnded(empty().key, dates, AiOutcome.NOTHING_FOUND, ONE_CALL, 0.seconds),
                ),
                app.events,
            )
            // Stopped while it is under way.
            provider.streaming = 10.seconds
            app.searches.ask(empty(), SearchKind.GENRES, searchFacts(empty(), HomeFixtures.sundown))
            advanceTimeBy(1.seconds)
            app.searches.stop(empty(), SearchKind.GENRES)
            advanceUntilIdle()
            val stopped = app.events.last() as PipelineEvent.AiEnded
            assertEquals(AiQuestion.Search(SearchKind.GENRES), stopped.question)
            assertEquals(AiOutcome.STOPPED, stopped.outcome)
            // Failed, with its reason.
            provider.streaming = 0.seconds
            provider.error = AiErrorKind.OFFLINE
            app.searches.ask(empty(), SearchKind.LYRICS, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            val failed = app.events.last() as PipelineEvent.AiEnded
            assertEquals(AiOutcome.FAILED, failed.outcome)
            assertTrue(failed.failure != null)
        }

    @Test
    fun `a search started by itself whose gates closed before its call is told as not asked`() =
        runTest {
            val slow = SlowCache(cache).apply { holding = true }
            val app = AskAi(this, settings, slow, provider, clock = clock)
            app.panels.follow(empty(), settings.value)
            runCurrent()
            settings.value = off
            app.panels.follow(empty(), settings.value)
            slow.holding = false
            slow.open.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(SearchKind.DATES, SearchKind.GENRES, SearchKind.LYRICS).map {
                    PipelineEvent.AiNotAsked(empty().key, AiQuestion.Search(it))
                },
                app.events,
            )
        }

    @Test
    fun `an answer read from the cache before a call is told as kept, with no question, and once`() =
        runTest {
            provider.streaming = 3.seconds
            app().panels.follow(empty(), settings.value)
            advanceUntilIdle()
            // After a restart, by a tap and then its panel: one kept answer served, no call.
            val restarted = app()
            settings.value = off
            restarted.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            restarted.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            val dates = AiQuestion.Search(SearchKind.DATES)
            assertEquals(
                listOf(
                    PipelineEvent.AiKept(
                        empty().key,
                        dates,
                        ProviderId.ANTHROPIC,
                        model,
                        Instant.fromEpochMilliseconds(3000),
                        automatic = true,
                    ),
                ),
                restarted.events,
            )
            assertEquals(3, provider.requests)
        }

    @Test
    fun `a kept answer read only is not told as served, and then read for its panel is`() =
        runTest {
            provider.streaming = 3.seconds
            app().panels.follow(empty(), settings.value)
            advanceUntilIdle()
            // After a restart, read only (#996: *Genres* reading *Find the lyrics*' answer): not told.
            val restarted = app()
            settings.value = off
            restarted.searches.answered(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            advanceUntilIdle()
            assertEquals(emptyList<PipelineEvent>(), restarted.events)
            // Its panel then: served, told once.
            restarted.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            advanceUntilIdle()
            assertEquals(listOf(PipelineEvent.AiKept::class), restarted.events.map { it::class })
        }

    @Test
    fun `nothing of a question under way at Clear history is told after it`() =
        runTest {
            settings.value = off
            provider.streaming = 10.seconds
            val app = app()
            app.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceTimeBy(1.seconds)
            app.events.clear()
            app.searches.forget()
            advanceUntilIdle()
            assertEquals(emptyList<PipelineEvent>(), app.events)
        }

    // A track whose table has rock, from Last.fm, with the fixtures' evidence.
    private val rockTrack =
        HomeFixtures.track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            "A",
                            listOf(
                                TrackLabel(
                                    GenreLabel("rock", "rock", "Rock", "rock", null, GenreLevel.ARTIST, "A"),
                                    listOf(LabelCredit(LabelSource.LASTFM, null)),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    @Test
    fun `a reason asked by a tap is told, and one kept is told as served`() =
        runTest {
            settings.value = off
            val app = app()
            val reason = AiQuestion.Reason("Rock")
            app.notes.explain(rockTrack, "rock")
            advanceUntilIdle()
            assertEquals(
                listOf(
                    PipelineEvent.AiAsked(rockTrack.key, reason, false, ProviderId.ANTHROPIC, model),
                    PipelineEvent.AiEnded(rockTrack.key, reason, AiOutcome.ANSWERED, ONE_CALL, 0.seconds),
                ),
                app.events,
            )
            // After a restart, its panel: read from the cache and served, no call.
            val restarted = app()
            restarted.notes.reason(rockTrack, "rock").first { it is GenreReason.Shown }
            assertEquals(
                listOf(
                    PipelineEvent.AiKept(
                        rockTrack.key,
                        reason,
                        ProviderId.ANTHROPIC,
                        model,
                        Instant.fromEpochMilliseconds(0),
                        automatic = false,
                    ),
                ),
                restarted.events,
            )
            assertEquals(1, provider.requests)
        }

    // A cache whose reads, while [holding], each wait for a gate of their own ([gates], in the order
    // the reads came): reads under way at once, ended in an order a test chooses.
    private class GatedCache(
        private val cache: PipelineCache,
    ) : PipelineCache by cache {
        var holding = false
        val gates = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun keptCard(key: CardKey): KeptCard? {
            if (holding) CompletableDeferred<Unit>().also { gates += it }.await()
            return cache.keptCard(key)
        }
    }

    @Test
    fun `a kept answer read by a panel and by a tap at once is told once, whichever read ends first`() =
        runTest {
            settings.value = off
            app().searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            for (first in listOf(0, 1)) {
                val gated = GatedCache(cache).apply { holding = true }
                val restarted = AskAi(this, settings, gated, provider, clock = clock)
                // The panel's read, then the tap's, both under way.
                val panel = launch { restarted.searches.search(empty(), SearchKind.DATES).collect {} }
                runCurrent()
                restarted.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
                runCurrent()
                assertEquals(2, gated.gates.size)
                gated.gates[first].complete(Unit)
                runCurrent()
                gated.gates[1 - first].complete(Unit)
                advanceUntilIdle()
                panel.cancel()
                assertEquals("read $first ended first", 1, restarted.events.count { it is PipelineEvent.AiKept })
            }
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a kept reason read by its panel and by a tap at once is told once, whichever read ends first`() =
        runTest {
            settings.value = off
            app().notes.explain(rockTrack, "rock")
            advanceUntilIdle()
            for (first in listOf(0, 1)) {
                val gated = GatedCache(cache).apply { holding = true }
                val restarted = AskAi(this, settings, gated, provider, clock = clock)
                val panel = launch { restarted.notes.reason(rockTrack, "rock").collect {} }
                runCurrent()
                restarted.notes.explain(rockTrack, "rock")
                runCurrent()
                assertEquals(2, gated.gates.size)
                gated.gates[first].complete(Unit)
                runCurrent()
                gated.gates[1 - first].complete(Unit)
                advanceUntilIdle()
                panel.cancel()
                assertEquals("read $first ended first", 1, restarted.events.count { it is PipelineEvent.AiKept })
            }
            assertEquals(1, provider.requests)
        }

    private companion object {
        val ONE_CALL = QuestionCost(calls = 1)
    }
}
