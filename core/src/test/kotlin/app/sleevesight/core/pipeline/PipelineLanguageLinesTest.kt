package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lines the English sources found about the track as the weakest hint of its language (#987),
 * through the pipeline: after the recorded hints, outweighed by them, and kept as none, read again.
 */
class PipelineLanguageLinesTest {
    // A title in Latin letters with no recorded language, and two Devanagari captions about it that the
    // English web search found (#987).
    private val captioned =
        Regional(
            nowPlaying("Tum Se Hi", "Pritam", "Jab We Met"),
            mapOf(
                SourceId.MUSICBRAINZ to listOf("“Tum Se Hi” was written by Irshad Kamil."),
                SourceId.WEB to listOf("यह गीत 2007 की एक फ़िल्म का है।", "मोहित चौहान ने यह गीत गाया।", "A page."),
            ),
        )

    private var playing: Regional = captioned

    private val englishAdapters =
        listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.WEB).map { source ->
            FakeAdapter(source, verifies = false) { _, _ ->
                val texts = playing.english[source].orEmpty()
                val scope = if (source == SourceId.WEB) Scope.WEB else Scope.SONG
                val drafts = texts.map { EvidenceDraft(source, scope, it, "https://example.org/$source", RETRIEVED) }
                SourceResult(if (texts.isEmpty()) Outcome.None else Outcome.SongArticle, drafts, Duration.ZERO)
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
                "ko" -> listOf(Scope.RELEASE to "《BE》는 방탄소년단의 앨범이다.", Scope.ARTIST to "방탄소년단은 대한민국의 그룹이다.")
                "pt" -> listOf(Scope.SONG to "Evidências é uma canção de José Augusto e Paulo Sérgio Valle.")
                else -> emptyList()
            }
        }

    private val web =
        FakeLanguageAdapter(SourceId.WEB) { query ->
            listOf(Scope.WEB to "${query.track.title} (${query.language.name}).")
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

    @Test
    fun `lines about the track in a script that names a language are the weakest hint, kept as none`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            assertEquals("hi", pipeline().play(captioned).inLanguage?.language)
            assertEquals(1, search.releasesAsked, "asked after the recorded hints")
            assertEquals(listOf(""), cache.decided.values.toList(), "left to the lines, which are read again")
            assertEquals("hi", pipeline().play(captioned).inLanguage?.language)
            assertEquals(1, search.releasesAsked, "a replay asks nothing")
        }

    @Test
    fun `under If needed a thin track named only by its lines is searched in their language`() =
        runTest {
            val thin = Regional(captioned.nowPlaying, captioned.english - SourceId.MUSICBRAINZ)
            assertEquals("hi", pipeline().play(thin).inLanguage?.language)
        }

    @Test
    fun `a title in a shared script takes the lines' language written in it, kept as none and read again`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val anime =
                Regional(
                    nowPlaying("夜空", "Hoshino"),
                    mapOf(SourceId.WEB to listOf("これは2020年に出た歌です。", "夜空はアニメの曲です。")),
                )
            assertEquals("ja", pipeline().play(anime).inLanguage?.language)
            assertEquals(listOf(""), cache.decided.values.toList())
            assertEquals("ja", pipeline().play(anime).inLanguage?.language)
            assertEquals(1, search.releasesAsked, "a replay asks nothing")
        }

    @Test
    fun `a recorded language outweighs the lines', English included`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val marathi = Regional(captioned.nowPlaying, captioned.english, releases = listOf("mar"))
            assertEquals("mr", pipeline().play(marathi).inLanguage?.language)
            cache.decided.clear()
            val english = Regional(captioned.nowPlaying, captioned.english, wikidata = listOf("Q1860"))
            assertNull(pipeline().play(english).inLanguage)
            assertEquals(listOf("en"), cache.decided.values.toList())
        }

    @Test
    fun `a kept none reads the lines again, so a source switched on since names the language`() =
        runTest {
            settings =
                settings.copy(
                    trackLanguageSearch = TrackLanguageSearch.ALWAYS,
                    enabledSources = settings.enabledSources - SourceSwitch.WEB,
                )
            assertNull(pipeline().play(captioned).inLanguage)
            assertEquals(listOf(""), cache.decided.values.toList())
            settings = settings.copy(enabledSources = settings.enabledSources + SourceSwitch.WEB)
            assertEquals("hi", pipeline().play(captioned).inLanguage?.language)
            assertEquals(1, search.releasesAsked, "read again with no request")
        }

    @Test
    fun `a look again that leaves the language to the lines replaces the language kept before`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val pipeline = pipeline()
            val first = pipeline.play(Regional(captioned.nowPlaying, captioned.english, releases = listOf("mar")))
            assertEquals("mr", first.inLanguage?.language)
            playing = captioned
            val gathered = Gathered(first.sources, first.resolution, inLanguage = first.inLanguage)
            val again =
                pipeline.run(
                    captioned.nowPlaying,
                    RunControl.Unattended(false),
                    lookAgain = LookAgain(gathered),
                )
            assertEquals("hi", again.inLanguage?.language)
            assertEquals(listOf(""), cache.decided.values.toList())
            assertEquals("hi", pipeline().play(captioned).inLanguage?.language)
        }
}
