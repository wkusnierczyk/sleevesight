package app.sleevesight.app.pipeline

import app.sleevesight.app.pipeline.AskAi.Companion.another
import app.sleevesight.app.pipeline.AskAi.Companion.empty
import app.sleevesight.app.pipeline.AskAi.Companion.punk
import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate of *Ask AI automatically* (#851), wired as `AppGraph` wires it: with the setting off, no
 * AI call for a genre Wikipedia has no definition for, nor for an empty *Dates*, *Genres* or *Lyrics*,
 * whether the question was never asked or was asked before (on an earlier track or play, or before a
 * restart), when only what was kept is served; and the setting turned off while a search started by
 * itself reads the cache, before its call, stops it.
 */
class AskAiGateTest {
    private val provider = CountingProvider()
    private val on = Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true)
    private val off = on.copy(searchWithAi = false)
    private val settings = MutableStateFlow<Settings?>(off)
    private val cache = CardCache()

    // Punk's definition, as the AI service answers it; the panels' replies have nothing usable.
    private val punkDefinition = "Punk rock is fast, raw rock music."

    init {
        provider.reply = { n ->
            if ("Name: Punk" in provider.prompts[n - 1]) "{\"definition\": \"$punkDefinition\"}" else "{}"
        }
    }

    // [track]'s genres looked up (Wikipedia has no article for punk), then followed by the trigger.
    private fun TestScope.followDefinitions(
        app: AskAi,
        track: TrackState,
    ) {
        app.notes.lookups.fetchAhead(track)
        advanceUntilIdle()
        app.definitions.follow(track, settings.value)
        advanceUntilIdle()
    }

    // What [app]'s panel shows for [track]'s search of [kind] ([genre]'s), once it shows more than the button.
    private suspend fun shown(
        app: AskAi,
        track: TrackState,
        kind: SearchKind,
        genre: String? = null,
    ): AiSearch = app.searches.search(track, kind, genre).first { it !is AiSearch.Offered }

    @Test
    fun `with the setting off, a genre with no definition asks nothing, never asked or asked on an earlier track`() =
        runTest {
            val app = AskAi(this, settings, cache, provider)
            val first = punk()
            // Never asked: nothing.
            followDefinitions(app, first)
            assertEquals(0, provider.requests)
            // Asked by itself on an earlier track, with the setting on.
            settings.value = on
            followDefinitions(app, first)
            assertEquals(1, provider.requests)
            // Off, a later track with the genre: no call; its panel shows the answer kept.
            settings.value = off
            val later = punk(HomeFixtures.track(HomeFixtures.carefree))
            followDefinitions(app, later)
            val kept = shown(app, later, SearchKind.DEFINITION, "punk") as AiSearch.Answered
            assertEquals(SearchAnswer.Definition(punkDefinition), kept.answer)
            assertEquals(true, kept.way.automatic)
            assertEquals(1, provider.requests)
            // After a restart, read from the cache: no call either.
            val restarted = AskAi(this, settings, cache, provider)
            followDefinitions(restarted, later.another())
            assertEquals(
                SearchAnswer.Definition(punkDefinition),
                (shown(restarted, later.another(), SearchKind.DEFINITION, "punk") as AiSearch.Answered).answer,
            )
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `with the setting off, empty Dates, Genres and Lyrics ask nothing, never asked or asked on an earlier play`() =
        runTest {
            val app = AskAi(this, settings, cache, provider)
            // Never asked: nothing.
            app.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // Asked by itself on an earlier play, with the setting on.
            settings.value = on
            app.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(3, provider.requests)
            // Off; another track, then this one again: no call; each panel shows what was kept.
            settings.value = off
            app.panels.follow(empty(HomeFixtures.carefree), settings.value)
            app.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            for (kind in listOf(SearchKind.DATES, SearchKind.GENRES, SearchKind.LYRICS)) {
                assertEquals(AiSearch.NothingFound::class, shown(app, empty(), kind)::class)
            }
            assertEquals(3, provider.requests)
            // After a restart, read from the cache: no call either.
            val restarted = AskAi(this, settings, cache, provider)
            restarted.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            for (kind in listOf(SearchKind.DATES, SearchKind.GENRES, SearchKind.LYRICS)) {
                assertEquals(AiSearch.NothingFound::class, shown(restarted, empty(), kind)::class)
            }
            advanceUntilIdle()
            assertEquals(3, provider.requests)
        }

    @Test
    fun `the setting turned off while a genre's definition reads the cache stops its call`() =
        runTest {
            settings.value = on
            val slow = SlowCache(cache).apply { holding = true }
            val app = AskAi(this, settings, slow, provider)
            app.notes.lookups.fetchAhead(punk())
            advanceUntilIdle()
            app.definitions.follow(punk(), settings.value)
            runCurrent()
            // Off while the cache is read; it misses.
            settings.value = off
            app.definitions.follow(punk(), settings.value)
            slow.holding = false
            slow.open.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // On again: as if it had not been tried.
            settings.value = on
            app.definitions.follow(punk(), settings.value)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `the setting turned off while Dates, Genres and Lyrics read the cache stops their calls`() =
        runTest {
            settings.value = on
            val slow = SlowCache(cache).apply { holding = true }
            val app = AskAi(this, settings, slow, provider)
            app.panels.follow(empty(), settings.value)
            runCurrent()
            settings.value = off
            app.panels.follow(empty(), settings.value)
            slow.holding = false
            slow.open.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            settings.value = on
            app.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(3, provider.requests)
        }
}
