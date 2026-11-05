package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The search in the track's language (#902), through the pipeline: when it runs (*Off*, *If needed*,
 * *Always*, and each lookup's own switch), in which language, how its lines join the evidence the
 * insight waits for, and how the retrieval cache keeps them. Five regional tracks, each thin in
 * English (synthetic fixtures, no source's real text), show what each source gives before and after.
 */
class PipelineLanguageTest {
    /** A track: as played, what the English sources give, and what MusicBrainz and Wikidata record. */
    private class Regional(
        val nowPlaying: NowPlaying,
        val english: Map<SourceId, List<String>>,
        val resolution: Resolution = Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec"),
        val wikidata: List<String> = emptyList(),
        val releases: List<String> = emptyList(),
    )

    private val bollywood =
        Regional(
            nowPlaying("Tum Hi Ho", "Arijit Singh", "Aashiqui 2"),
            mapOf(SourceId.MUSICBRAINZ to listOf("“Tum Hi Ho” was written by Mithoon.")),
            resolution = Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec-tum"),
            // MusicBrainz records no lyrics language for the work here; its tracklists are Hindi.
            releases = listOf("hin"),
        )
    private val tamil =
        Regional(
            nowPlaying("கண்ணான கண்ணே", "Sid Sriram"),
            mapOf(SourceId.MUSICBRAINZ to listOf("“கண்ணான கண்ணே” was released in 2019.")),
        )
    private val kpop =
        Regional(
            nowPlaying("잠시", "BTS", "BE"),
            mapOf(SourceId.MUSICBRAINZ to listOf("“잠시” was written by RM.")),
        )
    private val sertanejo =
        Regional(
            nowPlaying("Evidências", "Chitãozinho & Xororó"),
            mapOf(
                SourceId.MUSICBRAINZ to listOf("“Evidências” was written by José Augusto and Paulo Sérgio Valle."),
                SourceId.WIKIPEDIA to listOf("Chitãozinho & Xororó are a Brazilian duo."),
            ),
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec-evid",
                    lyrics = WorkLyrics(languages = listOf("por")),
                ),
        )
    private val polish =
        Regional(
            nowPlaying("Kocham cię, kochanie moje", "Maanam"),
            mapOf(SourceId.MUSICBRAINZ to listOf("“Kocham cię, kochanie moje” was released in 1984.")),
        )

    private var playing: Regional = bollywood

    private val englishAdapters =
        listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.WEB).map { source ->
            FakeAdapter(source, verifies = false) { _, _ ->
                val texts = playing.english[source].orEmpty()
                val scope = if (source == SourceId.WEB) Scope.WEB else Scope.SONG
                val drafts = texts.map { EvidenceDraft(source, scope, it, "https://example.org/$source", RETRIEVED) }
                SourceResult(if (texts.isEmpty()) Outcome.None else Outcome.SongArticle, drafts, Duration.ZERO)
            }
        }

    /** A lookup in a language, answering one line per scope it is given, after [delay], noting each query. */
    private class FakeLanguageAdapter(
        override val id: SourceId,
        private val wait: Duration = Duration.ZERO,
        private val texts: (LanguageQuery) -> List<Pair<Scope, String>>,
    ) : LanguageAdapter {
        val queries = mutableListOf<LanguageQuery>()
        val items = mutableListOf<LanguageItems>()

        override suspend fun lookup(
            query: LanguageQuery,
            items: LanguageItems,
        ): SourceResult {
            queries += query
            this.items += items
            delay(wait)
            val host = "https://${query.language.host}/wiki/"
            val drafts =
                texts(query).mapIndexed { i, (scope, text) ->
                    EvidenceDraft(id, scope, text, "$host$i", RETRIEVED, language = query.language.code)
                }
            return SourceResult(if (drafts.isEmpty()) Outcome.None else Outcome.SongArticle, drafts, Duration.ZERO)
        }
    }

    private val wikipedia =
        FakeLanguageAdapter(SourceId.WIKIPEDIA, wait = 2.seconds) { query ->
            when (query.language.code) {
                "hi" ->
                    listOf(
                        Scope.SONG to "तुम ही हो 2013 की फिल्म आशिकी 2 का एक गाना है।",
                        Scope.RELEASE to "आशिक़ी 2 मोहित सूरी द्वारा निर्देशित 2013 की फ़िल्म है।",
                    )
                "ta" -> listOf(Scope.SONG to "கண்ணான கண்ணே 2019 இல் வெளியான பாடல்.")
                "ko" -> listOf(Scope.RELEASE to "《BE》는 방탄소년단의 앨범이다.")
                "pt" -> listOf(Scope.SONG to "Evidências é uma canção de José Augusto e Paulo Sérgio Valle.")
                else -> emptyList()
            }
        }

    private val web =
        FakeLanguageAdapter(SourceId.WEB) { query ->
            listOf(Scope.WEB to "${query.track.title} (${query.language.name}).")
        }

    private class FakeSearch(
        override val wikipedia: LanguageAdapter?,
        override val web: LanguageAdapter?,
        private val regional: () -> Regional,
    ) : LanguageSearch {
        var workAsked = 0
        var releasesAsked = 0
        var itemsAsked = 0

        override suspend fun workLanguages(resolution: Resolution): Lookup<List<String>> {
            workAsked++
            return Lookup.Found(regional().wikidata)
        }

        override suspend fun releaseLanguages(resolution: Resolution): Lookup<List<String>> {
            releasesAsked++
            return Lookup.Found(regional().releases)
        }

        override suspend fun items(query: LanguageQuery): LanguageItems {
            itemsAsked++
            return LanguageItems(song = LocalName("Q1", "तुम ही हो", "तुम ही हो"))
        }
    }

    private val search = FakeSearch(wikipedia, web) { playing }
    private val provider = FakeProvider()
    private val resolver = FakeResolver()
    private val cache = LanguageMemoryCache()

    private var settings =
        Settings(aiProvider = ProviderId.ANTHROPIC, trackLanguageSearch = TrackLanguageSearch.IF_NEEDED)

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter> = englishAdapters,
        languageSearch: LanguageSearch? = search,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = adapters,
                resolver = resolver,
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
                languageSearch = languageSearch,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private suspend fun Pipeline.play(
        regional: Regional,
        write: Boolean = false,
    ): TrackState {
        playing = regional
        resolver.resolution = regional.resolution
        return run(regional.nowPlaying, write)
    }

    private fun TrackState.lines(): List<String> =
        evidence!!.lines.map { "${it.id} ${it.source.tag} ${it.scope.key} ${it.language ?: "-"}" }

    @Test
    fun `five regional tracks, thin in English, before and after`() =
        runTest {
            val before = mutableMapOf<String, List<String>>()
            val after = mutableMapOf<String, List<String>>()
            for (track in listOf(bollywood, tamil, kpop, sertanejo, polish)) {
                settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
                before[track.nowPlaying.title] = pipeline().play(track).lines()
                settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.IF_NEEDED)
                after[track.nowPlaying.title] = pipeline().play(track).lines()
            }
            assertEquals(
                mapOf(
                    "Tum Hi Ho" to listOf("MB1 MB song -", "P1 P release -"),
                    "கண்ணான கண்ணே" to listOf("MB1 MB song -"),
                    "잠시" to listOf("MB1 MB song -", "P1 P release -"),
                    "Evidências" to listOf("MB1 MB song -", "W1 W song -"),
                    "Kocham cię, kochanie moje" to listOf("MB1 MB song -"),
                ),
                before,
            )
            assertEquals(
                mapOf(
                    // The tracklists' recorded Hindi names it: the song's and the film's articles, and the web.
                    "Tum Hi Ho" to
                        listOf("MB1 MB song -", "W1 W song hi", "W2 W release hi", "S1 S web hi", "P1 P release -"),
                    // Tamil script names Tamil.
                    "கண்ணான கண்ணே" to listOf("MB1 MB song -", "W1 W song ta", "S1 S web ta"),
                    // Hangul names Korean: the album's article.
                    "잠시" to listOf("MB1 MB song -", "W1 W release ko", "S1 S web ko", "P1 P release -"),
                    // MusicBrainz's lyrics language names Portuguese; the English lines keep their ids.
                    "Evidências" to listOf("MB1 MB song -", "W1 W song -", "W2 W song pt", "S1 S web pt"),
                    // Latin letters and nothing recorded: no language, no search.
                    "Kocham cię, kochanie moje" to listOf("MB1 MB song -"),
                ),
                after,
            )
            assertEquals(listOf("hi", "ta", "ko", "pt"), wikipedia.queries.map { it.language.code })
        }

    @Test
    fun `If needed searches only while the English sources give fewer than four lines about the track`() =
        runTest {
            val three = (1..3).map { "Fact $it about “Tum Hi Ho”." }
            val thin = Regional(bollywood.nowPlaying, mapOf(SourceId.MUSICBRAINZ to three), releases = listOf("hin"))
            assertEquals("hi", pipeline().play(thin).inLanguage?.language)

            val enough =
                Regional(
                    nowPlaying("Sun Raha Hai", "Ankit Tiwari", "Aashiqui 2"),
                    mapOf(SourceId.MUSICBRAINZ to three, SourceId.WEB to listOf("A page.")),
                    releases = listOf("hin"),
                )
            val state = pipeline().play(enough)
            assertNull(state.inLanguage)
            assertEquals(1, wikipedia.queries.size)
            assertEquals(1, search.releasesAsked, "nothing asked for a track with enough of its own")
        }

    @Test
    fun `an artist's lines do not count as the track's own`() =
        runTest {
            val adapters =
                englishAdapters +
                    FakeAdapter(SourceId.LASTFM, verifies = false) { _, _ ->
                        val lines = (1..5).map { line(SourceId.LASTFM, "Artist fact $it.", Scope.ARTIST) }
                        SourceResult(Outcome.ArtistArticle, lines, Duration.ZERO)
                    }
            assertEquals("hi", pipeline(adapters).play(bollywood).inLanguage?.language)
        }

    @Test
    fun `Always searches a track with enough of its own, and Off never`() =
        runTest {
            val rich =
                Regional(
                    bollywood.nowPlaying,
                    mapOf(SourceId.MUSICBRAINZ to (1..6).map { "Fact $it." }),
                    releases = listOf("hin"),
                )
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            assertEquals("hi", pipeline().play(rich).inLanguage?.language)
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            assertNull(pipeline().play(bollywood).inLanguage)
            assertEquals(1, wikipedia.queries.size)
        }

    @Test
    fun `each lookup obeys its own source's switch`() =
        runTest {
            settings = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WIKIPEDIA)
            val noWikipedia = pipeline().play(bollywood)
            assertEquals(setOf(SourceId.WEB), noWikipedia.inLanguage?.sources?.keys)
            // Wikidata goes with Wikipedia's switch: no names asked of it, and its language hint none.
            assertEquals(0, search.itemsAsked)
            assertEquals(0, search.workAsked)

            settings = Settings(aiProvider = ProviderId.ANTHROPIC, webEngines = emptySet())
            val noWeb = pipeline().play(bollywood)
            assertEquals(setOf(SourceId.WIKIPEDIA), noWeb.inLanguage?.sources?.keys)

            settings = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WIKIPEDIA)
            val pipeline = pipeline()
            val events = recording(pipeline)
            val neither = pipeline.play(bollywood)
            assertNull(neither.inLanguage)
            assertTrue(events.none { it is PipelineEvent.LanguageFound })
        }

    @Test
    fun `English, or no language found, searches nothing`() =
        runTest {
            val english =
                Regional(
                    nowPlaying("Sundown", "Gordon Lightfoot"),
                    mapOf(SourceId.MUSICBRAINZ to listOf("“Sundown” was released in 1974.")),
                    resolution =
                        Resolution(
                            outcome = Outcome.SongArticle,
                            recordingMbid = "rec",
                            lyrics = WorkLyrics(languages = listOf("eng")),
                        ),
                    releases = listOf("eng"),
                )
            val pipeline = pipeline()
            val events = recording(pipeline)
            assertNull(pipeline.play(english).inLanguage)
            assertNull(pipeline.play(polish).inLanguage)
            assertEquals(0, wikipedia.queries.size + web.queries.size)
            assertEquals(
                listOf(null, null),
                events.filterIsInstance<PipelineEvent.LanguageFound>().map { it.language },
            )
        }

    @Test
    fun `the insight waits for the search in the language, one call with its lines marked`() =
        runTest {
            val state = pipeline().play(bollywood, write = true)
            val request = provider.requests.single()
            assertTrue("[W1] (Wikipedia, song, Hindi) तुम ही हो 2013 की फिल्म आशिकी 2 का एक गाना है।" in request.prompt)
            assertTrue("[S1] (Web search, web, Hindi) " in request.prompt)
            assertTrue("translate what you use of it into English" in request.system.orEmpty())
            assertTrue(state.insight is InsightState.Written)
        }

    @Test
    fun `the song's insight is written from the same evidence, the lines in the language with it`() =
        runTest {
            val state = pipeline().play(bollywood)
            val items = ItemInsights(null, cache, JvmHasher, NameMatcher(JvmAccentFolder))
            val song = items.evidence(InsightItem.SONG, state, settings)!!.evidence
            assertEquals(listOf("hi", "hi", "hi"), song.lines.mapNotNull { it.language })
            assertEquals(state.evidence!!.fingerprint, items.ownEvidence(state, settings)!!.fingerprint)
        }

    @Test
    fun `each lookup is kept under the track's key and the language, and read back on a replay`() =
        runTest {
            pipeline().play(bollywood)
            assertEquals(
                setOf(SourceId.WIKIPEDIA to "hi", SourceId.WEB to "hi"),
                cache.languageResults.keys
                    .map {
                        it.first to
                            it.second
                    }.toSet(),
            )
            val again = pipeline().play(bollywood)
            assertEquals(1, wikipedia.queries.size, "read from the cache")
            assertEquals(1, search.itemsAsked)
            assertEquals(
                listOf("W1 W song hi", "W2 W release hi", "S1 S web hi"),
                again.lines().filter { it.endsWith("hi") },
            )
            assertTrue(
                again.inLanguage!!
                    .sources.values
                    .all { (it as SourceState.Done).cached },
            )
        }

    @Test
    fun `a web line in the language whose page the English search quoted is not taken twice`() =
        runTest {
            val sameUrl =
                FakeLanguageAdapter(SourceId.WEB) { listOf(Scope.WEB to "Tum Hi Ho is a song.") }
            val withPage =
                Regional(
                    bollywood.nowPlaying,
                    mapOf(SourceId.WEB to listOf("Tum Hi Ho is a song.")),
                    releases = listOf("hin"),
                )
            val pageSearch =
                FakeSearch(
                    null,
                    object : LanguageAdapter {
                        override val id = SourceId.WEB

                        override suspend fun lookup(
                            query: LanguageQuery,
                            items: LanguageItems,
                        ): SourceResult =
                            sameUrl.lookup(query, items).let { result ->
                                result.copy(
                                    evidence =
                                        result.evidence.map {
                                            it.copy(
                                                url = "https://example.org/${SourceId.WEB}",
                                            )
                                        },
                                )
                            }
                    },
                ) { withPage }
            val state = pipeline(languageSearch = pageSearch).play(withPage)
            assertEquals(listOf("S1 S web -", "P1 P release -"), state.lines())
        }

    @Test
    fun `a track served from the cache is resolved for the search`() =
        runTest {
            pipeline().play(sertanejo)
            assertEquals(1, resolver.calls)
            cache.languageResults.clear()
            // The English sources come from the cache now: the run resolves for the search alone.
            val state = pipeline().play(sertanejo)
            assertEquals(2, resolver.calls)
            assertEquals("pt", state.inLanguage?.language)
        }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }
}

/** [MemoryCache], keeping the lookups in a language too, by source, language and track. */
private class LanguageMemoryCache : PipelineCache by MemoryCache() {
    private val english = MemoryCache()
    val languageResults = mutableMapOf<Triple<SourceId, String, String>, SourceResult>()

    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? = english.result(adapter, track, duration)

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) = english.keep(adapter, track, duration, result)

    override suspend fun result(
        adapter: LanguageAdapter,
        language: String,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? = languageResults[Triple(adapter.id, language, track.lookupKey)]

    override suspend fun keep(
        adapter: LanguageAdapter,
        language: String,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        languageResults[Triple(adapter.id, language, track.lookupKey)] = result
    }
}
