package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * An insight is shown no longer than *Keep history for* after it was written (#643), as the cache
 * serves it: shortened past the insight shown, whether the run's own (from the cache) or the card
 * paid for (*Look again*'s fallback included), the view drops it at once, never shows it again, and
 * offers Write to write it afresh, with no call on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostKeepHistoryTest : PipelineHostFixture() {
    // Sundown written on a tap, its sources' results kept in the cache.
    private fun TestScope.written() {
        cache.keepsResults = true
        started()
        play("Sundown")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requests)
        assertTrue(insight() is InsightState.Written)
    }

    private fun insight(): InsightState? =
        host.state.value.track
            ?.insight

    // Every view published from now on.
    private fun watched(): List<PipelineView> {
        val views = mutableListOf<PipelineView>()
        host.published = { views += it }
        return views
    }

    // *Keep history for* shortened to a week, past the insight shown.
    private fun shorten() {
        settings.value = settings.value.copy(historyLifetime = 7.days)
    }

    // The insight shown goes past *Keep history for* by [past]: dropped at once, never shown again,
    // and Write offered once the track is looked up again, with no call.
    private fun TestScope.goesPastIt(past: () -> Unit = ::shorten) {
        val calls = provider.requests
        val views = watched()
        past()
        runCurrent()
        assertFalse("not shown at once", insight() is InsightState.Written)
        settle()

        assertTrue("never shown again", views.none { it.track?.insight is InsightState.Written })
        assertFalse(host.state.value.cardShown)
        assertNull(host.state.value.lookAgainKey)
        assertFalse(host.state.value.nothingNew)
        assertNotNull("Write offered", host.state.value.writeKey)
        assertEquals("no call on its own", calls, provider.requests)
    }

    // A tap on the Write offered writes it afresh, one call.
    private fun TestScope.writesAfresh() {
        val calls = provider.requests
        tap()
        settle()
        assertEquals("a tap writes it afresh", calls + 1, provider.requests)
        assertTrue(insight() is InsightState.Written)
    }

    // The insight shown goes past *Keep history for* when it is shortened, and a tap writes afresh.
    private fun TestScope.shortenedPastIt() {
        goesPastIt()
        writesAfresh()
    }

    @Test
    fun `an insight served from the cache goes from the view when Keep history for is shortened past it`() =
        runTest {
            written()
            play("Carefree Highway")
            settle()
            clock.at += 30.days
            play("Sundown")
            settle()
            val kept = insight() as InsightState.Written
            assertTrue(kept.cached)
            assertEquals(clock.at - 30.days, kept.writtenAt)

            shortenedPastIt()
        }

    @Test
    fun `the card paid for goes from the view when Keep history for is shortened past it, while it plays`() =
        runTest {
            written()
            clock.at += 30.days
            // Still shown under 90 days, with *Look again* offered.
            settle()
            assertTrue(insight() is InsightState.Written)
            assertNotNull(host.state.value.lookAgainKey)

            shortenedPastIt()
        }

    @Test
    fun `a look again that found nothing new does not keep the card paid for past Keep history for`() =
        runTest {
            written()
            clock.at += 30.days
            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertTrue(host.state.value.nothingNew)
            assertTrue(host.state.value.cardShown)

            shortenedPastIt()
        }

    @Test
    fun `an insight that passes Keep history for on the screen goes at the next change shown, with Write offered`() =
        runTest {
            settings.value = settings.value.copy(historyLifetime = 7.days)
            written()
            clock.at += 8.days
            // No change of the setting: the app left and opened again republishes the view.
            goesPastIt {
                host.activityStopped(changingConfigurations = false)
                runCurrent()
                host.activityStarted()
            }
            writesAfresh()
        }

    @Test
    fun `a card read from the cache across the shortening is dropped as it arrives, with Write offered`() =
        runTest {
            written()
            play("Carefree Highway")
            settle()
            clock.at += 30.days
            // The card passes the cache's check under 90 days, and arrives only after the change.
            val read = CompletableDeferred<Unit>()
            cache.readHeld = read
            play("Sundown")
            settle()
            goesPastIt {
                shorten()
                read.complete(Unit)
            }
            writesAfresh()
        }

    @Test
    fun `the expiry's look up writes nothing on its own under the most eager mode, after a skip`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            written()
            play("Carefree Highway", cause = ChangeCause.SKIP_IN_PLAYER)
            settle()
            clock.at += 30.days
            play("Sundown", cause = ChangeCause.SKIP_IN_PLAYER)
            settle()
            assertTrue((insight() as InsightState.Written).cached)

            goesPastIt()
            settle()
            assertNotNull(host.state.value.writeKey)
            writesAfresh()
        }

    @Test
    fun `a card dropped past Keep history for is not shown again under a longer one`() =
        runTest {
            written()
            clock.at += 30.days
            goesPastIt()
            settings.value = settings.value.copy(historyLifetime = 365.days)
            settle()

            assertFalse(insight() is InsightState.Written)
            assertFalse(host.state.value.cardShown)
            assertNotNull(host.state.value.writeKey)
        }

    @Test
    fun `a fresh insight carries the time its card is kept with, in whole milliseconds`() =
        runTest {
            // A clock that moves on between each reading.
            clock.tick = 7.milliseconds + 300.microseconds
            written()
            val writtenAt = checkNotNull((insight() as InsightState.Written).writtenAt)

            assertTrue(writtenAt in cache.keptTimes)
            assertEquals(0, writtenAt.nanosecondsOfSecond % 1_000_000)
        }

    @Test
    fun `a longer Keep history for keeps the insight shown, and looks nothing up again`() =
        runTest {
            written()
            clock.at += 30.days
            val before = builds
            settings.value = settings.value.copy(historyLifetime = 365.days)
            settle()

            assertTrue(insight() is InsightState.Written)
            assertEquals(before, builds)
            assertEquals(1, provider.requests)
        }
}
