package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.FreshLookups
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration

/**
 * A run that looks again (*Look again*, #617, `Pipeline.run`'s `lookAgain`): every source is asked
 * afresh, past the retrieval cache and marked [FreshLookups] for the caches behind the adapters;
 * what it finds is kept as a usual lookup's; a source it cannot reach keeps what the run before
 * found, so a network failure does not pass for new evidence; and the evidence's fingerprint is
 * reported before the insight is decided.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineLookAgainTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val key = "gordon lightfoot|sundown"

    // What the web search answers now: a line, or unreachable while [down].
    private var webLine = "A web line."
    private var down = false

    // Whether the adapters' lookups ran marked to look afresh, each time.
    private val marked = mutableListOf<Boolean>()

    private fun web() =
        FakeAdapter(SourceId.WEB, verifies = false) { _, _ ->
            if (down) {
                SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO)
            } else {
                SourceResult(Outcome.SongArticle, listOf(line(SourceId.WEB, webLine)), Duration.ZERO)
            }
        }

    // MusicBrainz's adapter, recording whether its lookup ran marked to look afresh.
    private inner class Marking : SourceAdapter {
        val inner = FakeAdapter(SourceId.MUSICBRAINZ)
        override val id: SourceId = SourceId.MUSICBRAINZ

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            marked += FreshLookups.active()
            return inner.lookup(track, resolution)
        }
    }

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter>,
        resolver: TrackResolver,
        cache: PipelineCache,
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
    fun `a look again asks every source past the cache, marked fresh, and keeps what it found`() =
        runTest {
            val cache = MemoryCache()
            val musicBrainz = Marking()
            val web = web()
            val pipeline = pipeline(listOf(musicBrainz, web), FakeResolver(), cache)
            val events = recording(pipeline)
            val first = granting()
            pipeline.run(sundown, first)
            // Played again: the cache answers, and nothing is asked.
            pipeline.run(sundown, granting())
            assertEquals(1, web.asked.size)

            webLine = "A new web line."
            val looking = granting()
            val state = pipeline.run(sundown, looking, lookAgain = LookAgain(first.gathered.single()))
            runCurrent()

            assertEquals(listOf(key, key), musicBrainz.inner.asked)
            assertEquals(listOf(key, key), web.asked)
            assertEquals(listOf(false, true), marked)
            assertEquals(false, (state.sources[SourceId.WEB] as SourceState.Done).cached)
            assertEquals(
                "A new web line.",
                cache.results[SourceId.WEB to "$key@null"]
                    ?.evidence
                    ?.single()
                    ?.text,
            )
            val fresh = events.filterIsInstance<PipelineEvent.LookedAgain>().single()
            assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WEB), fresh.sources)
            // The fingerprint reported is the evidence's, and differs from the one before.
            assertEquals(state.evidence?.fingerprint, looking.assembled.single().fingerprint)
            assertNotEquals(first.assembled.single().fingerprint, looking.assembled.single().fingerprint)
        }

    @Test
    fun `a source the look again cannot reach keeps what the run before found, so nothing is new`() =
        runTest {
            val cache = MemoryCache()
            val resolver = FakeResolver()
            val pipeline = pipeline(listOf(FakeAdapter(SourceId.MUSICBRAINZ), web()), resolver, cache)
            val first = granting()
            pipeline.run(sundown, first)
            val before = first.gathered.single()

            down = true
            resolver.resolution = Resolution(Outcome.Unreachable)
            val looking = granting()
            val state = pipeline.run(sundown, looking, lookAgain = LookAgain(before))

            assertEquals(before.sources[SourceId.WEB], state.sources[SourceId.WEB])
            assertEquals(before.resolution, state.resolution)
            assertEquals(emptySet(), looking.gathered.single().unreachable)
            assertEquals(first.assembled.single().fingerprint, looking.assembled.single().fingerprint)
        }

    @Test
    fun `a usual run reports its evidence too, and is not marked fresh`() =
        runTest {
            val pipeline = pipeline(listOf(Marking()), FakeResolver(), MemoryCache())
            val control = granting()
            val state = pipeline.run(sundown, control)

            assertEquals(listOf(state.evidence), control.assembled)
            assertEquals(listOf(false), marked)
        }
}
