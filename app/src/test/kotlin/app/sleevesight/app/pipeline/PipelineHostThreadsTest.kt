package app.sleevesight.app.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The host on real threads (`Dispatchers.Default`), with barriers holding a lookup or a key read
 * past a cancellation, so that a result lands late, after what overtook it. A held step blocks its
 * thread on a latch, bounded, rather than suspending: a suspended one would be cancelled at its
 * next suspension, and nothing would land late. Each case waits for
 * what it observes, with a bound, and checks what must hold whatever the threads' order. Reports
 * go through a rendezvous channel, followed by a `null` (which the host ignores), so a report is
 * known to be on the host's queue before the next step.
 */
class PipelineHostThreadsTest {
    /**
     * Answers at once, unless its lookup of a track is held: then it blocks, past any
     * cancellation, and lands late, returning to its run as if nothing had happened. It says when
     * a lookup has begun, and when a held one has been released.
     */
    private class HeldAdapter : SourceAdapter {
        override val id: SourceId = SourceId.MUSICBRAINZ
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val held = ConcurrentHashMap<String, CountDownLatch>()
        private val begun = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val landed = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun begun(key: String): CompletableDeferred<Unit> = begun.getOrPut(key) { CompletableDeferred() }

        fun landed(key: String): CompletableDeferred<Unit> = landed.getOrPut(key) { CompletableDeferred() }

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            val key = track.lookupKey
            // The barrier first, then the signal: the test may release it as soon as it hears.
            val hold = held[key]
            asked += key
            begun(key).complete(Unit)
            if (hold != null) {
                check(hold.await(BOUND_MS, TimeUnit.MILLISECONDS)) { "Held past the bound" }
                landed(key).complete(Unit)
            }
            val line = EvidenceDraft(id, Scope.SONG, "A line.", null, Instant.parse("2026-10-17T12:00:00Z"))
            return SourceResult(Outcome.SongArticle, listOf(line), Duration.ZERO)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reports = Channel<TrackChange?>()
    private val keyChanges = MutableStateFlow(0)
    private val adapter = HeldAdapter()
    private val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val keyReads = AtomicInteger()
    private val builds = AtomicInteger()
    private val shownTitles: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var keysHeld: CountDownLatch? = null

    private val host =
        PipelineHost(
            acknowledged = MutableStateFlow(true),
            changes = reports.receiveAsFlow(),
            // No Write is tapped here.
            latestPlaying = { null },
            settings = MutableStateFlow(Settings()),
            keyChanges = keyChanges.map { },
            readKeys = {
                // The barrier first, then the count the test watches.
                val hold = keysHeld
                keyReads.incrementAndGet()
                hold?.let { check(it.await(BOUND_MS, TimeUnit.MILLISECONDS)) { "Held past the bound" } }
                PipelineKeys()
            },
            parts = { _, _ ->
                builds.incrementAndGet()
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = listOf(adapter),
                    resolver = TrackResolver { _, _ -> Resolution(Outcome.SongArticle, recordingMbid = "rec") },
                    hasher = JvmHasher,
                )
            },
            normalizer = Normalizer(JvmAccentFolder),
            onFailure = { failures += it },
        )

    @After
    fun close() = scope.cancel()

    private fun start(visible: Boolean = true) {
        // Every view as the host publishes it, none conflated.
        host.published = { shownTitles += it.track?.nowPlaying?.title }
        if (visible) host.activityStarted()
        host.start(scope)
    }

    /** Runs [body] on real threads, bounded as a whole. */
    private fun bounded(body: suspend () -> Unit) = runBlocking { withTimeout(TEST_BOUND_MS) { body() } }

    /** Reports [title], and returns once the report is on the host's queue. */
    private suspend fun report(title: String) {
        reports.send(TrackChange(NowPlaying(title = title, artists = "Artist", player = Settings.SPOTIFY)))
        reports.send(null)
    }

    private suspend fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(BOUND_MS) { while (!condition()) delay(STEP_MS) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Not reached within $BOUND_MS ms: $what", e)
        }
    }

    private suspend fun heard(signal: CompletableDeferred<Unit>) = withTimeout(BOUND_MS) { signal.await() }

    private fun shown(): String? =
        host.state.value.track
            ?.nowPlaying
            ?.title

    private fun gathered(title: String) =
        shown() == title &&
            host.state.value.track
                ?.gathered == true

    @Test
    fun aLookupThatLandsAfterItsRunWasReplacedIsNeverShown() =
        bounded {
            adapter.held["artist|alpha"] = CountDownLatch(1)
            start()
            report("Alpha")
            heard(adapter.begun("artist|alpha"))
            report("Bravo")
            until("Bravo gathered") { gathered("Bravo") }
            until("Bravo recorded") { "Bravo" in shownTitles }

            // Alpha's lookup lands now, past its run's cancellation, on another thread.
            adapter.held.getValue("artist|alpha").countDown()
            heard(adapter.landed("artist|alpha"))
            report("Charlie")
            until("Charlie gathered") { gathered("Charlie") }

            val titles = shownTitles.toList()
            val afterBravo = titles.drop(titles.indexOf("Bravo"))
            assertTrue("Alpha shown after Bravo: $titles", "Alpha" !in afterBravo)
            assertEquals(listOf("artist|alpha", "artist|bravo", "artist|charlie"), adapter.asked.toList())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    @Test
    fun aBuildThatFinishesAfterItWasOvertakenIsNeverSwappedIn() =
        bounded {
            start()
            report("Alpha")
            until("Alpha gathered") { gathered("Alpha") }

            // The second build's key read is held, and the third's, which overtakes it.
            val second = CountDownLatch(1)
            keysHeld = second
            keyChanges.value++
            until("the second key read") { keyReads.get() == 2 }
            val third = CountDownLatch(1)
            keysHeld = third
            keyChanges.value++
            until("the third key read") { keyReads.get() == 3 }

            // The second build finishes while the third is pending, and says so to the host,
            // which ignores it: no swap, so no replay.
            second.countDown()
            until("the second build made") { builds.get() == 2 }
            third.countDown()
            until("the replay on the third build") { adapter.asked.size == 2 }
            report("Charlie")
            until("Charlie gathered") { gathered("Charlie") }

            // Had the second been swapped in, Alpha would have run once more, before or after.
            assertEquals(3, builds.get())
            assertEquals(listOf("artist|alpha", "artist|alpha", "artist|charlie"), adapter.asked.toList())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    @Test
    fun anOpeningBeforeANewTracksReportGrantsTheOldTrack() =
        bounded {
            start(visible = false)
            report("Alpha")
            until("Alpha's run waiting for its lookups") { shown() == "Alpha" }
            assertEquals(emptyList<String>(), adapter.asked.toList())

            host.activityStarted()
            heard(adapter.begun("artist|alpha"))
            report("Bravo")
            until("Bravo gathered") { gathered("Bravo") }

            assertEquals(listOf("artist|alpha", "artist|bravo"), adapter.asked.toList())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    @Test
    fun aNewTracksReportBeforeAnOpeningGrantsTheOldTrackNothing() =
        bounded {
            start(visible = false)
            report("Alpha")
            until("Alpha's run waiting for its lookups") { shown() == "Alpha" }

            // Bravo's report is on the queue before the opening, so Alpha is stale when it comes.
            report("Bravo")
            host.activityStarted()
            until("Bravo gathered") { gathered("Bravo") }

            assertEquals(listOf("artist|bravo"), adapter.asked.toList())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    private companion object {
        const val BOUND_MS = 10_000L
        const val TEST_BOUND_MS = 30_000L
        const val STEP_MS = 10L
    }
}
