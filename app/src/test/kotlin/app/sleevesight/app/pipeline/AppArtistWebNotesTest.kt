package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The artist's insight in the app for an artist the sources say nothing about (#912): a web search
 * for the artist made only by a write, through the write's gates, counted as paid; its lines kept
 * per artist, so the next track of the artist finds the insight with no search or call.
 */
class AppArtistWebNotesTest {
    private val cache = CardCache()
    private val artistWeb = Lines()
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(
                aiProvider = ProviderId.ANTHROPIC,
                enabledSources = Settings().enabledSources + SourceSwitch.WEB,
                webEngines = setOf(WebEngine.EXA),
                aiWebSearch = AiWebSearch.ONCE,
            ),
        )
    private var open = true
    private var paid = QuestionCost()
    private val events = mutableListOf<PipelineEvent>()

    // As `ArtistWebSource`'s: what a search found kept per artist; a failed one keeps nothing.
    private class Lines(
        var found: List<EvidenceDraft>? = listOf(WEB_LINE),
    ) : ArtistWebLookup {
        val kept = mutableMapOf<ArtistWebQuery, List<EvidenceDraft>>()
        var searches = 0

        // While set, a search's read of the kept lines waits for it.
        var reading: CompletableDeferred<Unit>? = null

        override suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>? = kept[artist]

        override suspend fun search(
            artist: ArtistWebQuery,
            sent: () -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft>? {
            reading?.await()
            return kept[artist] ?: run {
                if (!mayPay()) return null
                searches++
                sent()
                found?.also { kept[artist] = it }
            }
        }

        override suspend fun keep(
            artist: ArtistWebQuery,
            lines: List<EvidenceDraft>,
        ) {
            kept[artist] = lines
        }

        override suspend fun renew(artist: ArtistWebQuery) = Unit
    }

    // A model that, with the search tool, searches once, then writes; without it, writes at once.
    private class Searching(
        override val usesTools: Boolean,
    ) : AiProvider {
        override val id: ProviderId = ProviderId.ANTHROPIC
        var requests = 0

        // While set, the first call waits for it: the model's first turn under way.
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                requests++
                if (requests == 1) hold?.await()
                if (request.tools.isNotEmpty() && request.toolsAllowed && request.exchanges.isEmpty()) {
                    val call = ToolCall("c1", WebPrompt.TOOL_NAME, """{"query":"Gordon Lightfoot singer"}""")
                    emit(CompletionEvent.ToolUse(ToolTurn(listOf(call), "[]")))
                    emit(CompletionEvent.Done(USAGE, StopReason.TOOL_USE))
                } else {
                    emit(CompletionEvent.Delta("Gordon Lightfoot was a Canadian folk singer [S1]."))
                    emit(CompletionEvent.Done(USAGE, StopReason.END))
                }
            }
    }

    // The AI's own searches, each finding a page about the artist, or failing while [failing].
    private class Pages : WebSearcher {
        var searches = 0
        var failing = false

        override suspend fun search(query: String): WebSearchOutcome {
            searches++
            return if (failing) WebSearchOutcome.Failed else WebSearchOutcome.Found(listOf(PAGE))
        }
    }

    private fun parts(
        provider: AiProvider,
        web: WebSearcher? = null,
        lookup: ArtistWebLookup? = artistWeb,
        cards: PipelineCache = cache,
    ) = MutableStateFlow<PipelineParts?>(made(provider, web, lookup, cards))

    private fun made(
        provider: AiProvider,
        web: WebSearcher?,
        lookup: ArtistWebLookup?,
        cards: PipelineCache = cache,
    ) = PipelineParts(
        normalizer = Normalizer(JvmAccentFolder),
        adapters = emptyList(),
        resolver = TrackResolver { _, _ -> Resolution.NONE },
        hasher = JvmHasher,
        ai = AiAccess(provider, Secret("key")),
        cache = cards,
        webSearch = web,
        artistWeb = lookup,
    )

    private fun TestScope.notes(parts: MutableStateFlow<PipelineParts?>) =
        AppItemNotes(
            parts,
            settings,
            this,
            NameMatcher(JvmAccentFolder),
            ClearEpoch(),
            paid = { paid += it },
            events = { events += it },
            gate = { open },
        )

    // A track of Sundown with a song line only: nothing about the artist.
    private fun track(playing: NowPlaying): TrackState {
        val drafts = listOf(HomeFixtures.draft(SourceId.MUSICBRAINZ, "${playing.title} was recorded in Toronto."))
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher)).copy(
            resolution =
                Resolution(
                    recordingMbid = "rec-${playing.title}",
                    artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
                ),
        )
    }

    private suspend fun AppItemNotes.settled(track: TrackState): ItemShown =
        item(track, InsightItem.ARTIST).first {
            it is ItemShown.Offered || (it is ItemShown.Shown && it.state !is InsightState.Writing)
        }

    @Test
    fun `once, the artist's write searches for the artist, and the next track finds its insight with no search`() =
        runTest {
            val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a Canadian folk singer [S1]." } }
            val notes = notes(parts(provider))
            val first = track(HomeFixtures.sundown)
            // Shown, unfolded, its key looked up: no search.
            assertEquals(ItemShown.Offered, notes.settled(first))
            assertEquals(0, artistWeb.searches)

            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, artistWeb.searches)
            assertEquals(1, provider.requests)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
            assertTrue(WEB_LINE.text in provider.prompts.single())
            val shown = notes.settled(first) as ItemShown.Shown
            assertTrue(shown.state is InsightState.Written)
            assertEquals(
                listOf(SourceId.WEB),
                shown.evidence.evidence.lines
                    .map { it.source },
            )
            // *Sources* lists the artist's web line.
            assertEquals(
                listOf(WEB_LINE.text),
                notes
                    .artistEvidence(first)
                    .first()
                    ?.lines
                    ?.map { it.text },
            )

            // The next track of the artist finds it kept, with no search or call.
            val next = track(HomeFixtures.carefree)
            assertTrue((notes.settled(next) as ItemShown.Shown).state is InsightState.Written)
            notes.write(next, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, artistWeb.searches)
            assertEquals(1, provider.requests)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
        }

    @Test
    fun `a failed search shows the write failed, keeps nothing, and is made again on a tap`() =
        runTest {
            artistWeb.found = null
            val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a Canadian folk singer [S1]." } }
            val notes = notes(parts(provider))
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            val failed = notes.item(first, InsightItem.ARTIST).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), failed.state)
            assertEquals(0, provider.requests)
            assertEquals(QuestionCost(searches = 1), paid)
            assertTrue(artistWeb.kept.isEmpty())

            // *Retry*: searched again, and written.
            artistWeb.found = listOf(WEB_LINE)
            notes.write(first, InsightItem.ARTIST, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, artistWeb.searches)
            assertTrue((notes.settled(first) as ItemShown.Shown).state is InsightState.Written)
        }

    @Test
    fun `no search for the artist with AI web search off`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            val provider = CountingProvider()
            val notes = notes(parts(provider))
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(0, artistWeb.searches)
            assertEquals(0, provider.requests)
            assertEquals(
                InsightState.NoEvidence,
                (
                    notes.item(first, InsightItem.ARTIST).first {
                        it is ItemShown.Shown
                    } as ItemShown.Shown
                ).state,
            )
        }

    @Test
    fun `up to 3 times, the AI service searches while it writes, and the next track finds its insight`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val provider = Searching(usesTools = true)
            val pages = Pages()
            val notes = notes(parts(provider, pages))
            val first = track(HomeFixtures.sundown)
            val before = notes.settled(first)
            assertEquals(ItemShown.Offered, before)

            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, pages.searches)
            assertEquals(0, artistWeb.searches)
            assertEquals(2, provider.requests)
            assertEquals(QuestionCost(searches = 1, calls = 2), paid)
            val shown = notes.settled(first) as ItemShown.Shown
            assertTrue(shown.state is InsightState.Written)
            assertEquals(
                listOf(PAGE.url),
                shown.evidence.evidence.lines
                    .map { it.url },
            )
            assertEquals(
                Scope.ARTIST,
                shown.evidence.evidence.lines
                    .single()
                    .scope,
            )
            assertEquals(
                listOf(PAGE.url),
                artistWeb.kept.values
                    .single()
                    .map { it.url },
            )
            assertEquals(
                listOf(PAGE.url),
                notes
                    .artistEvidence(first)
                    .first()
                    ?.lines
                    ?.map { it.url },
            )
            assertNotEquals(EvidenceSet.of(emptyList(), JvmHasher).fingerprint, shown.key?.evidenceFingerprint)

            // The next track of the artist finds the pages kept and the insight: no search or call.
            val next = track(HomeFixtures.carefree)
            assertTrue((notes.settled(next) as ItemShown.Shown).state is InsightState.Written)
            notes.write(next, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, pages.searches)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `up to 3 times, no search or call while the coordinator's gate is closed`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val provider = Searching(usesTools = true)
            val pages = Pages()
            val notes = notes(parts(provider, pages))
            open = false
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ARTIST, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            assertEquals(0, pages.searches)
            assertEquals(0, provider.requests)
            assertEquals(ItemShown.Offered, notes.item(first, InsightItem.ARTIST).first { it !is ItemShown.Gathering })
        }

    @Test
    fun `up to 3 times with a service that takes no tools is the app's own search`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val provider = Searching(usesTools = false)
            val pages = Pages()
            val notes = notes(parts(provider, pages))
            notes.write(track(HomeFixtures.sundown), InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(0, pages.searches)
            assertEquals(1, artistWeb.searches)
            assertEquals(1, provider.requests)
        }

    // Up to 3 times, with the model's first turn held until [then] has changed what the write may do.
    private fun TestScope.firstTurnHeld(then: () -> Unit): Pair<Searching, Pages> {
        settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
        val provider = Searching(usesTools = true).apply { hold = CompletableDeferred() }
        val pages = Pages()
        val notes = notes(parts(provider, pages))
        notes.write(track(HomeFixtures.sundown), InsightItem.ARTIST)
        advanceUntilIdle()
        assertEquals(1, provider.requests)
        then()
        checkNotNull(provider.hold).complete(Unit)
        advanceUntilIdle()
        return provider to pages
    }

    @Test
    fun `up to 3 times, the gate closed during the first call stops the write before any search or call`() =
        runTest {
            val (provider, pages) = firstTurnHeld { open = false }
            assertEquals(0, pages.searches)
            assertEquals(1, provider.requests)
            assertEquals(QuestionCost(calls = 1), paid)
            assertTrue(artistWeb.kept.isEmpty())
            // The call paid for ends the question as stopped, with its cost; never "not asked".
            val ended = events.filterIsInstance<PipelineEvent.AiEnded>().single()
            assertEquals(AiOutcome.STOPPED, ended.outcome)
            assertEquals(QuestionCost(calls = 1), ended.cost)
            assertEquals(1, events.filterIsInstance<PipelineEvent.AiAsked>().size)
            assertTrue(events.none { it is PipelineEvent.AiNotAsked })
        }

    @Test
    fun `up to 3 times, Exa web search switched off during the first call stops the write before any search`() =
        runTest {
            val (provider, pages) =
                firstTurnHeld {
                    settings.value =
                        settings.value?.let { it.copy(enabledSources = it.enabledSources - SourceSwitch.WEB) }
                }
            assertEquals(0, pages.searches)
            assertEquals(1, provider.requests)
            assertTrue(artistWeb.kept.isEmpty())
        }

    // *Once*, with the lookup's read of the kept lines held until [then] has changed what the write may do.
    private fun TestScope.onceReadHeld(then: () -> Unit): AppItemNotes {
        val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a Canadian folk singer [S1]." } }
        val notes = notes(parts(provider))
        val reading = CompletableDeferred<Unit>()
        artistWeb.reading = reading
        notes.write(track(HomeFixtures.sundown), InsightItem.ARTIST)
        advanceUntilIdle()
        then()
        reading.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, artistWeb.searches)
        assertEquals(0, paid.searches)
        assertEquals(0, provider.requests)
        assertTrue(artistWeb.kept.isEmpty())
        return notes
    }

    @Test
    fun `once, the gate closed while the kept lines are read makes no search, and the artist is offered again`() =
        runTest {
            val notes = onceReadHeld { open = false }
            assertEquals(
                ItemShown.Offered,
                notes.item(track(HomeFixtures.sundown), InsightItem.ARTIST).first { it !is ItemShown.Gathering },
            )
        }

    @Test
    fun `once, Exa web search switched off while the kept lines are read makes no search`() =
        runTest {
            onceReadHeld {
                settings.value = settings.value?.let { it.copy(enabledSources = it.enabledSources - SourceSwitch.WEB) }
            }
        }

    @Test
    fun `an artist opened with AI web search off is searched for once Once is chosen`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a Canadian folk singer [S1]." } }
            val notes = notes(parts(provider))
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(InsightState.NoEvidence, (notes.settled(first) as ItemShown.Shown).state)

            // Once chosen: gathered again, a search due; the earlier "nothing to write from" stands for none.
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.ONCE)
            assertEquals(ItemShown.Offered, notes.settled(first))
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, artistWeb.searches)
            assertTrue((notes.settled(first) as ItemShown.Shown).state is InsightState.Written)
        }

    @Test
    fun `an artist opened before Exa's key was given is searched for once it is`() =
        runTest {
            val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a Canadian folk singer [S1]." } }
            val parts = parts(provider, lookup = null)
            val notes = notes(parts)
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(InsightState.NoEvidence, (notes.settled(first) as ItemShown.Shown).state)

            // The key given: the parts rebuilt with the artist's web search.
            parts.value = made(provider, null, artistWeb)
            assertEquals(ItemShown.Offered, notes.settled(first))
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, artistWeb.searches)
            assertTrue((notes.settled(first) as ItemShown.Shown).state is InsightState.Written)
        }

    @Test
    fun `up to 3 times, another track asking while the card is being kept makes no second write`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val keeping = CompletableDeferred<Unit>()
            // The synthesis cache, its keeping of a card held until [keeping] completes.
            val held =
                object : PipelineCache by cache {
                    override suspend fun keep(
                        key: CardKey,
                        text: String,
                        writtenAt: Instant,
                        rewritten: Set<RewritePart>,
                    ) {
                        keeping.await()
                        cache.keep(key, text, writtenAt, rewritten)
                    }
                }
            val provider = Searching(usesTools = true)
            val pages = Pages()
            val notes = notes(parts(provider, pages, cards = held))
            notes.write(track(HomeFixtures.sundown), InsightItem.ARTIST)
            advanceUntilIdle()
            // The pages kept, the card not yet: the next track gathers them and asks for the insight.
            assertEquals(
                listOf(PAGE.url),
                artistWeb.kept.values
                    .flatten()
                    .map { it.url },
            )
            val next = track(HomeFixtures.carefree)
            notes.write(next, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertEquals(1, pages.searches)

            keeping.complete(Unit)
            advanceUntilIdle()
            assertTrue((notes.settled(next) as ItemShown.Shown).state is InsightState.Written)
            assertEquals(2, provider.requests)
            assertEquals(1, pages.searches)
        }

    @Test
    fun `up to 3 times, a write whose searches all fail leaves the artist's other tracks with the search due`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.UP_TO)
            val provider = Searching(usesTools = true)
            val pages = Pages().apply { failing = true }
            val notes = notes(parts(provider, pages))
            val first = track(HomeFixtures.sundown)
            val next = track(HomeFixtures.carefree)
            // The next track gathered before the first's write.
            assertEquals(ItemShown.Offered, notes.settled(next))
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, pages.searches)
            val failed = notes.item(first, InsightItem.ARTIST).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), failed.state)
            assertTrue(artistWeb.kept.isEmpty())

            // A tap on the next track makes the loop's search, and writes the insight.
            pages.failing = false
            notes.write(next, InsightItem.ARTIST, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, pages.searches)
            assertTrue((notes.settled(next) as ItemShown.Shown).state is InsightState.Written)
        }

    private companion object {
        val USAGE = Usage(inputTokens = 10, outputTokens = 5)

        val WEB_LINE =
            HomeFixtures.draft(
                SourceId.WEB,
                "Gordon Lightfoot was a Canadian folk singer.",
                "https://example.com/lightfoot",
                Scope.ARTIST,
            )

        val PAGE =
            WebPage(
                "https://example.com/gordon",
                "Gordon Lightfoot",
                "Gordon Lightfoot was a Canadian folk singer-songwriter.",
            )
    }
}
