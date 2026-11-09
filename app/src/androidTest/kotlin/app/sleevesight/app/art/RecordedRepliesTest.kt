package app.sleevesight.app.art

import androidx.test.platform.app.InstrumentationRegistry
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.GenreListState
import app.sleevesight.core.pipeline.GenreLists
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.SearchState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The AI's answers read on Android's runtime (#1114), as the phone reads them, from model replies
 * kept as fixtures (`assets/replies/`), with no network: the genre list, a genre's explanation
 * (*Why is this …?*), *Explain the song* and a *Dates* answer, each through the pipeline's own class
 * for it. They are read with ICU's regex engine and Android's libraries, which the JVM's tests of the
 * same paths do not use (#1113: every reply read through `JsonReplies` failed on the phone alone).
 */
class RecordedRepliesTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val answers = AiSearchAnswers(vocabulary, JvmAccentFolder)
    private val at = Instant.parse("2026-11-01T10:00:00Z")

    @Test
    fun theGenreListIsReadAgainstTheVocabulary() =
        runBlocking {
            // *AI service knowledge* on, so the list may be asked; a genre only MusicBrainz gives.
            val sources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL
            val settings = Settings(aiProvider = ProviderId.ANTHROPIC, enabledSources = sources)
            val playing = NowPlaying("The Black Oak", "Emily Jane White", "Ode to Sentience", player = PLAYER)
            val drafts = listOf(draft(SourceId.WIKIPEDIA, "Emily Jane White is a singer-songwriter.", Scope.ARTIST))
            val genres =
                combineGenres(
                    mapOf(
                        LabelSource.MUSICBRAINZ to vocabulary.labelsFor(listOf("post-punk"), level = GenreLevel.TRACK),
                        LabelSource.LASTFM to vocabulary.labelsFor(listOf("folk"), level = GenreLevel.ALBUM),
                    ),
                    emptyList(),
                )
            val track = track(playing, drafts).copy(genres = genres)
            val lists = GenreLists(access("genre-list.txt"), PipelineCache.None, JvmHasher, vocabulary)
            val key = checkNotNull(lists.key(track, settings)) { "no genre list offered" }
            val listed = lists.ask(key, track) { _, _ -> }
            // "Gothic Folk" is not on the app's genre list, so it is dropped.
            assertTrue("$listed", listed is GenreListState.Listed)
            assertEquals(listOf("postpunk", "blues"), (listed as GenreListState.Listed).genres.map { it.identity })
        }

    @Test
    fun aGenresExplanationIsWrittenAndChecked() =
        runBlocking {
            val playing = NowPlaying("So What", "Miles Davis", "Kind of Blue", player = PLAYER)
            val drafts = listOf(draft(SourceId.WIKIPEDIA, "So What is a modal jazz composition by Miles Davis."))
            val label = GenreLabel("jazz", "Jazz", "jazz", null, GenreLevel.ARTIST, "Miles Davis")
            val credited = TrackLabel(label, listOf(LabelCredit(LabelSource.LASTFM, null)))
            val track =
                track(playing, drafts).copy(
                    genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(credited)))),
                )
            val reasons = GenreReasons(access("genre-reason.txt"), PipelineCache.None, JvmHasher)
            val key = checkNotNull(reasons.key(track, "jazz", Settings(aiProvider = ProviderId.ANTHROPIC)))
            val written = reasons.write(key, track, "Jazz") { _, _ -> }
            assertTrue("$written", written is InsightState.Written)
            assertEquals(1, (written as InsightState.Written).grounded.counts.ok)
        }

    @Test
    fun explainTheSongReadsAnAnswerWithAStrayQuote() =
        runBlocking {
            val wikipedia = "https://en.wikipedia.org/wiki/The_Black_Oak"
            val found = WebPage("https://music-mag.example/interview", "Interview", "She wrote it about loss.")
            val playing = NowPlaying("The Black Oak", "Emily Jane White", "Victorian America", player = PLAYER)
            val drafts = listOf(draft(SourceId.WIKIPEDIA, "The song is about an oak.", url = wikipedia))
            val track = track(playing, drafts)
            val searches =
                AiSearches(
                    access("explain.txt"),
                    PipelineCache.None,
                    JvmHasher,
                    answers,
                    web = { _, _ -> WebSearchOutcome.Found(listOf(found)) },
                )
            val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
            val key = checkNotNull(searches.key(track, SearchKind.EXPLAIN, settings)) { "no Explain the song offered" }
            val facts = SearchFacts("The Black Oak", listOf("Emily Jane White"), "Victorian America")
            val last =
                searches.search(
                    key,
                    SearchKind.EXPLAIN,
                    track,
                    facts,
                    { _, _ -> },
                    asking = AiSearches.Asking(AiWebSearch.ONCE),
                )
            assertTrue("$last", last is SearchState.Answered)
            val explanation = (last as SearchState.Answered).answer
            assertTrue("$explanation", explanation is SearchAnswer.Explanation)
            assertEquals(
                listOf(found.url, wikipedia),
                (explanation as SearchAnswer.Explanation).pages.map { it.url },
            )
        }

    @Test
    fun aDatesAnswerIsReadFromWordsAroundItsObject() =
        runBlocking {
            val playing = NowPlaying("Hallelujah", "Jeff Buckley", "Grace", player = PLAYER)
            val track = track(playing, emptyList())
            val searches = AiSearches(access("dates.txt"), PipelineCache.None, JvmHasher, answers)
            val key = checkNotNull(searches.key(track, SearchKind.DATES, Settings(aiProvider = ProviderId.ANTHROPIC)))
            val facts = SearchFacts("Hallelujah", listOf("Jeff Buckley"), "Grace")
            val last = searches.search(key, SearchKind.DATES, track, facts, { _, _ -> })
            assertTrue("$last", last is SearchState.Answered)
            val dates = (last as SearchState.Answered).answer
            assertTrue("$dates", dates is SearchAnswer.Dates)
            assertEquals("1994-08-23", (dates as SearchAnswer.Dates).album)
            assertEquals("Grace", dates.first?.title)
        }

    private fun track(
        playing: NowPlaying,
        drafts: List<EvidenceDraft>,
    ): TrackState =
        TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources = emptyMap(),
            evidence = EvidenceSet.of(drafts, JvmHasher),
        )

    private fun draft(
        source: SourceId,
        text: String,
        scope: Scope = Scope.SONG,
        url: String? = null,
    ): EvidenceDraft = EvidenceDraft(source, scope, text, url, at)

    // The AI service, answering every request with the reply in [fixture], streamed in parts as a
    // provider's events arrive.
    private fun access(fixture: String): AiAccess = AiAccess(Replaying(reply(fixture)), Secret("k"))

    private fun reply(fixture: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("replies/$fixture")
            .bufferedReader()
            .use { it.readText() }

    private class Replaying(
        private val reply: String,
    ) : AiProvider {
        override val id: ProviderId = ProviderId.ANTHROPIC

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                reply.chunked(CHUNK).forEach { emit(CompletionEvent.Delta(it)) }
                emit(CompletionEvent.Done(Usage(inputTokens = 900, outputTokens = 60), StopReason.END))
            }
    }

    private companion object {
        const val PLAYER = "com.spotify.music"
        const val CHUNK = 8
    }
}
