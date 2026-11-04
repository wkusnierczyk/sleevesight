package app.sleevesight.app.pipeline

import android.media.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.ArtLoader
import app.sleevesight.app.session.FakeActiveSessions
import app.sleevesight.app.session.FakeSession
import app.sleevesight.app.session.HttpArt
import app.sleevesight.app.session.NextInQueue
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.advertised
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.app.speech.FakeFocus
import app.sleevesight.app.speech.FakeSpeech
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An ad between two tracks (#880), from the real session layer into the host, as the app wires them
 * (`AppGraph`: [SessionRepository.trackChanges] and the next queue item): the ad reaches no path that
 * looks up or pays, and the track after it is analysed as usual. The ad is synthetic
 * (`AdDetectionTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class PipelineHostAdTest : PipelineHostFixture() {
    private val ad: MediaMetadata =
        advertised(metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000))
    private val carefree = metadata(title = "Carefree Highway", mediaId = "spotify:track:2", durationMs = 230_000)

    /** Every track the host's view has shown, by title. */
    private val shown = mutableListOf<String?>()

    private lateinit var repository: SessionRepository

    // Spotify playing Sundown, through a session layer on this test's clock, its reports going to the
    // host as the app sends them.
    private fun TestScope.spotify(): FakeSession {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "spotify:track:1", durationMs = 225_000))
        val now = UnconfinedTestDispatcher(testScheduler)
        repository =
            SessionRepository(
                FakeActiveSessions(listOf(spotify)),
                MutableStateFlow(true),
                ArtLoader(
                    ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
                    HttpArt { null },
                    Dispatchers.Unconfined,
                ),
                CoroutineScope(backgroundScope.coroutineContext + now),
                flowOf(setOf(SPOTIFY)),
                clock = { testScheduler.currentTime },
            )
        backgroundScope.launch(now) { repository.trackChanges.collect { changes.value = it } }
        backgroundScope.launch(now) { repository.withdrawn.collect { withdrawn.value = it } }
        backgroundScope.launch(now) {
            combine(repository.nowPlaying, repository.ad, repository.queue) { playing, ad, queue ->
                NextInQueue.of(playing, queue) ?: NextInQueue.afterAd(ad, queue)
            }.collect { next.value = it }
        }
        backgroundScope.launch(now) { host.state.collect { shown += it.track?.nowPlaying?.title } }
        repository.start()
        runCurrent()
        return spotify
    }

    // A first action (Sundown written on a tap), so automatic writing writes by itself from then on.
    private fun TestScope.acted(): FakeSession {
        started()
        val spotify = spotify()
        settle()
        tap()
        settle()
        assertEquals(1, provider.requestsFor("Sundown"))
        return spotify
    }

    private fun askedAboutTheAd(): Boolean =
        provider.prompts.any { "tetesept" in it } ||
            resolved.any { "tetesept" in it.first } ||
            musicBrainz.asked.any { "tetesept" in it }

    // Spotify's queue with an ad as its second item.
    private val adQueue =
        listOf(queueItem(1, "Sundown", mediaId = "spotify:track:1"), queueItem(2, "tetesept", mediaId = "spotify:ad:1"))

    // The ad's metadata, with no flag and no media id, and the state naming its queue item: a track
    // until the queue that names that item an ad comes.
    private fun FakeSession.adWithoutItsQueue() {
        send(state(queueItem = 2))
        send(metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000))
    }

    @Test
    fun `an ad told by a queue after the session settled, before the debounce, is never looked up`() =
        runTest {
            val spotify = acted()
            spotify.adWithoutItsQueue()
            advanceTimeBy(SessionRepository.SETTLE + 100.milliseconds)
            assertEquals("published as a track first", "tetesept", changes.value?.nowPlaying?.title)
            spotify.sendQueue(adQueue, "Play Queue")
            settle()
            assertFalse("nothing looked up or written for the ad", askedAboutTheAd())
            spotify.send(carefree)
            settle()
            assertEquals("the track after it, as usual", 1, provider.requestsFor("Carefree Highway"))
        }

    @Test
    fun `an ad told after its run started has the run cancelled, and nothing more done for it`() =
        runTest {
            settings.value = settings.value.copy(readNewInsightsAloud = true)
            val speech = FakeSpeech()
            val readAloud =
                ReadAloud(
                    speech,
                    FakeFocus(),
                    settings,
                    SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
                    { false },
                    { _, _ -> },
                    backgroundScope,
                ) { Locale.UK }
            readAloud.activityStarted()
            val spotify = acted()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                readAloud.follow(repository.nowPlaying, host.state)
            }
            runCurrent()
            val read = speech.spoken.size
            provider.streaming = 10.seconds
            spotify.adWithoutItsQueue()
            advanceTimeBy(3.seconds)
            assertEquals("its run started, its call under way", 1, provider.requestsFor("tetesept"))
            val lookedUp = resolved.size
            val completed = provider.completed

            spotify.sendQueue(adQueue, "Play Queue")
            settle()
            assertEquals("its call cut off", completed, provider.completed)
            assertEquals("nothing more asked", 1, provider.requestsFor("tetesept"))
            assertEquals("nothing more looked up", lookedUp, resolved.size)
            assertTrue(
                "not shown: ${host.state.value.track}",
                host.state.value.track
                    ?.nowPlaying
                    ?.title != "tetesept",
            )
            assertEquals("nothing read", read, speech.spoken.size)
        }

    @Test
    fun `an ad told late counts as no track change for Saver either`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            val spotify = acted()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            spotify.adWithoutItsQueue()
            advanceTimeBy(SessionRepository.SETTLE + 100.milliseconds)
            spotify.sendQueue(adQueue, "Play Queue")
            settle()
            spotify.send(carefree)
            settle()
            assertEquals(1, provider.requestsFor("Carefree Highway"))
            assertFalse("written, not offered", host.state.value.lapsed)
        }

    @Test
    fun `an ad told late after it was reported again counts as no track change for Saver`() =
        runTest {
            // The ad published as a track, then again with its length filled in, then told by its queue.
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            val spotify = acted()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            spotify.send(state(queueItem = 2))
            spotify.send(metadata(title = "tetesept", artist = "Advertisement • 2 of 3"))
            advanceTimeBy(SessionRepository.SETTLE + 100.milliseconds)
            spotify.send(metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000))
            advanceTimeBy(SessionRepository.SETTLE + 100.milliseconds)
            assertEquals(28.seconds, changes.value?.nowPlaying?.duration)
            spotify.sendQueue(adQueue, "Play Queue")
            settle()
            spotify.send(carefree)
            settle()
            assertEquals(1, provider.requestsFor("Carefree Highway"))
            assertFalse("written, not offered", host.state.value.lapsed)
        }

    @Test
    fun `during an ad, the track after it in the queue is prepared`() =
        runTest {
            started()
            val spotify = spotify()
            settle()
            spotify.sendQueue(
                listOf(
                    queueItem(1, "Sundown", mediaId = "spotify:track:1"),
                    queueItem(2, "tetesept", mediaId = "spotify:ad:1"),
                    queueItem(3, "Carefree Highway", "Gordon Lightfoot", mediaId = "spotify:track:2"),
                ),
                "Play Queue",
            )
            settle()
            assertEquals("past the ad, before it", "Carefree Highway", next.value?.title)
            spotify.send(state(queueItem = 2))
            spotify.send(ad)
            settle()
            assertEquals("during it", "Carefree Highway", next.value?.title)
            assertTrue("prepared: $resolved", resolved.any { "carefree highway" in it.first })
            assertFalse(askedAboutTheAd())
        }

    @Test
    fun `automatic writing writes no insight for an ad, and writes the track after it`() =
        runTest {
            val spotify = acted()
            spotify.send(ad)
            settle()
            assertFalse("nothing looked up or written for the ad", askedAboutTheAd())
            spotify.send(carefree)
            settle()
            assertEquals(1, provider.requestsFor("Carefree Highway"))
            assertFalse(askedAboutTheAd())
        }

    @Test
    fun `no track the panels read is ever the ad, so Search with AI and the notes never ask about it`() =
        runTest {
            // *Ask AI automatically*, the genres' notes and the item insights all follow the host's track.
            val spotify = acted()
            spotify.send(ad)
            settle()
            spotify.send(carefree)
            settle()
            assertTrue("never the ad: $shown", shown.none { it == "tetesept" })
            assertEquals("Carefree Highway", shown.last())
        }

    @Test
    fun `a tap during an ad starts nothing`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            started()
            val spotify = spotify()
            settle()
            spotify.send(ad)
            settle()
            // Home shows no Write during an ad (`AdHomeScreenTest`); a tap on one still shown
            // for the track before is for a track the session layer no longer shows.
            val key = host.state.value.writeKey
            if (key != null) host.write(key, checkNotNull(host.state.value.track).nowPlaying)
            settle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `an ad next in the queue is not prepared`() =
        runTest {
            started()
            val spotify = spotify()
            spotify.sendQueue(
                listOf(
                    queueItem(1, "Sundown", mediaId = "spotify:track:1"),
                    queueItem(2, "tetesept", mediaId = "spotify:ad:1"),
                ),
                "Play Queue",
            )
            settle()
            assertNull(next.value)
            assertFalse(askedAboutTheAd())
        }

    @Test
    fun `with Read new insights aloud, nothing is read for an ad`() =
        runTest {
            settings.value = settings.value.copy(readNewInsightsAloud = true)
            // A reply of its own each time, so each insight is a new one to read.
            provider.reply = { "It is song number $it [MB1]." }
            val speech = FakeSpeech()
            val readAloud =
                ReadAloud(
                    speech,
                    FakeFocus(),
                    settings,
                    SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
                    { false },
                    { _, _ -> },
                    backgroundScope,
                ) { Locale.UK }
            readAloud.activityStarted()
            started()
            val spotify = spotify()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                readAloud.follow(repository.nowPlaying, host.state)
            }
            settle()
            tap()
            settle()
            assertEquals("Sundown's insight, read as it landed", 1, speech.spoken.size)
            spotify.send(ad)
            settle()
            assertEquals("the reading stopped, and nothing read for the ad", null, readAloud.reading.value)
            assertEquals(1, speech.spoken.size)
            spotify.send(carefree)
            settle()
            assertEquals("the track after it, written and read", 2, speech.spoken.size)
        }

    @Test
    fun `an ad counts as no track change for Saver`() =
        runTest {
            // Paused after one change away: Sundown, the ad, Carefree Highway is one change, not two.
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            val spotify = acted()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            spotify.send(ad)
            settle()
            spotify.send(carefree)
            settle()
            assertEquals(1, provider.requestsFor("Carefree Highway"))
            assertFalse("written, not offered", host.state.value.lapsed)
        }
}
