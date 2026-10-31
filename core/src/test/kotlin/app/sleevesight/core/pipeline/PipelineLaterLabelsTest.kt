package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.LastFmLabels
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.ReleaseTags
import app.sleevesight.core.model.NormalizedTrack
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
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The genre labels' requests after the evidence (#772): how they stand per source while asked, done
 * or failed, published with the assembled genres; the genres a run that asks again starts with; and
 * a *Retry* of a failed request, asked alone again.
 */
class PipelineLaterLabelsTest {
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
    fun `the requests after the evidence show as asked for until they answer, then done`() =
        runTest {
            // MusicBrainz's artist tags take a second; Last.fm's track and album tags answer at once.
            val pipeline = withRelease(FakeReleaseTags())
            val run = async { pipeline.run(sundown, write = false) }
            advanceTimeBy(500)
            assertEquals(
                mapOf(LabelSource.MUSICBRAINZ to LaterLabels.ASKING, LabelSource.LASTFM to LaterLabels.DONE),
                pipeline.state.value?.laterLabels,
            )
            val state = run.await()
            assertEquals(
                mapOf(LabelSource.MUSICBRAINZ to LaterLabels.DONE, LabelSource.LASTFM to LaterLabels.DONE),
                state.laterLabels,
            )
            assertEquals(
                listOf("Folk", "Singer-Songwriter", "Country"),
                state.genres.rows[1]
                    .labels
                    .map { it.genre.label },
            )
        }

    @Test
    fun `the requests after the evidence are asked for as soon as the evidence is in, before they may start`() =
        runTest {
            val pipeline = withRelease(FakeReleaseTags())
            val run = async { pipeline.run(sundown, write = false) }
            runCurrent()
            assertEquals(
                LaterLabels.ASKING,
                pipeline.state.value
                    ?.laterLabels
                    ?.get(LabelSource.MUSICBRAINZ),
            )
            run.await()
        }

    @Test
    fun `a request that finds nothing leaves its source done, one that fails or is not answered failed`() =
        runTest {
            val none = withRelease(FakeReleaseTags(track = Lookup.NotFound, album = Lookup.NotFound))
            assertEquals(LaterLabels.DONE, none.run(sundown, write = false).laterLabels[LabelSource.LASTFM])

            // Last.fm's album tags not reached; MusicBrainz's artist tags failing.
            val down = MusicBrainzLabels({ Lookup.Failed("network") }, vocabulary)
            val failing = withRelease(FakeReleaseTags(album = Lookup.Failed("network")), labels = down)
            val state = failing.run(sundown, write = false)
            assertEquals(
                mapOf(LabelSource.MUSICBRAINZ to LaterLabels.FAILED, LabelSource.LASTFM to LaterLabels.FAILED),
                state.laterLabels,
            )
            // The track's own Last.fm tags still show.
            assertEquals(listOf("Soft Rock"), state.genres.folded.map { it.label.genre.label })
        }

