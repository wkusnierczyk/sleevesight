package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.LastFmLabels
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.genres.ReleaseTags
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
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.coroutines.cancellation.CancellationException
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
                labelArtists = listOf(ResolvedArtist("Gordon Lightfoot", MB_ARTIST)),
            )
        }

    private fun lastFm() =
        FakeAdapter(SourceId.LASTFM) { _, _ ->
            SourceResult(
                Outcome.ArtistArticle,
                listOf(line(SourceId.LASTFM, "Gordon Lightfoot has 1 listener.")),
                Duration.ZERO,
                genres =
                    vocabulary.labelsFor(
                        listOf("folk", "singer-songwriter"),
                        LASTFM_PAGE,
                        artist = "Gordon Lightfoot",
                    ),
            )
        }

    private fun TestScope.pipeline(
        provider: FakeProvider,
        cache: PipelineCache = PipelineCache.None,
        adapters: List<SourceAdapter> = listOf(musicBrainz(), lastFm()),
        labels: MusicBrainzLabels = musicBrainzLabels,
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = adapters,
            resolver = FakeResolver(),
            hasher = JvmHasher,
            ai = AiAccess(provider, Secret("sk-test")),
            cache = cache,
            musicBrainzLabels = labels,
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
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.all.map { it.genre.label })
            assertEquals(
                listOf(
                    listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
                    listOf(LabelSource.LASTFM),
                    listOf(LabelSource.MUSICBRAINZ),
                ),
                state.genres.all.map { label -> label.credits.map { it.source } },
            )
            assertEquals(listOf(true, true, false), state.genres.all.map { it.opensCard })
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
                                ?.all
                                ?.map { it.genre.label }
                        FakeProvider.done("It is a song [MB1].")
                    },
                )
            pipeline = pipeline(provider)
            val state = pipeline.run(sundown, write = true)

            assertEquals(0L, askedAt)
            assertEquals(listOf("Folk", "Singer-Songwriter"), labelsThen)
            assertIs<InsightState.Written>(state.insight)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.all.map { it.genre.label })
            assertEquals(1_000L, testScheduler.currentTime)
        }

    @Test
    fun `a second track by the same artist asks for no tags`() =
        runTest {
            track(sundown)
            val second = track(carefree)
            assertEquals(listOf(MB_ARTIST), tagRequests)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), second.genres.all.map { it.genre.label })
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
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), cached.genres.all.map { it.genre.label })
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
            assertEquals(listOf("Folk", "Singer-Songwriter"), state.genres.all.map { it.genre.label })
            assertTrue(state.genres.all.all { label -> label.credits.map { it.source } == listOf(LabelSource.LASTFM) })
            assertIs<SourceState.Done>(state.sources[SourceId.MUSICBRAINZ])
            assertEquals(emptyList(), tagRequests)

            settings = settings.copy(labelSources = setOf(LabelSource.MUSICBRAINZ))
            assertEquals(listOf("Country", "Folk"), track().genres.all.map { it.genre.label })
        }

    @Test
    fun `a source off under Sources gives no labels, whatever its label switch`() =
        runTest {
            settings = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.LASTFM)
            assertEquals(listOf("Country", "Folk"), track().genres.all.map { it.genre.label })

            tagRequests.clear()
            settings = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ)
            assertEquals(listOf("Folk", "Singer-Songwriter"), track().genres.all.map { it.genre.label })
            assertEquals(emptyList(), tagRequests)
        }

    @Test
    fun `a superseded track's tag request is cancelled with its run, and asked again for the next`() =
        runTest {
            var cancelled = 0
            val slow =
                MusicBrainzLabels(
                    { mbid ->
                        tagRequests += mbid
                        try {
                            delay(10.seconds)
                        } catch (e: CancellationException) {
                            cancelled++
                            throw e
                        }
                        Lookup.Found(listOf("country"))
                    },
                    vocabulary,
                )
            val pipeline = pipeline(FakeProvider(), labels = slow)
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            advanceTimeBy(Pipeline.DEBOUNCE + 1.seconds)
            assertEquals(listOf(MB_ARTIST), tagRequests)

            changes.value = TrackChange(carefree)
            advanceTimeBy(Pipeline.DEBOUNCE + 1.seconds)
            assertEquals(1, cancelled)
            // A cancelled request is not kept, so the next track by the artist asks again.
            assertEquals(listOf(MB_ARTIST, MB_ARTIST), tagRequests)

            // The follow loop runs in the background, which `advanceUntilIdle` does not wait for.
            advanceTimeBy(10.seconds)
            val state = pipeline.state.value!!
            assertEquals("Carefree Highway", state.normalized.title)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.all.map { it.genre.label })
            assertEquals(1, cancelled)
        }

    @Test
    fun `a superseded track's Last fm track and album requests are cancelled, and give the next track nothing`() =
        runTest {
            val asked = mutableListOf<String>()
            var cancelled = 0
            val slow =
                object : ReleaseTags {
                    suspend fun slowly(what: String): Lookup<List<String>> {
                        asked += what
                        try {
                            delay(10.seconds)
                        } catch (e: CancellationException) {
                            cancelled++
                            throw e
                        }
                        return Lookup.Found(listOf("soft rock"))
                    }

                    override suspend fun trackTags(
                        artist: String,
                        title: String,
                    ) = slowly("track $title")

                    override suspend fun albumTags(
                        artist: String,
                        album: String,
                    ) = slowly("album $album")

                    override fun trackPage(
                        artist: String,
                        title: String,
                    ) = null

                    override fun albumPage(
                        artist: String,
                        album: String,
                    ) = null
                }
            val pipeline =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(musicBrainz(), lastFm()),
                        resolver = FakeResolver(),
                        hasher = JvmHasher,
                        musicBrainzLabels = musicBrainzLabels,
                        lastFmLabels = LastFmLabels(slow, vocabulary),
                    ),
                    PipelineTime(timeSource = testTimeSource),
                ) { settings }
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            advanceTimeBy(Pipeline.DEBOUNCE + 1.seconds)
            assertEquals(listOf("track Sundown", "album Sundown"), asked)

            changes.value = TrackChange(carefree)
            advanceTimeBy(Pipeline.DEBOUNCE + 1.seconds)
            assertEquals(2, cancelled)
            assertEquals(listOf("track Sundown", "album Sundown", "track Carefree Highway"), asked)

            advanceTimeBy(10.seconds)
            val state = pipeline.state.value!!
            assertEquals("Carefree Highway", state.normalized.title)
            // Carefree Highway has no album: its track's label only, none of Sundown's album.
            assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ARTIST), state.genres.levels)
            assertEquals(2, cancelled)
        }

    @Test
    fun `labels switched off give none from a filled cache, and ask for no tags`() =
        runTest {
            val cache = MemoryCache()
            val filled = track(cache = cache)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), filled.genres.all.map { it.genre.label })
            tagRequests.clear()

            // Both caches hold labels now: Last.fm's result, with its labels and MusicBrainz's
            // artists, in the retrieval cache, and the artist's tags in memory.
            settings = settings.copy(labelSources = emptySet())
            val off = track(cache = cache)
            assertTrue(off.sources.values.all { (it as SourceState.Done).cached })
            assertTrue(off.genres.isEmpty)

            settings = settings.copy(labelSources = setOf(LabelSource.MUSICBRAINZ))
            assertEquals(listOf("Country", "Folk"), track(cache = cache).genres.all.map { it.genre.label })
            settings = settings.copy(labelSources = setOf(LabelSource.LASTFM))
            assertEquals(listOf("Folk", "Singer-Songwriter"), track(cache = cache).genres.all.map { it.genre.label })
            assertEquals(emptyList(), tagRequests)
        }

    /** Last.fm's track and album tags, written for the test; each request counted. */
    private class FakeReleaseTags(
        val track: Lookup<List<String>> = Lookup.Found(listOf("soft rock", "seen live")),
        val album: Lookup<List<String>> = Lookup.Found(listOf("folk rock", "1974")),
    ) : ReleaseTags {
        val asked = mutableListOf<String>()

        override suspend fun trackTags(
            artist: String,
            title: String,
        ): Lookup<List<String>> = track.also { asked += "track $artist / $title" }

        override suspend fun albumTags(
            artist: String,
            album: String,
        ): Lookup<List<String>> = this.album.also { asked += "album $artist / $album" }

        override fun trackPage(
            artist: String,
            title: String,
        ): String = "https://www.last.fm/music/x/_/y"

        override fun albumPage(
            artist: String,
            album: String,
        ): String = "https://www.last.fm/music/x/y"
    }

    private fun TestScope.withRelease(
        tags: FakeReleaseTags,
        adapters: List<SourceAdapter> = listOf(musicBrainz(), lastFm()),
        cache: PipelineCache = PipelineCache.None,
        labels: MusicBrainzLabels = musicBrainzLabels,
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = adapters,
            resolver = FakeResolver(),
            hasher = JvmHasher,
            cache = cache,
            musicBrainzLabels = labels,
            lastFmLabels = LastFmLabels(tags, vocabulary),
        ),
        PipelineTime(timeSource = testTimeSource),
    ) { settings }

    @Test
    fun `the track's and the album's labels are rows of their own, in the header's order`() =
        runTest {
            val tags = FakeReleaseTags()
            val state = withRelease(tags).run(sundown, write = false)
            assertEquals(
                listOf(
                    GenreLevel.TRACK to listOf("Soft Rock"),
                    GenreLevel.ARTIST to listOf("Folk", "Singer-Songwriter", "Country"),
                    GenreLevel.ALBUM to listOf("Folk Rock"),
                ),
                state.genres.rows.map { row -> row.level to row.labels.map { it.genre.label } },
            )
            assertEquals("Gordon Lightfoot", state.genres.rows[1].artist)
            assertEquals(listOf("track Gordon Lightfoot / Sundown", "album Gordon Lightfoot / Sundown"), tags.asked)
            // Folded, the album's show: the track's Last.fm tag agrees with no other level (#799).
            // Only an artist's label opens a genre card.
            assertEquals(listOf("Folk Rock"), state.genres.folded.map { it.label.genre.label })
            assertEquals(
                listOf(false, true, true, false, false),
                state.genres.rows
                    .flatMap { it.labels }
                    .map { it.opensCard },
            )
        }

    @Test
    fun `an album's tags are kept for its next track, a track without an album asks for none`() =
        runTest {
            val tags = FakeReleaseTags()
            val pipeline = withRelease(tags)
            pipeline.run(sundown, write = false)
            pipeline.run(nowPlaying("Carefree Highway", "Gordon Lightfoot", album = "Sundown"), write = false)
            val bare = pipeline.run(carefree, write = false)
            assertEquals(
                listOf(
                    "track Gordon Lightfoot / Sundown",
                    "album Gordon Lightfoot / Sundown",
                    "track Gordon Lightfoot / Carefree Highway",
                    "track Gordon Lightfoot / Carefree Highway",
                ),
                tags.asked,
            )
            assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ARTIST), bare.genres.levels)
        }

    @Test
    fun `Last fm's track and album tags are asked for only once it shows an artist, and only with its labels on`() =
        runTest {
            val rejected =
                FakeAdapter(
                    SourceId.LASTFM,
                ) { _, _ -> SourceResult(Outcome.Rejected("another artist"), emptyList(), Duration.ZERO) }
            val tags = FakeReleaseTags()
            val state = withRelease(tags, adapters = listOf(musicBrainz(), rejected)).run(sundown, write = false)
            assertEquals(emptyList(), tags.asked)
            assertTrue(state.genres.oneLevel)

            settings = settings.copy(labelSources = setOf(LabelSource.MUSICBRAINZ))
            withRelease(tags).run(sundown, write = false)
            assertEquals(emptyList(), tags.asked)
        }

    @Test
    fun `a collaborator Last fm shows does not let it ask under a primary artist it rejected`() =
        runTest {
            val collaborator =
                FakeAdapter(SourceId.LASTFM) { _, _ ->
                    SourceResult(
                        Outcome.ArtistArticle,
                        listOf(line(SourceId.LASTFM, "Ian Tyson has 1 listener.")),
                        Duration.ZERO,
                        rejections =
                            listOf(
                                Rejection("Gordon Lightfoot", "a different MusicBrainz artist", Scope.ARTIST),
                            ),
                    )
                }
            val tags = FakeReleaseTags()
            withRelease(tags, adapters = listOf(musicBrainz(), collaborator))
                .run(nowPlaying("Sundown", "Gordon Lightfoot, Ian Tyson", album = "Sundown"), write = false)
            assertEquals(emptyList(), tags.asked)
        }

    @Test
    fun `an artist's labels kept under another spelling of its key join the credited artist's row`() =
        runTest {
            val accented =
                FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                    SourceResult(
                        Outcome.SongArticle,
                        listOf(line(SourceId.MUSICBRAINZ, "A recording by Gordon Lightfoot.")),
                        Duration.ZERO,
                        labelArtists = listOf(ResolvedArtist("GORDON LIGHTFÓOT", MB_ARTIST)),
                    )
                }
            val state = withRelease(FakeReleaseTags(), listOf(accented, lastFm())).run(sundown, write = false)
            val artists = state.genres.rows.filter { it.level == GenreLevel.ARTIST }
            assertEquals(listOf("Gordon Lightfoot"), artists.map { it.artist })
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), artists.single().labels.map { it.genre.label })
        }

    @Test
    fun `a failed track or album request gives no row, and the rest still show`() =
        runTest {
            val tags = FakeReleaseTags(track = Lookup.Failed("Offline"), album = Lookup.NotFound)
            val state = withRelease(tags).run(sundown, write = false)
            assertEquals(listOf(GenreLevel.ARTIST), state.genres.levels)
        }

    @Test
    fun `MusicBrainz's recording tags give the track's labels with no request, also from the cache`() =
        runTest {
            val tagged =
                FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                    SourceResult(
                        Outcome.SongArticle,
                        listOf(line(SourceId.MUSICBRAINZ, "A recording by Gordon Lightfoot.")),
                        Duration.ZERO,
                        labelArtists = listOf(ResolvedArtist("Gordon Lightfoot", MB_ARTIST)),
                        recordingTags = RecordingTags("mb-sundown", listOf("soft rock", "folk")),
                    )
                }
            val cache = MemoryCache()
            for (served in listOf(false, true)) {
                val state = withRelease(FakeReleaseTags(), listOf(tagged, lastFm()), cache).run(sundown, write = false)
                assertEquals(served, (state.sources[SourceId.MUSICBRAINZ] as SourceState.Done).cached)
                val track = state.genres.rows.first { it.level == GenreLevel.TRACK }
                assertEquals(listOf("Soft Rock", "Folk"), track.labels.map { it.genre.label })
                assertEquals(
                    listOf(listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ), listOf(LabelSource.MUSICBRAINZ)),
                    track.labels.map { label -> label.credits.map { it.source } },
                )
                assertEquals(
                    "https://musicbrainz.org/recording/mb-sundown",
                    track.labels[1]
                        .credits
                        .single()
                        .url,
                )
            }
        }

    private companion object {
        const val MB_ARTIST = "mb-gl"
        const val LASTFM_PAGE = "https://www.last.fm/music/Gordon+Lightfoot"
    }
}
