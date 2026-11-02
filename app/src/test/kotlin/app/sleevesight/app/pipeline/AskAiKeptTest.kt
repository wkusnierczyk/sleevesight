package app.sleevesight.app.pipeline

import app.sleevesight.app.pipeline.AskAi.Companion.empty
import app.sleevesight.app.pipeline.AskAi.Companion.punk
import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.app.ui.home.searchFacts
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * An AI answer kept from before (#851): one written before this play of the track it is shown for,
 * served from what was kept, carries when it was written, for its panel to say; one written on this
 * play does not. For a genre's definition, a later track's play; for a panel's answer or a genre's
 * reason, the track's next play, in memory or from the cache; with no play known, one read from the
 * cache.
 */
class AskAiKeptTest {
    private val provider = CountingProvider()
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true))
    private val cache = CardCache()

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

    // A track whose table has rock, from Last.fm, with the fixtures' evidence.
    private val rock =
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

    // The test's virtual time, as the app's clock.
    private val TestScope.clock: Clock
        get() =
            object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            }

    @Test
    fun `a definition written on this play is not kept, and on a later track's play it is`() =
        runTest {
            provider.streaming = 2.seconds
            val plays = Plays(clock)
            val app = AskAi(this, settings, cache, provider, clock = clock, plays = plays)
            val first = punk()
            plays.follow(first)
            app.notes.lookups.fetchAhead(first)
            advanceUntilIdle()
            app.definitions.follow(first, settings.value)
            advanceUntilIdle()
            val fresh = app.searches.search(first, SearchKind.DEFINITION, "punk").first { it is AiSearch.Answered }
            assertNull((fresh as AiSearch.Answered).keptAt)
            advanceTimeBy(1.minutes)
            val later = punk(HomeFixtures.track(HomeFixtures.carefree))
            plays.follow(later)
            val kept = app.searches.search(later, SearchKind.DEFINITION, "punk").first { it is AiSearch.Answered }
            assertEquals(Instant.fromEpochMilliseconds(2000), (kept as AiSearch.Answered).keptAt)
        }

    @Test
    fun `a panel's answer is kept on the track's next play`() =
        runTest {
            val plays = Plays(clock)
            val app = AskAi(this, settings, cache, provider, clock = clock, plays = plays)
            plays.follow(empty())
            app.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            val fresh = app.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            assertNull((fresh as AiSearch.NothingFound).keptAt)
            // Another track, then this one again: a new play.
            advanceTimeBy(1.minutes)
            plays.follow(empty(HomeFixtures.carefree))
            plays.follow(empty())
            val kept = app.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            assertEquals(Instant.fromEpochMilliseconds(0), (kept as AiSearch.NothingFound).keptAt)
        }

    @Test
    fun `with no play known, an answer read from the cache is kept, one written for the track is not`() =
        runTest {
            val app = AskAi(this, settings, cache, provider, clock = clock)
            app.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            val fresh = app.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            assertNull((fresh as AiSearch.NothingFound).keptAt)
            advanceTimeBy(1.minutes)
            val restarted = AskAi(this, settings, cache, provider, clock = clock)
            val kept = restarted.searches.search(empty(), SearchKind.DATES).first { it is AiSearch.NothingFound }
            assertEquals(Instant.fromEpochMilliseconds(0), (kept as AiSearch.NothingFound).keptAt)
        }

    @Test
    fun `a reason written on this play is not kept, and on the track's next play it is`() =
        runTest {
            val plays = Plays(clock)
            val app = AskAi(this, settings, cache, provider, clock = clock, plays = plays)
            plays.follow(rock)
            app.notes.explain(rock, "rock")
            advanceUntilIdle()
            assertNull((app.notes.reason(rock, "rock").first { it is GenreReason.Shown } as GenreReason.Shown).keptAt)
            advanceTimeBy(1.minutes)
            plays.follow(empty(HomeFixtures.carefree))
            plays.follow(rock)
            val kept = app.notes.reason(rock, "rock").first { it is GenreReason.Shown } as GenreReason.Shown
            assertEquals(Instant.fromEpochMilliseconds(0), kept.keptAt)
        }

    @Test
    fun `a panel watching before the play changes shows the answer kept once the play does`() =
        runTest {
            val plays = Plays(clock)
            val app = AskAi(this, settings, cache, provider, clock = clock, plays = plays)
            plays.follow(empty())
            app.searches.ask(empty(), SearchKind.DATES, searchFacts(empty(), HomeFixtures.sundown))
            advanceUntilIdle()
            advanceTimeBy(1.minutes)
            plays.follow(empty(HomeFixtures.carefree))
            // The panel for this track watches before the play of it is followed (A, B, then A).
            val shown = mutableListOf<AiSearch>()
            val panel = launch { app.searches.search(empty(), SearchKind.DATES).collect { shown += it } }
            advanceUntilIdle()
            assertNull((shown.last() as AiSearch.NothingFound).keptAt)
            plays.follow(empty())
            advanceUntilIdle()
            assertEquals(Instant.fromEpochMilliseconds(0), (shown.last() as AiSearch.NothingFound).keptAt)
            panel.cancel()
        }

    @Test
    fun `a reason's panel watching before the play changes shows it kept once the play does`() =
        runTest {
            val plays = Plays(clock)
            val app = AskAi(this, settings, cache, provider, clock = clock, plays = plays)
            plays.follow(rock)
            app.notes.explain(rock, "rock")
            advanceUntilIdle()
            advanceTimeBy(1.minutes)
            plays.follow(empty(HomeFixtures.carefree))
            val shown = mutableListOf<GenreReason>()
            val panel = launch { app.notes.reason(rock, "rock").collect { shown += it } }
            advanceUntilIdle()
            assertNull((shown.last() as GenreReason.Shown).keptAt)
            plays.follow(rock)
            advanceUntilIdle()
            assertEquals(Instant.fromEpochMilliseconds(0), (shown.last() as GenreReason.Shown).keptAt)
            panel.cancel()
        }
}