    @Test
    fun `a request that throws leaves its source failed`() =
        runTest {
            val broken = MusicBrainzLabels({ error("a bug") }, vocabulary)
            val state = withRelease(FakeReleaseTags(), labels = broken).run(sundown, write = false)
            assertEquals(LaterLabels.FAILED, state.laterLabels[LabelSource.MUSICBRAINZ])
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.LASTFM])
        }

    /** [MemoryCache], with each keep of a source's result held until [release]d. */
    private class HeldCache : PipelineCache by MemoryCache() {
        val release = CompletableDeferred<Unit>()

        override suspend fun keep(
            adapter: SourceAdapter,
            track: NormalizedTrack,
            duration: Duration?,
            result: SourceResult,
        ) = release.await()
    }

    @Test
    fun `the genres and their later requests are published together, after the last result is kept`() =
        runTest {
            val cache = HeldCache()
            val pipeline = withRelease(FakeReleaseTags(), cache = cache)
            val seen = mutableListOf<TrackState>()
            backgroundScope.launch { pipeline.state.collect { state -> state?.let { seen += it } } }
            val run = async { pipeline.run(sundown, write = false) }
            runCurrent()
            // Every source has landed, its result still being kept: no genre yet, and not assembled.
            val held = pipeline.state.value!!
            assertTrue(held.gathered)
            assertFalse(held.genresAssembled)
            assertTrue(held.genres.isEmpty)
            cache.release.complete(Unit)
            val state = run.await()
            assertTrue(state.genresAssembled)
            // No state is assembled without its later requests: none says none found too soon.
            val assembled = seen.filter { it.genresAssembled }
            assertTrue(assembled.isNotEmpty())
            assertTrue(assembled.all { it.laterLabels.keys == setOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM) })
            assertEquals(LaterLabels.ASKING, assembled.first().laterLabels[LabelSource.MUSICBRAINZ])
        }

    @Test
    fun `a run cancelled before its label requests start leaves none asked for`() =
        runTest {
            val pipeline = withRelease(FakeReleaseTags())
            lateinit var job: Job
            // Cancelled as the evidence is assembled: the requests are published as asked for, and
            // the coroutine that would ask them never starts.
            val control =
                object : RunControl by RunControl.Unattended(write = false) {
                    override fun assembled(evidence: EvidenceSet) = job.cancel()
                }
            job = launch { pipeline.run(sundown, control) }
            job.join()
            assertTrue(job.isCancelled)
            val state = pipeline.state.value!!
            assertTrue(state.genresAssembled)
            assertFalse(LaterLabels.ASKING in state.laterLabels.values, "${state.laterLabels}")
        }

    /** Last.fm's track tags as [track] says at the time, its album's none. */
    private class ChangingReleaseTags(
        var track: Lookup<List<String>> = Lookup.Found(listOf("soft rock")),
    ) : ReleaseTags {
        override suspend fun trackTags(
            artist: String,
            title: String,
        ): Lookup<List<String>> = track

        override suspend fun albumTags(
            artist: String,
            album: String,
        ): Lookup<List<String>> = Lookup.NotFound

        override fun trackPage(
            artist: String,
            title: String,
        ): String? = null

        override fun albumPage(
            artist: String,
            album: String,
        ): String? = null
    }

    // A control that holds each run's lookups until [gate] opens.
    private fun held(gate: CompletableDeferred<Unit>) =
        object : RunControl by RunControl.Unattended(write = false) {
            override suspend fun await(step: RunStep) {
                if (step == RunStep.LOOKUPS) gate.await()
            }
        }

    private fun TestScope.changing(tags: ChangingReleaseTags) =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(musicBrainz(), lastFm()),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                musicBrainzLabels = musicBrainzLabels,
                lastFmLabels = LastFmLabels(tags, vocabulary),
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private fun labelsOf(state: TrackState?) =
        state
            ?.genres
            ?.all
            ?.map { it.genre.label }
            .orEmpty()

    @Test
    fun `a run that looks again shows the genres shown before while it asks, then replaces them with its own`() =
        runTest {
            val tags = ChangingReleaseTags()
            val pipeline = changing(tags)
            val first = pipeline.run(sundown, write = false)
            assertTrue("Soft Rock" in labelsOf(first))

            // Looked up afresh, Last.fm no longer tags the track.
            tags.track = Lookup.NotFound
            val gate = CompletableDeferred<Unit>()
            val again =
                async {
                    pipeline.run(
                        sundown,
                        held(gate),
                        lookAgain = LookAgain(Gathered(first.sources, first.resolution)),
                    )
                }
            runCurrent()
            val asking = pipeline.state.value!!
            assertFalse(asking.genresAssembled)
            assertEquals(first.genres, asking.genres)

            gate.complete(Unit)
            val state = again.await()
            assertTrue(state.genresAssembled)
            // Its own, not merged with those before: the track's tag is gone.
            assertFalse("Soft Rock" in labelsOf(state), "${labelsOf(state)}")
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), labelsOf(state))
        }

    @Test
    fun `a run asking a source again keeps the genres shown before while it asks`() =
        runTest {
            val pipeline = changing(ChangingReleaseTags())
            val first = pipeline.run(sundown, write = false)
            // As a *Retry* of MusicBrainz asks it: the run looks it up again, the rest kept.
            val unreached =
                SourceState.Done(
                    SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO),
                    cached = false,
                )
            val before = Gathered(first.sources + (SourceId.MUSICBRAINZ to unreached), first.resolution)
            val gate = CompletableDeferred<Unit>()
            val retry = before.asking(setOf(SourceId.MUSICBRAINZ))
            val again = async { pipeline.run(sundown, held(gate), again = retry) }
            runCurrent()
            assertEquals(first.genres, pipeline.state.value?.genres)
            gate.complete(Unit)
            again.await()
        }

    @Test
    fun `a new track starts with no genres, even one run as asked again`() =
        runTest {
            val pipeline = changing(ChangingReleaseTags())
            val first = pipeline.run(sundown, write = false)
            assertTrue(labelsOf(first).isNotEmpty())
            // Asked again with an earlier run's sources (MusicBrainz's to ask), but another track.
            val unreached =
                SourceState.Done(
                    SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO),
                    cached = false,
                )
            val before = Gathered(first.sources + (SourceId.MUSICBRAINZ to unreached), first.resolution)
            val gate = CompletableDeferred<Unit>()
            val next = async { pipeline.run(carefree, held(gate), again = before) }
            runCurrent()
            assertEquals(
                carefree.title,
                pipeline.state.value
                    ?.nowPlaying
                    ?.title,
            )
            assertTrue(
                pipeline.state.value!!
                    .genres.isEmpty,
            )
            gate.complete(Unit)
            next.await()
        }

    @Test
    fun `a Retry of a failed label request asks it alone again, and keeps every other source as it was`() =
        runTest {
            // MusicBrainz's artist tags fail at first, then answer; Last.fm's track tags answer.
            var artistTags: Lookup<List<String>> = Lookup.Failed("network")
            var tagAsks = 0
            val artists =
                MusicBrainzLabels(
                    {
                        tagAsks++
                        artistTags
                    },
                    vocabulary,
                )
            val releaseTags = ChangingReleaseTags()
            var releaseAsks = 0
            val counted =
                object : ReleaseTags by releaseTags {
                    override suspend fun trackTags(
                        artist: String,
                        title: String,
                    ): Lookup<List<String>> = releaseTags.trackTags(artist, title).also { releaseAsks++ }
                }
            val mb = musicBrainz()
            val lf = lastFm()
            val resolver = FakeResolver()
            val pipeline =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(mb, lf),
                        resolver = resolver,
                        hasher = JvmHasher,
                        musicBrainzLabels = artists,
                        lastFmLabels = LastFmLabels(counted, vocabulary),
                    ),
                    PipelineTime(timeSource = testTimeSource),
                ) { settings }
            val failed = mutableListOf<Set<SourceId>>()
            val reporting =
                object : RunControl by RunControl.Unattended(write = false) {
                    override fun labelsFailed(sources: Set<SourceId>) {
                        failed += sources
                    }
                }
            val first = pipeline.run(sundown, reporting)
            assertEquals(LaterLabels.FAILED, first.laterLabels[LabelSource.MUSICBRAINZ])
            assertEquals(listOf(setOf(SourceId.MUSICBRAINZ)), failed)
            val before = Gathered(first.sources, first.resolution, labelsFailed = failed.single())
            assertTrue(SourceId.MUSICBRAINZ in before.retryable)
            val retry = before.asking(setOf(SourceId.MUSICBRAINZ))
            // Its lookup is kept, not made again: only its label requests are.
            assertEquals(emptySet(), retry.asked)
            assertEquals(setOf(SourceId.MUSICBRAINZ), retry.labelsAsked)

            artistTags = Lookup.Found(listOf("country"))
            val lookups = mb.asked.size + lf.asked.size
            val resolutions = resolver.calls
            val state = pipeline.run(sundown, RunControl.Unattended(write = false), again = retry)
            // MusicBrainz's artist tags asked again; no lookup, no resolution, no Last.fm request.
            assertEquals(2, tagAsks)
            assertEquals(lookups, mb.asked.size + lf.asked.size)
            assertEquals(resolutions, resolver.calls)
            assertEquals(1, releaseAsks)
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.MUSICBRAINZ])
            assertFalse(LaterLabels.FAILED in state.laterLabels.values)
            // Last.fm's track label kept from the run before, MusicBrainz's artist label now in.
            assertTrue("Soft Rock" in labelsOf(state), "${labelsOf(state)}")
            assertTrue("Country" in labelsOf(state), "${labelsOf(state)}")
        }

    @Test
    fun `with no request after the evidence, no source is said to be asked`() =
        runTest {
            settings = settings.copy(labelSources = emptySet())
            assertEquals(emptyMap(), withRelease(FakeReleaseTags()).run(sundown, write = false).laterLabels)
        }

    // A pipeline whose MusicBrainz artist tags answer with [artistTags] (each call counted in
    // [tagAsks]) and whose Last.fm track tags with [releaseTags]'s.
    private class Counted(
        val artistTags: suspend (Int) -> Lookup<List<String>>,
    ) {
        var tagAsks = 0
    }

    private fun TestScope.counting(
        counted: Counted,
        releaseTags: ReleaseTags,
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = listOf(musicBrainz(), lastFm()),
            resolver = FakeResolver(),
            hasher = JvmHasher,
            musicBrainzLabels = MusicBrainzLabels({ counted.artistTags(++counted.tagAsks) }, vocabulary),
            lastFmLabels = LastFmLabels(releaseTags, vocabulary),
        ),
        PipelineTime(timeSource = testTimeSource),
    ) { settings }

    // A control that records the label requests a run reports failed.
    private class Reporting : RunControl by RunControl.Unattended(write = false) {
        val failed = mutableListOf<Set<SourceId>>()

        override fun labelsFailed(sources: Set<SourceId>) {
            failed += sources
        }
    }

    @Test
    fun `a Retry while another source's label requests are unanswered asks those again too`() =
        runTest {
            // MusicBrainz's artist tags held on the first run; Last.fm's track tags failing.
            val gate = CompletableDeferred<Unit>()
            val counted =
                Counted { call ->
                    if (call == 1) gate.await()
                    Lookup.Found(listOf("country"))
                }
            val pipeline = counting(counted, ChangingReleaseTags(Lookup.Failed("network")))
            val first = async { pipeline.run(sundown, write = false) }
            runCurrent()
            val asking = pipeline.state.value!!
            assertEquals(LaterLabels.ASKING, asking.laterLabels[LabelSource.MUSICBRAINZ])
            assertEquals(LaterLabels.FAILED, asking.laterLabels[LabelSource.LASTFM])

            // *Retry* of Last.fm's label requests while MusicBrainz's are still out.
            val retry =
                Gathered(asking.sources, asking.resolution, labelsFailed = setOf(SourceId.LASTFM))
                    .asking(setOf(SourceId.LASTFM))
            val again = async { pipeline.run(sundown, RunControl.Unattended(write = false), again = retry) }
            first.cancel()
            val state = again.await()
            // MusicBrainz's artist tags asked again, never answered before, and in.
            assertEquals(2, counted.tagAsks)
            assertTrue("Country" in labelsOf(state), "${labelsOf(state)}")
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.MUSICBRAINZ])
        }

    @Test
    fun `a Retry of another source reports a kept label failure again`() =
        runTest {
            // Last.fm's track tags fail; then a *Retry* of MusicBrainz's label requests.
            val counted =
                Counted { call ->
                    if (call ==
                        1
                    ) {
                        Lookup.Failed("network")
                    } else {
                        Lookup.Found(listOf("country"))
                    }
                }
            val pipeline = counting(counted, ChangingReleaseTags(Lookup.Failed("network")))
            val reporting = Reporting()
            val first = pipeline.run(sundown, reporting)
            assertEquals(listOf(setOf(SourceId.MUSICBRAINZ, SourceId.LASTFM)), reporting.failed)
            val before = Gathered(first.sources, first.resolution, labelsFailed = reporting.failed.single())

            val retried = Reporting()
            val state = pipeline.run(sundown, retried, again = before.asking(setOf(SourceId.MUSICBRAINZ)))
            assertEquals(listOf(setOf(SourceId.LASTFM)), retried.failed)
            assertEquals(LaterLabels.FAILED, state.laterLabels[LabelSource.LASTFM])
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.MUSICBRAINZ])
        }

    @Test
    fun `a Retry of a lookup carries an earlier label failure through`() =
        runTest {
            val counted = Counted { Lookup.Found(listOf("country")) }
            val pipeline = counting(counted, ChangingReleaseTags(Lookup.Failed("network")))
            val reporting = Reporting()
            val first = pipeline.run(sundown, reporting)
            assertEquals(listOf(setOf(SourceId.LASTFM)), reporting.failed)
            // MusicBrainz's lookup to be made again, as a *Retry* of an unreachable source makes it.
            val unreached =
                SourceState.Done(
                    SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO),
                    cached = false,
                )
            val before =
                Gathered(
                    first.sources + (SourceId.MUSICBRAINZ to unreached),
                    first.resolution,
                    labelsFailed = reporting.failed.single(),
                ).asking(setOf(SourceId.MUSICBRAINZ))
            assertEquals(setOf(SourceId.MUSICBRAINZ), before.asked)

            val retried = Reporting()
            val state = pipeline.run(sundown, retried, again = before)
            assertEquals(listOf(setOf(SourceId.LASTFM)), retried.failed)
            assertEquals(LaterLabels.FAILED, state.laterLabels[LabelSource.LASTFM])
        }

    @Test
    fun `a source kept done by a Retry stays done in how the requests stand`() =
        runTest {
            // Both answer; then MusicBrainz's label requests are asked again on a *Retry* of them.
            val counted = Counted { Lookup.Found(listOf("country")) }
            val pipeline = counting(counted, ChangingReleaseTags())
            val first = pipeline.run(sundown, write = false)
            val before = Gathered(first.sources, first.resolution, labelsFailed = setOf(SourceId.MUSICBRAINZ))
            val state =
                pipeline.run(
                    sundown,
                    RunControl.Unattended(write = false),
                    again = before.asking(setOf(SourceId.MUSICBRAINZ)),
                )
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.LASTFM])
            assertEquals(LaterLabels.DONE, state.laterLabels[LabelSource.MUSICBRAINZ])
        }

    private companion object {
        const val MB_ARTIST = "mb-gl"
        const val LASTFM_PAGE = "https://www.last.fm/music/Gordon+Lightfoot"
    }
}
