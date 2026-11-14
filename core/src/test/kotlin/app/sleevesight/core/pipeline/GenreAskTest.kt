package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
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
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * The ask (#1117, *Is this …?*): for a genre only MusicBrainz's tags give that the AI's genre list
 * leaves out, its own key, apart from any reason of the genre; the ask's prompt, which the reply may
 * answer either way, not read as a dispute; the genre's name alone reaching the prompt, nothing else
 * of MusicBrainz's tags; and, with *AI web search*, the same cascade as a reason's.
 */
class GenreAskTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val cache = MemoryCache()
    private val page = WebPage("https://www.allmusic.com/album/thriller", "Thriller", "A pop and post-disco album.")
    private val tags = WebPage("https://musicbrainz.org/release/x", "Thriller", "Tags: funk")
    private val queries = mutableListOf<String>()
    private val web =
        WebSearcher { query, _ ->
            queries += query
            WebSearchOutcome.Found(listOf(tags, page))
        }

    // *AI service knowledge* on, as the AI's genre list needs; *AI web search* off unless a test sets it.
    private val settings =
        Settings(
            aiProvider = ProviderId.ANTHROPIC,
            enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL,
            aiWebSearch = AiWebSearch.OFF,
        )

    // "Thriller": MusicBrainz's contemporary R&B and funk on the track, Last.fm's pop on the album.
    private val track: TrackState =
        run {
            val playing = nowPlaying("Thriller", "Michael Jackson", "Thriller")
            val drafts = listOf(line(SourceId.WIKIPEDIA, "Thriller is a 1982 song by Michael Jackson.", Scope.SONG))
            val musicBrainz =
                vocabulary.labelsFor(listOf("contemporary r&b", "funk"), level = GenreLevel.TRACK)
            val lastFm = vocabulary.labelsFor(listOf("pop"), level = GenreLevel.ALBUM)
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

    private val named =
        track.genres.everyLabel
            .first { "contemporary" in it.genre.identity }
            .genre
    private val genre: String = named.identity
    private val label: String = named.label

    private fun reasons(provider: FakeProvider) =
        GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher, web = web)

    private fun lists(provider: FakeProvider) =
        GenreLists(AiAccess(provider, Secret("k")), cache, JvmHasher, vocabulary)

    @Test
    fun `a genre the AI's list leaves out has the ask's path, by a key of its own`() {
        val provider = FakeProvider()
        val reasons = reasons(provider)
        val path = reasons.path(track, genre, settings, lists(provider), setOf("pop"))
        val key = assertIs<ReasonPath.NotListed>(path).key
        assertEquals(CardKind.GENRE, key.kind)
        assertEquals(genre + GenreReasons.ASK, key.subject)
        assertEquals(GenreReasonPrompt.ASK_VERSION, key.promptVersion)
        // No reason's key for it, and the ask's is never a reason's.
        assertNull(reasons.key(track, genre, settings, setOf("pop")))
        // With *AI web search*, the web part after the ask's.
        val webKey = reasons.askKey(track, genre, settings.copy(aiWebSearch = AiWebSearch.ONCE))
        assertEquals(genre + GenreReasons.ASK + GenreReasons.WEB, webKey?.subject)
        // No AI service: none.
        assertNull(GenreReasons(null, cache, JvmHasher).askKey(track, genre, settings))
    }

    @Test
    fun `the ask is one call with the ask's prompt, and an answer that it does not fit is written and kept`() =
        runTest {
            val provider =
                FakeProvider {
                    FakeProvider.done("Thriller is a 1982 song [W1]. It is not contemporary R&B but pop [AI].")
                }
            val reasons = reasons(provider)
            val key = checkNotNull(reasons.askKey(track, genre, settings))
            val shown = mutableListOf<InsightState>()
            val written = assertIs<InsightState.Written>(reasons.write(key, track, label) { s, _ -> shown += s })
            assertTrue("not contemporary R&B" in written.grounded.text)
            val request = provider.requests.single()
            val system = checkNotNull(request.system)
            assertTrue(GenreReasonPrompt.MODEL_ASK_RULE in system)
            assertFalse(GenreReasonPrompt.MODEL_LABEL_RULE in system)
            assertTrue("asks whether it is of the genre" in system)
            // Kept under the ask's key, and found again with no call.
            val kept = cache.cards.keys.single()
            assertEquals(GenreReasonPrompt.ASK_VERSION, kept.promptVersion)
            assertIs<InsightState.Written>(reasons.found(key, track)?.state)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `the ask names the genre alone, and none of MusicBrainz's other tags`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Thriller is a 1982 song [W1].") }
            val reasons = reasons(provider)
            val key = checkNotNull(reasons.askKey(track, genre, settings))
            reasons.write(key, track, label) { _, _ -> }
            val sent = provider.requests.single().let { it.prompt + "\n" + it.system }
            assertTrue("<genre>\n$label\n</genre>" in sent)
            // Once, in its block; the track's other genre only MusicBrainz gives is not there.
            assertEquals(1, sent.windowed(label.length).count { it == label })
            assertFalse("funk" in sent.lowercase())
            assertFalse("musicbrainz" in sent.lowercase())
        }

    @Test
    fun `NO_EVIDENCE is the could-not state, kept`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }
            val reasons = reasons(provider)
            val key = checkNotNull(reasons.askKey(track, genre, settings))
            assertEquals(InsightState.NoEvidence, reasons.write(key, track, label) { _, _ -> })
            assertEquals(GroundingCheck.NO_EVIDENCE, cache.cards.values.single())
        }

    @Test
    fun `with web search the ask goes on to the web with the ask's prompt, no MusicBrainz page in it`() =
        runTest {
            val provider =
                FakeProvider { request ->
                    // The page's line, numbered after the track's evidence (no web line of its own).
                    if ("[S1]" in request.prompt) {
                        FakeProvider.done("It fits only in part: a page calls the album pop and post-disco [S1].")
                    } else {
                        FakeProvider.done(GroundingCheck.NO_EVIDENCE)
                    }
                }
            val reasons = reasons(provider)
            val on = settings.copy(aiWebSearch = AiWebSearch.ONCE)
            val key = checkNotNull(reasons.askKey(track, genre, on))
            val state = reasons.write(key, track, label, AiSearches.Asking(AiWebSearch.ONCE)) { _, _ -> }
            assertIs<InsightState.Written>(state)
            assertEquals(2, provider.requests.size)
            val second = provider.requests[1]
            assertTrue(GenreReasonPrompt.MODEL_ASK_RULE in checkNotNull(second.system))
            assertFalse("musicbrainz.org" in second.prompt || "funk" in second.prompt.lowercase())
            // The search names the genre, as a reason's does, and nothing else of the tags.
            assertTrue("$label genre" in queries.single())
            assertFalse("funk" in queries.single().lowercase())
        }
}
