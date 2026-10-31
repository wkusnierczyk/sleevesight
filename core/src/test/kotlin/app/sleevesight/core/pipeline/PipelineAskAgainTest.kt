package app.sleevesight.core.pipeline

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration

/**
 * A run that asks again the sources an earlier run of the track found unreachable (back online,
 * #192, `Pipeline.run`'s `again`): only those are looked up; the others keep what they found, and
 * a resolution made then is used again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineAskAgainTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val key = "gordon lightfoot|sundown"

    // A source that is unreachable while [down], as an adapter maps a network error.
    private var down = true

    private fun web() =
        FakeAdapter(SourceId.WEB, verifies = false) { _, _ ->
            if (down) {
                SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO)
            } else {
                SourceResult(Outcome.SongArticle, listOf(line(SourceId.WEB, "A web line.")), Duration.ZERO)
            }
        }

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter>,
        resolver: TrackResolver,
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(normalizer, adapters, resolver, JvmHasher, cache = cache),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings() }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    // A control that grants every step at once and never writes, recording what it was told.
    private fun granting() = Granted(write = false).apply { grants.values.forEach { it.complete(Unit) } }

    @Test
    fun `only the sources unreachable before are looked up again, and the resolution made is used again`() =
        runTest {
            val musicBrainz = FakeAdapter(SourceId.MUSICBRAINZ)
            val web = web()
            val resolver = FakeResolver()
            val pipeline = pipeline(listOf(musicBrainz, web), resolver)
            val events = recording(pipeline)

            val first = granting()
            pipeline.run(sundown, first)
            val before = first.gathered.single()
            assertEquals(setOf(SourceId.WEB), before.unreachable)

            down = false
            val again = granting()
            val state = pipeline.run(sundown, again, before)
            runCurrent()

            assertEquals(listOf(key), musicBrainz.asked)
            assertEquals(listOf(key, key), web.asked)
            assertEquals(1, resolver.calls)
            assertEquals(before.resolution, state.resolution)
            assertEquals(before.sources[SourceId.MUSICBRAINZ], state.sources[SourceId.MUSICBRAINZ])
            assertEquals(Outcome.SongArticle, (state.sources[SourceId.WEB] as SourceState.Done).result.outcome)
            assertEquals(emptySet(), again.gathered.single().unreachable)
            assertEquals(listOf("MB1", "S1", "P1"), state.evidence?.ids)
            assertEquals(listOf(SourceId.WEB), events.filterIsInstance<PipelineEvent.AskedAgain>().single().sources)
        }

    @Test
    fun `a resolution that was unreachable is asked for again`() =
        runTest {
            val resolver = FakeResolver(resolution = Resolution(Outcome.Unreachable))
            val web = FakeAdapter(SourceId.WEB) { _, resolved -> answer(resolved) }
            val pipeline = pipeline(listOf(FakeAdapter(SourceId.MUSICBRAINZ), web), resolver)

            val first = granting()
            pipeline.run(sundown, first)
            resolver.resolution = Resolution(Outcome.SongArticle, recordingMbid = "rec-1")
            down = false
            val state = pipeline.run(sundown, granting(), first.gathered.single())

            assertEquals(2, resolver.calls)
            assertEquals("rec-1", state.resolution?.recordingMbid)
            assertEquals(listOf(true), web.resolutions.drop(1).map { it.resolved })
        }

    @Test
    fun `a source that threw is not asked again, and one served from the cache stays so`() =
        runTest {
            val cache = MemoryCache()
            val lastFm = FakeAdapter(SourceId.LASTFM, failure = IllegalStateException("bug"))
            val musicBrainz = FakeAdapter(SourceId.MUSICBRAINZ)
            val web = web()
            val pipeline = pipeline(listOf(musicBrainz, lastFm, web), FakeResolver(), cache)
            // MusicBrainz's result is in the cache from before.
            pipeline.run(sundown, granting())
            cache.results.keys.removeAll { it.first != SourceId.MUSICBRAINZ }

            val first = granting()
            pipeline.run(sundown, first)
            down = false
            val state = pipeline.run(sundown, granting(), first.gathered.single())

            assertEquals(2, lastFm.asked.size)
            assertIs<SourceState.Failed>(state.sources[SourceId.LASTFM])
            assertEquals(true, (state.sources[SourceId.MUSICBRAINZ] as SourceState.Done).cached)
            assertEquals(3, web.asked.size)
            // The web result found now is kept: it was verified against a resolution made.
            assertEquals(Outcome.SongArticle, cache.results[SourceId.WEB to "$key@null"]?.outcome)
        }

    // A web answer that holds a line only when the resolution was made; unreachable while [down].
    private fun answer(resolved: Resolution?): SourceResult =
        when {
            down -> SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO)
            resolved?.resolved == true -> {
                SourceResult(Outcome.SongArticle, listOf(line(SourceId.WEB, "A line.")), Duration.ZERO)
            }
            else -> SourceResult(Outcome.None, emptyList(), Duration.ZERO)
        }
}
