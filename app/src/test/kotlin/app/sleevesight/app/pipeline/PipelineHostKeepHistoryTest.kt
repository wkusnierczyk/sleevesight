package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.InsightState
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

    // *Keep history for* shortened to a week, past the insight shown: dropped at once, never shown
    // again, and Write offered once the track is looked up again, with no call.
    private fun TestScope.shortenedPastIt() {
        val calls = provider.requests
        val views = watched()
        settings.value = settings.value.copy(historyLifetime = 7.days)
        runCurrent()
        assertFalse("not shown at once", insight() is InsightState.Written)
        settle()

        assertTrue("never shown again", views.none { it.track?.insight is InsightState.Written })
        assertFalse(host.state.value.cardShown)
        assertNull(host.state.value.lookAgainKey)
        assertFalse(host.state.value.nothingNew)
        assertNotNull("Write offered", host.state.value.writeKey)
        assertEquals("no call on its own", calls, provider.requests)
        tap()
        settle()
        assertEquals("a tap writes it afresh", calls + 1, provider.requests)
        assertTrue(insight() is InsightState.Written)
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
