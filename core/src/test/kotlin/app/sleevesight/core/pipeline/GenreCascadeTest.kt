package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * *Why is this …?*'s cascade (#695): one tap, one answer. The first call is from the sources and,
 * with *AI service knowledge* on, the model's own knowledge; an answer citing a source stands; one
 * from the knowledge alone, or `NO_EVIDENCE`, goes on to web search when *AI web search* is on; the
 * web explaining nothing shows the first call's answer from the knowledge if there was one, else
 * `NO_EVIDENCE`. Each row of the decided table, with replies of each kind.
 */
class GenreCascadeTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val cache = MemoryCache()
    private val page = WebPage("https://www.allmusic.com/song/the-black-oak", "The Black Oak", "A slow folk ballad.")
    private var outcome: WebSearchOutcome = WebSearchOutcome.Found(listOf(page))
    private val queries = mutableListOf<String>()
    private val web =
        WebSearcher { query ->
            queries += query
            outcome
        }
    private val base = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val knowledge = base.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)

    // "The Black Oak": Last.fm's album Folk; one Wikipedia line (W1) and one web line already (S1),
    // so the page a search finds is S2.
    private val track: TrackState =
        run {
            val playing = nowPlaying("The Black Oak", "Emily Jane White", "Ode to Sentience")
            val drafts =
                listOf(
                    line(SourceId.WIKIPEDIA, "Emily Jane White is a singer-songwriter.", Scope.ARTIST),
                    line(SourceId.WEB, "Emily Jane White's new album is out.", Scope.WEB),
                )
            TrackState(
                nowPlaying = playing,
                normalized = Normalizer(JvmAccentFolder).normalize(playing),
                sources =
                    mapOf(
                        SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                    ),
                evidence = EvidenceSet.of(drafts, JvmHasher),
                genres =
                    combineGenres(
                        mapOf(LabelSource.LASTFM to vocabulary.labelsFor(listOf("folk"), level = GenreLevel.ALBUM)),
                        emptyList(),
                    ),
            )
        }

    // A provider whose first call (no page among its evidence) replies [first], and whose call with
    // web search (the page's line, S2, in its prompt) replies [second].
    private fun provider(
        first: String,
        second: () -> List<CompletionEvent> = { FakeProvider.done(WEB) },
    ) = FakeProvider { request: CompletionRequest ->
        if ("[S2]" in request.prompt) second() else FakeProvider.done(first)
    }

    private class Run(
        val state: InsightState,
        val shown: List<InsightState>,
    )

    private suspend fun GenreReasons.run(
        settings: Settings,
        search: AiWebSearch = AiWebSearch.ONCE,
    ): Run {
        val shown = mutableListOf<InsightState>()
        val key = checkNotNull(key(track, "folk", settings))
        val state = write(key, track, "Folk", AiSearches.Asking(search)) { s, _ -> shown += s }
        return Run(state, shown)
    }

    private fun reasons(provider: FakeProvider) =
        GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)

    // Knowledge off, web off.

    @Test
    fun `knowledge and web off - a sourced answer stands`() =
        runTest {
            val provider = provider(SOURCED)
            val run = reasons(provider).run(base.copy(aiWebSearch = AiWebSearch.OFF))
            val written = assertIs<InsightState.Written>(run.state)
            assertTrue(written.grounded.citesSource)
            assertEquals(1, provider.requests.size)
            assertTrue(queries.isEmpty())
        }

    @Test
    fun `knowledge and web off - NO_EVIDENCE is the could-not state`() =
        runTest {
            val provider = provider(GroundingCheck.NO_EVIDENCE)
            val run = reasons(provider).run(base.copy(aiWebSearch = AiWebSearch.OFF))
            assertEquals(InsightState.NoEvidence, run.state)
            assertEquals(1, provider.requests.size)
            assertFalse(GenreReasonPrompt.MODEL_RULE in provider.requests.single().prompt)
        }

    // Knowledge on, web off.

    @Test
    fun `knowledge on, web off - an answer wholly from the model stands, in one call`() =
        runTest {
            val provider = provider(MODEL)
            val run = reasons(provider).run(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            val written = assertIs<InsightState.Written>(run.state)
            assertTrue(written.grounded.fromModelAlone)
            assertNull(written.searched)
            assertEquals(1, provider.requests.size)
            assertTrue(queries.isEmpty())
            assertTrue(GenreReasonPrompt.MODEL_RULE in provider.requests.single().prompt)
        }

    @Test
    fun `knowledge on, web off - a mixed answer stands, and NO_EVIDENCE is the could-not state`() =
        runTest {
            val mixed = reasons(provider(MIXED)).run(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            val written = assertIs<InsightState.Written>(mixed.state)
            assertTrue(written.grounded.citesSource)
            assertFalse(written.grounded.fromModelAlone)
            val none = reasons(provider(GroundingCheck.NO_EVIDENCE)).run(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            assertEquals(InsightState.NoEvidence, none.state)
            assertTrue(queries.isEmpty())
        }

    // Knowledge on, web on.

    @Test
    fun `knowledge and web on - a sourced answer stands, with no search`() =
        runTest {
            val provider = provider(MIXED)
            val run = reasons(provider).run(knowledge)
            assertTrue(assertIs<InsightState.Written>(run.state).grounded.citesSource)
            assertEquals(1, provider.requests.size)
            assertTrue(queries.isEmpty())
            // Shown as it came, once it cited a source.
            assertTrue(run.shown.any { it is InsightState.Writing && it.partial.isNotEmpty() })
        }

    @Test
    fun `knowledge and web on - an answer wholly from the model goes on to the web, whose pages stand`() =
        runTest {
            val provider = provider(MODEL)
            val run = reasons(provider).run(knowledge)
            val written = assertIs<InsightState.Written>(run.state)
            assertTrue(written.grounded.citesSource)
            assertEquals(page.url, written.searched?.get("S2")?.url)
            assertEquals(2, provider.requests.size)
            assertEquals(1, queries.size)
            // The second call may use the knowledge too.
            assertTrue(GenreReasonPrompt.MODEL_RULE in provider.requests.last().prompt)
            // One answer: the first call's text never shows, not even as it came.
            assertTrue(run.shown.none { it is InsightState.Writing && it.partial.isNotEmpty() && "[AI]" in it.partial })
            assertEquals(listOf(written), run.shown.filterIsInstance<InsightState.Written>())
        }

    @Test
    fun `knowledge and web on - the web finding nothing shows the first call's answer from the model`() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val provider = provider(MODEL)
            val reasons = reasons(provider)
            val run = reasons.run(knowledge)
            val written = assertIs<InsightState.Written>(run.state)
            assertTrue(written.grounded.fromModelAlone)
            assertNull(written.searched)
            assertEquals(1, provider.requests.size)
            assertEquals(1, queries.size)
            assertEquals(listOf(written), run.shown.filterIsInstance<InsightState.Written>())
            // Kept: the web's "nothing" under its key, the first call's answer under its own, and
            // the two read back as shown.
            val key = checkNotNull(reasons.key(track, "folk", knowledge))
            assertEquals(InsightState.NoEvidence, reasons.kept(key, track)?.state)
            val found = assertIs<InsightState.Written>(reasons.found(key, track)?.state)
            assertEquals(written.grounded, found.grounded)
        }

    @Test
    fun `knowledge and web on - the web's NO_EVIDENCE, or an answer from the model again, shows the first's`() =
        runTest {
            val none = reasons(provider(MODEL) { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }).run(knowledge)
            assertEquals(MODEL_TEXT, assertIs<InsightState.Written>(none.state).grounded.text)
            val again =
                GenreReasons(
                    AiAccess(provider(MODEL) { FakeProvider.done(MODEL_AGAIN) }, Secret("k")),
                    MemoryCache(),
                    JvmHasher,
                    web = web,
                ).run(knowledge)
            assertEquals(MODEL_TEXT, assertIs<InsightState.Written>(again.state).grounded.text)
        }

    @Test
    fun `knowledge and web on - NO_EVIDENCE then nothing on the web is the could-not state`() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val run = reasons(provider(GroundingCheck.NO_EVIDENCE)).run(knowledge)
            assertEquals(InsightState.NoEvidence, run.state)
            assertEquals(1, queries.size)
        }

    @Test
    fun `knowledge and web on - NO_EVIDENCE then the web's answer citing no page is the could-not state`() =
        runTest {
            val reasons = reasons(provider(GroundingCheck.NO_EVIDENCE) { FakeProvider.done(MODEL_AGAIN) })
            val run = reasons.run(knowledge)
            assertEquals(InsightState.NoEvidence, run.state)
            assertTrue(run.shown.none { it is InsightState.Written })
            // Kept as one, and found so.
            val key = checkNotNull(reasons.key(track, "folk", knowledge))
            assertEquals(InsightState.NoEvidence, reasons.kept(key, track)?.state)
            assertEquals(InsightState.NoEvidence, reasons.found(key, track)?.state)
        }

    @Test
    fun `knowledge off, web on - the web's uncited answer is the could-not state`() =
        runTest {
            val run = reasons(provider(GroundingCheck.NO_EVIDENCE) { FakeProvider.done("It is folk.") }).run(base)
            assertEquals(InsightState.NoEvidence, run.state)
        }

    @Test
    fun `a web answer citing no page kept from before is found as the could-not state`() =
        runTest {
            val reasons = reasons(provider(GroundingCheck.NO_EVIDENCE))
            val key = checkNotNull(reasons.key(track, "folk", knowledge))
            cache.cards[key.about("folk")] = GroundingCheck.NO_EVIDENCE
            cache.cards[key] = KeptReasonText(MODEL_AGAIN, emptyList()).text()
            assertIs<InsightState.Written>(reasons.kept(key, track)?.state)
            assertEquals(InsightState.NoEvidence, reasons.found(key, track)?.state)
        }

    @Test
    fun `knowledge and web on - a failed search shows the failure, and Retry keeps the first call's answer`() =
        runTest {
            outcome = WebSearchOutcome.Failed
            val provider = provider(MODEL)
            val reasons = reasons(provider)
            val failed = reasons.run(knowledge)
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), failed.state)
            assertTrue(failed.shown.none { it is InsightState.Written })
            // Retry: no first call again; the web finds nothing, so the first call's answer shows.
            outcome = WebSearchOutcome.Found(emptyList())
            val retried = reasons.run(knowledge)
            assertTrue(assertIs<InsightState.Written>(retried.state).grounded.fromModelAlone)
            assertEquals(1, provider.requests.size)
            assertEquals(2, queries.size)
            // Asked again with a page found: the web's answer, the first call still not repaid.
            outcome = WebSearchOutcome.Found(listOf(page))
            val found = reasons.run(knowledge)
            assertTrue(assertIs<InsightState.Written>(found.state).grounded.citesSource)
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun `knowledge and web on - a failed second call shows the failure`() =
        runTest {
            val run =
                reasons(provider(MODEL) { listOf(CompletionEvent.Error(AiErrorKind.OFFLINE)) }).run(knowledge)
            assertIs<InsightState.Failed>(run.state)
            assertTrue(run.shown.none { it is InsightState.Written })
        }

    @Test
    fun `knowledge and web on - an answer from the model alone kept is not found before the web is asked`() =
        runTest {
            val reasons = reasons(provider(MODEL))
            reasons.run(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            val on = checkNotNull(reasons.key(track, "folk", knowledge))
            assertNull(reasons.found(on, track))
            // A sourced one is.
            val sourced = GenreReasons(AiAccess(provider(MIXED), Secret("k")), MemoryCache(), JvmHasher, web = web)
            sourced.run(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            assertNotNull(sourced.found(on, track))
        }

    // Knowledge off, web on.

    @Test
    fun `knowledge off, web on - a sourced answer stands, NO_EVIDENCE goes on to the web`() =
        runTest {
            val sourced = provider(SOURCED)
            assertTrue(assertIs<InsightState.Written>(reasons(sourced).run(base).state).grounded.citesSource)
            assertTrue(queries.isEmpty())
            val provider = provider(GroundingCheck.NO_EVIDENCE)
            val run = GenreReasons(AiAccess(provider, Secret("k")), MemoryCache(), JvmHasher, web = web).run(base)
            val written = assertIs<InsightState.Written>(run.state)
            assertNotNull(written.searched)
            assertEquals(2, provider.requests.size)
            assertFalse(GenreReasonPrompt.MODEL_RULE in provider.requests.last().prompt)
        }

    @Test
    fun `knowledge off, web on - nothing on the web is the could-not state`() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val run = reasons(provider(GroundingCheck.NO_EVIDENCE)).run(base)
            assertEquals(InsightState.NoEvidence, run.state)
            assertEquals(1, queries.size)
        }

    // A track no source said anything of: no evidence line at all, the artists known.
    private val bare: TrackState = track.copy(evidence = EvidenceSet.of(emptyList(), JvmHasher))

    // A provider for [bare]: [first] for the first call, [second] for the one with the page, its only line.
    private fun bareProvider(
        first: String,
        second: String = "A review files her slow ballads under folk [S1].",
    ) = FakeProvider { request: CompletionRequest ->
        FakeProvider.done(if (page.text in request.prompt) second else first)
    }

    private suspend fun GenreReasons.bare(settings: Settings): InsightState {
        val key = checkNotNull(key(bare, "folk", settings))
        return write(key, bare, "Folk", AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
    }

    @Test
    fun `knowledge on, no evidence line - the first call is made, and its answer from the model stands`() =
        runTest {
            val provider = bareProvider(MODEL)
            val state = reasons(provider).bare(knowledge.copy(aiWebSearch = AiWebSearch.OFF))
            assertTrue(assertIs<InsightState.Written>(state).grounded.fromModelAlone)
            assertEquals(1, provider.requests.size)
            assertTrue("<evidence>\n(none)\n</evidence>" in provider.requests.single().prompt)
        }

    @Test
    fun `knowledge on, no evidence line, web on finding nothing - the first call's answer from the model`() =
        runTest {
            outcome = WebSearchOutcome.Found(emptyList())
            val provider = bareProvider(MODEL)
            val state = reasons(provider).bare(knowledge)
            assertEquals(MODEL_TEXT, assertIs<InsightState.Written>(state).grounded.text)
            assertEquals(1, provider.requests.size)
            assertEquals(1, queries.size)
        }

    @Test
    fun `knowledge off, no evidence line - no first call`() =
        runTest {
            val provider = bareProvider(MODEL)
            assertEquals(InsightState.NoEvidence, reasons(provider).bare(base.copy(aiWebSearch = AiWebSearch.OFF)))
            assertTrue(provider.requests.isEmpty())
            // With web search on: straight to the search, and one call with its page.
            val searched = GenreReasons(AiAccess(provider, Secret("k")), MemoryCache(), JvmHasher, web = web).bare(base)
            assertTrue(assertIs<InsightState.Written>(searched).grounded.citesSource)
            assertEquals(1, provider.requests.size)
            assertTrue(page.text in provider.requests.single().prompt)
        }

    @Test
    fun `whether an answer cites a source is read from its citations, not its words`() {
        val ids = setOf("W1", "S1")

        fun read(text: String) = assertIs<InsightReply.Written>(GroundingCheck.check(text, ids, true)).grounded
        assertTrue(read("The sources say it is folk [W1].").citesSource)
        // Words about sources, cited [AI] alone: from the model.
        assertTrue(read("Wikipedia and the web say it is folk [AI].").fromModelAlone)
        // A sentence citing a line that does not exist cites no source.
        assertFalse(read("It is folk [W9].").citesSource)
        assertTrue(read("It is folk [W1][AI].").citesSource)
        assertFalse(read("It is folk.").fromModelAlone)
    }

    private companion object {
        const val SOURCED = "Emily Jane White is a singer-songwriter in the folk manner [W1]."
        const val MODEL_TEXT = "Her finger-picked ballads are what listeners file as folk [AI]."
        const val MODEL = MODEL_TEXT
        const val MODEL_AGAIN = "Folk is the usual home of such ballads [AI]."
        const val MIXED = "She is a singer-songwriter [W1]. Her ballads are what listeners file as folk [AI]."
        const val WEB = "A review files her slow ballads under folk [S2]."
    }
}
