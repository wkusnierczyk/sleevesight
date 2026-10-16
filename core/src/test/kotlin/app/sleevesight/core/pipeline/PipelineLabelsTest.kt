package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The genre labels the pipeline assembles: the label sources switched on, combined (#84). */
class PipelineLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val carefree = nowPlaying("Carefree Highway", "Gordon Lightfoot")
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // Tags written for the test, not MusicBrainz's; each request takes MusicBrainz's one second.
    private val tagRequests = mutableListOf<String>()
    private val musicBrainzLabels =
        MusicBrainzLabels(
            { mbid ->
                tagRequests += mbid
                delay(1.seconds)
                Lookup.Found(listOf("country", "folk", "seen live"))
            },
            vocabulary,
        )

    /** MusicBrainz as the real adapter answers: its lines, and the resolved artist for the labels. */
    private fun musicBrainz() =
        FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
            SourceResult(
                Outcome.SongArticle,
                listOf(line(SourceId.MUSICBRAINZ, "A recording by Gordon Lightfoot.")),
                Duration.ZERO,
                labelArtists = listOf(MB_ARTIST),
            )
        }

    private fun lastFm() =
        FakeAdapter(SourceId.LASTFM) { _, _ ->
            SourceResult(
                Outcome.ArtistArticle,
                listOf(line(SourceId.LASTFM, "Gordon Lightfoot has 1 listener.")),
                Duration.ZERO,
                genres = vocabulary.labelsFor(listOf("folk", "singer-songwriter"), LASTFM_PAGE),
            )
        }

    private fun TestScope.pipeline(
        provider: FakeProvider,
        cache: PipelineCache = PipelineCache.None,
        adapters: List<SourceAdapter> = listOf(musicBrainz(), lastFm()),
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = adapters,
            resolver = FakeResolver(),
            hasher = JvmHasher,
            ai = AiAccess(provider, Secret("sk-test")),
            cache = cache,
            musicBrainzLabels = musicBrainzLabels,
        ),
        PipelineTime(timeSource = testTimeSource),
    ) { settings }

    private suspend fun TestScope.track(
        nowPlaying: NowPlaying = sundown,
        provider: FakeProvider = FakeProvider(),
        cache: PipelineCache = PipelineCache.None,
    ): TrackState = pipeline(provider, cache).run(nowPlaying, write = true)

    @Test
    fun `both sources' labels, Last fm's first, credited to each source that gave them`() =
        runTest {
            val state = track()
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.map { it.genre.label })
            assertEquals(
                listOf(
                    listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
                    listOf(LabelSource.LASTFM),
                    listOf(LabelSource.MUSICBRAINZ),
                ),
                state.genres.map { label -> label.credits.map { it.source } },
            )
            assertEquals(listOf(true, true, false), state.genres.map { it.opensCard })
        }

    @Test
    fun `the insight does not wait for the tags, which join the labels when they arrive`() =
        runTest {
            var askedAt: Long? = null
            var labelsThen: List<String>? = null
            lateinit var pipeline: Pipeline
            val provider =
                FakeProvider(
                    reply = { _ ->
                        askedAt = testScheduler.currentTime
                        labelsThen =
                            pipeline.state.value
                                ?.genres
                                ?.map { it.genre.label }
                        FakeProvider.done("It is a song [MB1].")
                    },
                )
            pipeline = pipeline(provider)
            val state = pipeline.run(sundown, write = true)

            assertEquals(0L, askedAt)
            assertEquals(listOf("Folk", "Singer-Songwriter"), labelsThen)
            assertIs<InsightState.Written>(state.insight)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.map { it.genre.label })
            assertEquals(1_000L, testScheduler.currentTime)
        }

    @Test
    fun `a second track by the same artist asks for no tags`() =
        runTest {
            track(sundown)
            val second = track(carefree)
            assertEquals(listOf(MB_ARTIST), tagRequests)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), second.genres.map { it.genre.label })
        }

    @Test
    fun `a track served from the cache gets MusicBrainz's labels too`() =
        runTest {
            val cache = MemoryCache()
            track(cache = cache)
            val resolver = FakeResolver()
            val cached =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(musicBrainz(), lastFm()),
                        resolver = resolver,
                        hasher = JvmHasher,
                        cache = cache,
                        musicBrainzLabels = musicBrainzLabels,
                    ),
                    PipelineTime(timeSource = testTimeSource),
                ) { settings }.run(sundown, write = false)
            assertEquals(0, resolver.calls)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), cached.genres.map { it.genre.label })
        }

    @Test
    fun `the labels are never evidence, so never in the prompt`() =
        runTest {
            val provider = FakeProvider()
            val state = track(provider = provider)
            val prompt = provider.requests.single().prompt
            assertFalse("country" in prompt.lowercase(), prompt)
            assertFalse(state.evidence!!.lines.any { "country" in it.text.lowercase() })
        }

    @Test
    fun `with MusicBrainz labels switched off, no tags are asked for`() =
        runTest {
            settings = settings.copy(labelSources = setOf(LabelSource.LASTFM))
            val state = track()
            assertEquals(listOf("Folk", "Singer-Songwriter"), state.genres.map { it.genre.label })
            assertTrue(state.genres.all { label -> label.credits.map { it.source } == listOf(LabelSource.LASTFM) })
            assertIs<SourceState.Done>(state.sources[SourceId.MUSICBRAINZ])
            assertEquals(emptyList(), tagRequests)

            settings = settings.copy(labelSources = setOf(LabelSource.MUSICBRAINZ))
            assertEquals(listOf("Country", "Folk"), track().genres.map { it.genre.label })
        }

    @Test
    fun `a source off under Where to look gives no labels, whatever its label switch`() =
        runTest {
            settings = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.LASTFM)
            assertEquals(listOf("Country", "Folk"), track().genres.map { it.genre.label })

            tagRequests.clear()
            settings = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ)
            assertEquals(listOf("Folk", "Singer-Songwriter"), track().genres.map { it.genre.label })
            assertEquals(emptyList(), tagRequests)
        }

    private companion object {
        const val MB_ARTIST = "mb-gl"
        const val LASTFM_PAGE = "https://www.last.fm/music/Gordon+Lightfoot"
    }
}
