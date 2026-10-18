package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
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
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Preparing the next track, *Evidence* (D13, #20): from a queue item, by name, with no length;
 * only what carries over to the playing track's run; nothing shown, kept as the track's own, or
 * written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparationTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private val resolved = mutableListOf<Pair<String, Duration?>>()

    // The resolver's answers, in turn; once they run out, a recording found.
    private val answers = ArrayDeque<Resolution>()
    private val resolver =
        TrackResolver { track, duration ->
            resolved += track.lookupKey to duration
            answers.removeFirstOrNull() ?: Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec")
        }

    // MusicBrainz's lines, naming the resolved artist; it prepares ahead, as the real one does.
    private val musicBrainz =
        Ahead(
            FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                SourceResult(Outcome.SongArticle, emptyList(), Duration.ZERO, labelArtists = listOf("artist-1"))
            },
        )
    private val lastFm = FakeAdapter(SourceId.LASTFM)
    private val wikipedia = FakeAdapter(SourceId.WIKIPEDIA)
    private val tagRequests = mutableListOf<String>()
    private val labels =
        MusicBrainzLabels({ mbid -> Lookup.Found(listOf("folk")).also { tagRequests += mbid } }, vocabulary)
    private val provider = FakeProvider()
    private val cache = MemoryCache()

    private val steps = mutableListOf<String>()
    private val control =
        object : RunControl {
            override suspend fun await(step: RunStep) {
                steps += "await $step"
            }

            override suspend fun decide(settings: Settings): Boolean = error("a preparation never asks to write")

            override val waitsForTap: Boolean = false

            override suspend fun awaitTap() = error("a preparation never waits for a tap")

            override fun ended(step: RunStep) {
                steps += "ended $step"
            }

            override fun decidedWithoutCall() = error("a preparation decides no insight")
        }

    private fun pipeline() =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(musicBrainz, wikipedia, lastFm),
                resolver = resolver,
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                musicBrainzLabels = labels,
            ),
        ) { settings }

    @Test
    fun `the next track is resolved by name, with what carries over, and nothing else`() =
        runTest {
            val pipeline = pipeline()
            val events = mutableListOf<PipelineEvent>()
            backgroundScope.launch { pipeline.events.toList(events) }
            runCurrent()
            pipeline.prepare(nowPlaying("Next Song", "Next Artist"), control)
            runCurrent()

            assertEquals(listOf<Pair<String, Duration?>>("next artist|next song" to null), resolved)
            assertEquals(listOf("next artist|next song"), musicBrainz.inner.asked)
            assertEquals(emptyList<String>(), wikipedia.asked, "Wikipedia's results would not carry over")
            assertEquals(emptyList<String>(), lastFm.asked, "Last.fm's requests would be sent again")
            assertEquals(listOf("artist-1"), tagRequests)
            assertEquals(
                listOf("await LOOKUPS", "ended LOOKUPS", "await LABELS", "ended LABELS"),
                steps,
            )
            assertNull(pipeline.state.value, "nothing is shown")
            assertTrue(cache.results.isEmpty() && cache.cards.isEmpty(), "nothing is kept as the track's own")
            assertEquals(0, provider.requests.size, "no paid call")
            assertTrue(events.any { it is PipelineEvent.Prepared && it.key == "next artist|next song" })
        }

    @Test
    fun `nothing is prepared with MusicBrainz switched off, nor tried again`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ)
            pipeline().prepare(nowPlaying("Next Song", "Next Artist"), control)
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, Duration?>>(), resolved)
            assertEquals(emptyList<String>(), steps)
        }

    @Test
    fun `an unreachable resolution is tried once more, after the wait, within the same lookups step`() =
        runTest {
            answers += Resolution.unreachable("RateLimited")
            val pipeline = pipeline()
            val events = recording(pipeline)
            backgroundScope.launch { pipeline.prepare(nowPlaying("Next Song", "Next Artist"), control) }
            runCurrent()
            assertEquals(1, resolved.size)

            advanceTimeBy(Preparation.RETRY_WAIT - 1.milliseconds)
            assertEquals(1, resolved.size, "not before the wait")
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertEquals(List<Pair<String, Duration?>>(2) { "next artist|next song" to null }, resolved)
            assertEquals(
                listOf(
                    "prepared   next artist|next song: unreachable (RateLimited) in N ms",
                    "prepared   next artist|next song: song_article in N ms, second try",
                ),
                events.filterIsInstance<PipelineEvent.Prepared>().map { EventLine.of(it).replace(LATENCY, "in N ms") },
            )
            // What carries over follows the second try, as it would the first.
            assertEquals(listOf("next artist|next song"), musicBrainz.inner.asked)
            assertEquals(listOf("artist-1"), tagRequests)
            assertEquals(listOf("await LOOKUPS", "ended LOOKUPS", "await LABELS", "ended LABELS"), steps)
        }

    @Test
    fun `a second try that is unreachable too is the last`() =
        runTest {
            answers += Resolution.unreachable("Offline")
            answers += Resolution.unreachable("Server")
            val pipeline = pipeline()
            val events = recording(pipeline)
            pipeline.prepare(nowPlaying("Next Song", "Next Artist"), control)
            advanceUntilIdle()

            assertEquals(2, resolved.size)
            assertEquals(
                listOf(
                    Prepared(Resolution.unreachable("Offline"), again = false),
                    Prepared(Resolution.unreachable("Server"), again = true),
                ),
                events.filterIsInstance<PipelineEvent.Prepared>().map { Prepared(it) },
            )
        }

    @Test
    fun `a preparation cancelled while it waits sends nothing more and reports nothing more`() =
        runTest {
            answers += Resolution.unreachable("RateLimited")
            val pipeline = pipeline()
            val events = recording(pipeline)
            val job = backgroundScope.launch { pipeline.prepare(nowPlaying("Next Song", "Next Artist"), control) }
            advanceTimeBy(Preparation.RETRY_WAIT / 2)
            job.cancel()
            // Past the wait; `advanceUntilIdle` would not run the background scope's work.
            advanceTimeBy(Preparation.RETRY_WAIT)
            runCurrent()

            assertEquals(1, resolved.size)
            assertEquals(1, events.count { it is PipelineEvent.Prepared })
            assertEquals(emptyList<String>(), musicBrainz.inner.asked)
            assertEquals(emptyList<String>(), tagRequests)
            assertEquals(listOf("await LOOKUPS", "ended LOOKUPS"), steps, "the step still ends")
        }

    @Test
    fun `an answer from MusicBrainz is not tried again, whatever it was`() =
        runTest {
            for (answer in listOf(Resolution.NONE, Resolution(outcome = Outcome.Rejected("another artist")))) {
                resolved.clear()
                answers += answer
                pipeline().prepare(nowPlaying("Next Song", "Next Artist"), control)
                advanceUntilIdle()
                assertEquals(1, resolved.size, "${answer.outcome}")
            }
        }

    @Test
    fun `an item with no artists is not prepared`() =
        runTest {
            pipeline().prepare(nowPlaying("Next Song", ""), control)
            assertEquals(emptyList<String>(), steps)
        }

    // The events of [pipeline], as they come.
    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    // What a Prepared event says of its resolution, without the time it took.
    private data class Prepared(
        val resolution: Resolution,
        val again: Boolean,
    ) {
        constructor(event: PipelineEvent.Prepared) : this(
            Resolution(outcome = event.outcome, failure = event.failure),
            event.again,
        )
    }

    private companion object {
        val LATENCY = Regex("in \\d+ ms")
    }

    // [inner], which prepares ahead.
    private class Ahead(
        val inner: FakeAdapter,
    ) : SourceAdapter by inner {
        override val preparesAhead: Boolean = true

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult = inner.lookup(track, resolution)
    }
}
