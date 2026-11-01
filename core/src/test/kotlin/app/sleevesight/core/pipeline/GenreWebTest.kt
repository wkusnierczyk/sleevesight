package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
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
import app.sleevesight.core.synthesis.GenreListMode
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.WebPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * *Why is this …?* with *AI web search* (#801, the parts #762 left for it): path B's genres call from
 * the web (its mode first when web search is on; no genre in its query; no MusicBrainz page in its
 * prompt), and path A's reason asked again with web search when the evidence cannot support the
 * genre, the pages it found numbered after the track's web lines and kept with the reason; a search
 * that fails keeps nothing; each search and call counted.
 */
class GenreWebTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val cache = MemoryCache()
    private val page = WebPage("https://www.allmusic.com/song/the-black-oak", "The Black Oak", "A slow folk ballad.")
    private val tags = WebPage("https://musicbrainz.org/recording/x", "The Black Oak", "Tags: shoegaze")
    private var outcome: WebSearchOutcome = WebSearchOutcome.Found(listOf(tags, page))
    private val queries = mutableListOf<String>()
    private val web =
        WebSearcher { query ->
            queries += query
            outcome
        }
    private val counted = mutableListOf<QuestionCost>()
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // "The Black Oak": MusicBrainz's rock, Last.fm's album Folk, and one web line already (S1).
    private val track: TrackState =
        run {
            val playing = nowPlaying("The Black Oak", "Emily Jane White", "Ode to Sentience")
            val drafts =
                listOf(
                    line(SourceId.WIKIPEDIA, "Emily Jane White is a singer-songwriter.", Scope.ARTIST),
                    line(SourceId.WEB, "Emily Jane White's new album is out.", Scope.WEB),
                )
            val musicBrainz = vocabulary.labelsFor(listOf("rock"), level = GenreLevel.TRACK)
            val lastFm = vocabulary.labelsFor(listOf("folk"), level = GenreLevel.ALBUM)
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
                        mapOf(LabelSource.MUSICBRAINZ to musicBrainz, LabelSource.LASTFM to lastFm),
                        emptyList(),
                    ),
            )
        }

    @Test
    fun `the genres call is asked from the web when web search is on`() {
        val withModel = settings.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)
        assertEquals(GenreListMode.WEB, GenreLists.mode(settings, web = true))
        assertEquals(GenreListMode.WEB, GenreLists.mode(withModel, web = true))
        assertEquals(
            GenreListMode.KNOWLEDGE,
            GenreLists.mode(withModel.copy(aiWebSearch = AiWebSearch.OFF), web = true),
        )
        assertEquals(GenreListMode.KNOWLEDGE, GenreLists.mode(withModel, web = false))
        assertNull(GenreLists.mode(settings.copy(aiWebSearch = AiWebSearch.OFF), web = true))
    }

    @Test
    fun `path B's genres call from the web names no genre and gets no MusicBrainz page`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("""{"genres": ["folk", "blues"]}""") }
            val lists = GenreLists(AiAccess(provider, Secret("k")), cache, JvmHasher, vocabulary, web = web)
            val key = checkNotNull(lists.key(track, settings))
            assertEquals(GenreListMode.WEB.key, key.subject)
            val listed =
                lists.ask(key, track, AiSearches.Asking(AiWebSearch.ONCE), { counted += it }) { _, _ -> }
            assertEquals(listOf("folk", "blues"), assertIs<GenreListState.Listed>(listed).genres.map { it.identity })
            val query = queries.single()
            assertEquals("\"The Black Oak\" by Emily Jane White, song genre", query)
            val prompt = provider.requests.single().prompt
            assertTrue(page.url in prompt)
            assertFalse(
                "musicbrainz.org" in prompt ||
                    "shoegaze" in prompt ||
                    Regex("\\brock\\b").containsMatchIn(prompt.lowercase()),
            )
            assertEquals(listOf(QuestionCost.SEARCH, QuestionCost.CALL), counted)
            assertEquals(listed, lists.kept(key)?.state)
        }

    @Test
    fun `path A asks again with web search when the evidence cannot support the genre`() =
        runTest {
            val provider =
                FakeProvider { request ->
                    // The page's line, numbered after the track's own web line.
                    if ("[S2]" in request.prompt) {
                        FakeProvider.done("It is filed under folk for its slow ballads [S2].")
                    } else {
                        FakeProvider.done(GroundingCheck.NO_EVIDENCE)
                    }
                }
            val reasons = GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)
            val key = checkNotNull(reasons.key(track, "folk", settings))
            val state =
                reasons.write(key, track, "Folk", AiSearches.Asking(AiWebSearch.ONCE), { counted += it }) { _, _ -> }
            val written = assertIs<InsightState.Written>(state)
            // The page is S2, after the track's own web line S1, and cited.
            val searched = checkNotNull(written.searched)
            assertEquals(page.url, searched["S2"]?.url)
            assertEquals(0, written.grounded.counts.unknown)
            assertEquals(2, provider.requests.size)
            assertEquals("\"The Black Oak\" by Emily Jane White, Folk genre", queries.single())
            assertFalse("musicbrainz.org" in provider.requests.last().prompt)
            assertEquals(QuestionCost(searches = 1, calls = 2), counted.fold(QuestionCost()) { a, b -> a + b })
            // Kept with its page: read again as it was written.
            val kept = assertIs<InsightState.Written>(reasons.kept(key, track)?.state)
            assertEquals(written.grounded, kept.grounded)
            assertEquals(searched.lines.map { it.id to it.url }, kept.searched?.lines?.map { it.id to it.url })
        }

    @Test
    fun `path A's loop numbers the pages of each search as evidence lines`() =
        runTest {
            val provider =
                FakeProvider(usesTools = true) { request: CompletionRequest ->
                    when {
                        request.tools.isEmpty() -> FakeProvider.done(GroundingCheck.NO_EVIDENCE)
                        request.exchanges.isEmpty() ->
                            listOf(
                                CompletionEvent.ToolUse(
                                    ToolTurn(listOf(ToolCall("c", WebPrompt.TOOL_NAME, """{"query":"folk"}""")), "[]"),
                                ),
                                CompletionEvent.Done(FakeProvider.USAGE, StopReason.TOOL_USE),
                            )
                        else -> FakeProvider.done("It is filed under folk for its slow ballads [S2].")
                    }
                }
            val reasons = GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)
            val key = checkNotNull(reasons.key(track, "folk", settings))
            val state = reasons.write(key, track, "Folk", AiSearches.Asking(AiWebSearch.UP_TO, 2)) { _, _ -> }
            assertIs<InsightState.Written>(state)
            val loop = provider.requests[1]
            assertTrue("numbered from [S2] on" in loop.prompt)
            val result =
                provider.requests[2]
                    .exchanges
                    .single()
                    .results
                    .single()
                    .content
            assertTrue(result.startsWith("[S2] "), result)
            assertFalse("shoegaze" in result)
        }

    @Test
    fun `path A with a search that fails keeps nothing`() =
        runTest {
            outcome = WebSearchOutcome.Failed
            val provider = FakeProvider { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }
            val reasons = GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)
            val key = checkNotNull(reasons.key(track, "folk", settings))
            val state = reasons.write(key, track, "Folk", AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
            assertEquals(InsightState.Failed(AiSearches.WEB_SEARCH_FAILED), state)
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `with web search off, path A is one call as before`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }
            val reasons = GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)
            val key = checkNotNull(reasons.key(track, "folk", settings))
            assertEquals(InsightState.NoEvidence, reasons.write(key, track, "Folk") { _, _ -> })
            assertEquals(1, provider.requests.size)
            assertTrue(queries.isEmpty())
        }
}
