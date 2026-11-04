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
        backgroundScope.launch(now) {
            combine(repository.nowPlaying, repository.queue, NextInQueue::of).collect {
                next.value =
                    it
            }
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
