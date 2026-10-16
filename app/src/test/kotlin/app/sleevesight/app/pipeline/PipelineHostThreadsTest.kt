package app.sleevesight.app.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.PipelineParts
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The host on real threads (`Dispatchers.Default`), with barriers holding a lookup or a key read
 * where a cancellation, a grant or another build can overtake it. Each case waits for what it
 * needs, with a bound, and checks what must hold whatever the threads' order.
 */
class PipelineHostThreadsTest {
    /** Answers at once, unless its lookup of a track is held; says when each lookup has begun. */
    private class HeldAdapter : SourceAdapter {
        override val id: SourceId = SourceId.MUSICBRAINZ
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val held = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val begun = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun begun(key: String): CompletableDeferred<Unit> = begun.getOrPut(key) { CompletableDeferred() }

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            asked += track.lookupKey
            begun(track.lookupKey).complete(Unit)
            held[track.lookupKey]?.await()
            val line = EvidenceDraft(id, Scope.SONG, "A line.", null, Instant.parse("2026-10-17T12:00:00Z"))
            return SourceResult(Outcome.SongArticle, listOf(line), Duration.ZERO)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val keyChanges = MutableStateFlow(0)
    private val adapter = HeldAdapter()
    private val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val keyReads = AtomicInteger()
    private val builds = AtomicInteger()

    @Volatile
    private var keysHeld: CompletableDeferred<Unit>? = null

    private val host =
        PipelineHost(
            acknowledged = MutableStateFlow(true),
            nowPlaying = nowPlaying,
            settings = MutableStateFlow(Settings()),
            keyChanges = keyChanges.map { },
            readKeys = {
                keyReads.incrementAndGet()
                keysHeld?.await()
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

    private fun play(title: String) {
        nowPlaying.value = NowPlaying(title = title, artists = "Artist", player = Settings.SPOTIFY)
    }

    private suspend fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(BOUND_MS) { while (!condition()) delay(STEP_MS) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Not reached within $BOUND_MS ms: $what", e)
        }
    }

    private fun shown(): String? =
        host.state.value.track
            ?.nowPlaying
            ?.title

    private fun gathered(title: String) =
        shown() == title &&
            host.state.value.track
                ?.gathered == true

    @Test
    fun aLookupHeldWhileAnotherTrackReplacesItNeverShows() =
        runBlocking {
            adapter.held["artist|alpha"] = CompletableDeferred()
            host.activityStarted()
            host.start(scope)
            play("Alpha")
            withTimeout(BOUND_MS) { adapter.begun("artist|alpha").await() }
            play("Bravo")
            withTimeout(BOUND_MS) { adapter.begun("artist|bravo").await() }
            // Alpha's lookup lands now, on another thread, after its run was cancelled.
            adapter.held.getValue("artist|alpha").complete(Unit)
            until("Bravo gathered") { gathered("Bravo") }
            delay(SETTLE_MS)

            assertEquals("Bravo", shown())
            assertEquals(listOf("artist|alpha", "artist|bravo"), adapter.asked.toList())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    @Test
    fun aBuildOvertakenByAnotherIsNeverSwappedIn() =
        runBlocking {
            host.activityStarted()
            host.start(scope)
            play("Alpha")
            until("Alpha gathered") { gathered("Alpha") }

            // The second build's key read is held; a third build overtakes it.
            keysHeld = CompletableDeferred()
            val held = keysHeld!!
            keyChanges.value++
            until("the second key read") { keyReads.get() == 2 }
            keysHeld = null
            keyChanges.value++
            until("the replay on the third build") { adapter.asked.size == 2 }
            held.complete(Unit)
            delay(SETTLE_MS)

            assertEquals(2, adapter.asked.size)
            assertEquals("initial and third builds; the second was cancelled", 2, builds.get())
            assertTrue(gathered("Alpha"))
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    @Test
    fun anOpeningRacingANewTrackGrantsTheOldTrackAtMostOnce() =
        runBlocking {
            host.start(scope)
            play("Alpha")
            // Alpha settles while hidden, and waits for its lookups to be granted.
            delay(SETTLE_MS)
            assertEquals(emptyList<String>(), adapter.asked.toList())

            // The opening and Bravo's report race from two threads.
            listOf(scope.launch { host.activityStarted() }, scope.launch { play("Bravo") }).joinAll()
            until("Bravo gathered") { gathered("Bravo") }
            delay(SETTLE_MS)

            val asked = adapter.asked.toList()
            assertEquals("artist|bravo", asked.last())
            assertTrue(asked.count { it == "artist|alpha" } <= 1)
            assertEquals("Bravo", shown())
            assertEquals(emptyList<Throwable>(), failures.toList())
        }

    private companion object {
        const val BOUND_MS = 10_000L
        const val STEP_MS = 10L

        // Past the debounce (650 ms), with room for the threads.
        const val SETTLE_MS = 1_200L
    }
}
