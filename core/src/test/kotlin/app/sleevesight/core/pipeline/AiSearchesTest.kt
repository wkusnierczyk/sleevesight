package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.AiSearchPrompt
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Search with AI* (#773): one call, its answer checked and kept in the synthesis cache, keyed by the
 * track, the kind, the AI service, model and effort and the prompts' version, not the evidence;
 * failures shown and not kept; a search stopped before it settles shows and keeps nothing.
 */
class AiSearchesTest {
    private val provider = FakeProvider { FakeProvider.done("{\"genres\": [\"folk rock\", \"rock\"]}") }
    private val cache = MemoryCache()
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
    private val searches = AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers)
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val playing = nowPlaying("Hallelujah (2004 Remaster)", "Jeff Buckley", "Grace")
    private val track =
        TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), sources = emptyMap())
    private val facts = SearchFacts(track.normalized.title, track.normalized.artists, "Grace")

    // How a question from the model's knowledge, by a tap, was asked: one call.
    private val oneCall = SearchWay(cost = QuestionCost(calls = 1))

    private suspend fun search(
        kind: SearchKind = SearchKind.GENRES,
        shown: MutableList<SearchState> = mutableListOf(),
        settle: () -> Boolean = { true },
    ): SearchState? {
        val key = checkNotNull(searches.key(track, kind, settings))
        return searches.search(key, kind, track, facts, { state, _ -> shown += state }, settle)
    }

    @Test
    fun theKeyIsTheTrackTheKindTheServiceModelAndEffortAndThePromptsVersion() {
        val key = checkNotNull(searches.key(track, SearchKind.DATES, settings))
        assertEquals(CardKind.SEARCH, key.kind)
        assertEquals(track.normalized, key.track)
        assertEquals("dates\ngrace", key.subject)
        assertEquals(ProviderId.ANTHROPIC, key.provider)
        assertEquals(settings.model(ProviderId.ANTHROPIC), key.model)
        assertEquals(AiSearchPrompt.VERSION, key.promptVersion)
        assertEquals(AiSearches.NO_FINGERPRINT, key.evidenceFingerprint)
        // Not the evidence: the same key whatever the sources found.
        val withEvidence =
            track.copy(
                evidence =
                    app.sleevesight.core.evidence.EvidenceSet
                        .of(emptyList(), JvmHasher),
            )
        assertEquals(key, searches.key(withEvidence, SearchKind.DATES, settings))
        // Each kind its own; another model or effort another key.
        assertNotEquals(key, searches.key(track, SearchKind.LYRICS, settings))
        assertNotEquals(
            key,
            searches.key(track, SearchKind.DATES, settings.withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")),
        )
        assertNotEquals(
            key,
            searches.key(track, SearchKind.DATES, settings.withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)),
        )
    }

    @Test
    fun oneRecordingFromTwoAlbumsHasTwoDatesKeysAndOneForTheOtherKinds() {
        val compilation = track.copy(nowPlaying = playing.copy(album = "The Best of Jeff Buckley"))
        val volume = track.copy(nowPlaying = playing.copy(album = "Grace, Vol. 2"))
        val same = track.copy(nowPlaying = playing.copy(album = "Grace, Volume 2"))
        val dates = { t: TrackState -> searches.key(t, SearchKind.DATES, settings) }
        assertNotEquals(dates(track), dates(compilation))
        // Album names read as the panels compare them.
        assertEquals(dates(volume), dates(same))
        assertEquals(
            searches.key(track, SearchKind.LYRICS, settings),
            searches.key(compilation, SearchKind.LYRICS, settings),
        )
        assertEquals("dates\n", searches.subject(track.copy(nowPlaying = playing.copy(album = null)), SearchKind.DATES))
    }

    @Test
    fun noKeyWithNoAiServiceOrWithShowInsightsOff() {
        assertNull(AiSearches(null, cache, JvmHasher, answers).key(track, SearchKind.DATES, settings))
        assertNull(searches.key(track, SearchKind.DATES, settings.copy(writeInsights = false)))
    }

    @Test
    fun oneCallItsAnswerCheckedShownAndKept() =
        runTest {
            val shown = mutableListOf<SearchState>()
            val last = search(shown = shown)
            assertIs<SearchState.Answered>(last)
            assertEquals(listOf("Folk Rock", "Rock"), (last.answer as SearchAnswer.Genres).genres.map { it.label })
            assertEquals(listOf(SearchState.Searching(), last), shown)
            assertEquals(1, provider.requests.size)
            val request = provider.requests.single()
            assertEquals(AiSearchPrompt.build(SearchKind.GENRES, facts, answers.genreNames), request.prompt)
            assertTrue("folk rock" in request.prompt)
            assertEquals(AiSearches.maxTokensFor(request.effort), request.maxTokens)
            // Kept as the reply, read and checked again when found.
            val key = checkNotNull(searches.key(track, SearchKind.GENRES, settings))
            // Kept in its envelope (#801), with how it was asked.
            val kept = AiSearches.Kept.of(checkNotNull(cache.cards[key]))
            assertEquals("{\"genres\": [\"folk rock\", \"rock\"]}", kept.reply)
            assertEquals(1, kept.calls)
            assertEquals(last, searches.kept(key, SearchKind.GENRES, track)?.state)
        }

    @Test
    fun theTokenLimitIsHalfACards() {
        assertEquals(2048, AiSearches.maxTokensFor(ReasoningEffort.LOW))
        assertEquals(4096, AiSearches.maxTokensFor(ReasoningEffort.MEDIUM))
        assertEquals(8192, AiSearches.maxTokensFor(null))
    }

    @Test
    fun anAnswerWithNothingUsableIsNothingFoundAndKept() =
        runTest {
            provider.reply = { FakeProvider.done("{}") }
            assertEquals(SearchState.NothingFound(oneCall), search())
            val key = checkNotNull(searches.key(track, SearchKind.GENRES, settings))
            assertEquals("{}", AiSearches.Kept.of(checkNotNull(cache.cards[key])).reply)
            assertEquals(SearchState.NothingFound(oneCall), searches.kept(key, SearchKind.GENRES, track)?.state)
        }

    @Test
    fun aFailedCallIsShownWithWhyAndNotKept() =
        runTest {
            val cases =
                listOf(
                    listOf(CompletionEvent.Error(AiErrorKind.UNAUTHORIZED)) to "unauthorized",
                    listOf(CompletionEvent.Error(AiErrorKind.OFFLINE)) to "offline",
                    listOf(CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)) to "model_not_found",
                    FakeProvider.done("I can't help with that.", StopReason.REFUSAL) to ReplyFailures.DECLINED,
                    FakeProvider.done("It is folk rock, I think.") to AiSearches.MALFORMED,
                    FakeProvider.done("{\"genres\": [\"rock\"", StopReason.MAX_TOKENS) to ReplyFailures.INCOMPLETE,
                    FakeProvider.done("") to ReplyFailures.EMPTY,
                    FakeProvider.done("", StopReason.MAX_TOKENS) to ReplyFailures.LENGTH_LIMIT,
                    listOf(CompletionEvent.Delta("{")) to ReplyFailures.INCOMPLETE,
                )
            for ((events, reason) in cases) {
                provider.reply = { events }
                assertEquals(SearchState.Failed(reason), search(), reason)
            }
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun aSearchStoppedBeforeItSettlesShowsAndKeepsNothing() =
        runTest {
            val shown = mutableListOf<SearchState>()
            assertNull(search(shown = shown) { false })
            assertEquals(listOf<SearchState>(SearchState.Searching()), shown)
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun aCacheThatFailsIsAMissAndTheSearchStillShows() =
        runTest {
            val failing =
                object : PipelineCache by cache {
                    override suspend fun keptCard(key: CardKey): KeptCard? = error("cache down")

                    override suspend fun keep(
                        key: CardKey,
                        text: String,
                        writtenAt: kotlin.time.Instant,
                    ): Unit = error("cache down")
                }
            val down = AiSearches(AiAccess(provider, Secret("k")), failing, JvmHasher, answers)
            val key = checkNotNull(down.key(track, SearchKind.GENRES, settings))
            assertNull(down.kept(key, SearchKind.GENRES, track))
            assertIs<SearchState.Answered>(down.search(key, SearchKind.GENRES, track, facts, { _, _ -> }))
        }

    @Test
    fun aKeptLyricsAnswerIsCheckedAgainAsItIsRead() =
        runTest {
            val key = checkNotNull(searches.key(track, SearchKind.LYRICS, settings))
            cache.keep(
                key,
                "{\"lyrics_urls\": [\"https://evil.example/x\", " +
                    "\"https://genius.com/Jeff-buckley-hallelujah-lyrics\"]}",
            )
            val kept = assertNotNull(searches.kept(key, SearchKind.LYRICS, track)).state
            assertIs<SearchState.Answered>(kept)
            assertEquals(1, (kept.answer as SearchAnswer.Lyrics).links.size)
            // A kept reply that is no answer is none.
            cache.keep(key, "nonsense")
            assertNull(searches.kept(key, SearchKind.LYRICS, track))
        }

    @Test
    fun aDefinitionsKeyIsTheGenreNotTheTrack() {
        val key = checkNotNull(searches.key(track, SearchKind.DEFINITION, settings, genre = "rock"))
        assertEquals(CardKind.SEARCH, key.kind)
        assertEquals(AiSearches.GENRE_ENTITY, key.track)
        assertNull(key.duration)
        assertEquals("definition\nrock", key.subject)
        assertEquals(AiSearchPrompt.DEFINITION_VERSION, key.promptVersion)
        // Another track, the same key; another genre, another.
        val other =
            TrackState(nowPlaying("Numb", "Linkin Park", "Meteora"), track.normalized.copy(title = "numb"), emptyMap())
        assertEquals(key, searches.key(other, SearchKind.DEFINITION, settings, genre = "rock"))
        assertNotEquals(key, searches.key(track, SearchKind.DEFINITION, settings, genre = "metal"))
        // No genre, no key.
        assertNull(searches.key(track, SearchKind.DEFINITION, settings))
    }

    @Test
    fun aDefinitionIsAskedAboutTheGenreAndKept() =
        runTest {
            val asking = FakeProvider { FakeProvider.done("{\"definition\": \"Rock is a genre of popular music.\"}") }
            val made = AiSearches(AiAccess(asking, Secret("k")), cache, JvmHasher, answers)
            val key = checkNotNull(made.key(track, SearchKind.DEFINITION, settings, genre = "rock"))
            val state = made.search(key, SearchKind.DEFINITION, track, SearchFacts.ofGenre("Rock"), { _, _ -> })
            assertEquals(
                SearchState.Answered(SearchAnswer.Definition("Rock is a genre of popular music."), oneCall),
                state,
            )
            assertTrue("Name: Rock" in asking.requests.single().prompt)
            val kept = assertNotNull(made.kept(key, SearchKind.DEFINITION, track))
            assertEquals(state, kept.state)
        }
}
