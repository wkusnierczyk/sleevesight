package app.sleevesight.core.sources

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A run's allowance for waiting out a busy MusicBrainz (#1120): waits that overlap count once, what
 * is left never goes below zero, and the run's work finds it in its context.
 */
class BusyWaitsTest {
    @Test
    fun `waits that overlap count once, one after another add up`() =
        runTest {
            val busy = BusyWaits(15.seconds, testTimeSource)
            val first = launch { busy.waiting { delay(4.seconds) } }
            val second = launch { busy.waiting { delay(2.seconds) } }
            first.join()
            second.join()
            assertEquals(4.seconds, busy.waited)
            busy.waiting { delay(3.seconds) }
            assertEquals(7.seconds, busy.waited)
            assertEquals(8.seconds, busy.left)
            assertFalse(busy.waitingNow)
        }

    @Test
    fun `what is left is never below zero, and a wait under way counts as it goes`() =
        runTest {
            val busy = BusyWaits(5.seconds, testTimeSource)
            val waiting = launch { busy.waiting { delay(8.seconds) } }
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()
            assertTrue(busy.waitingNow)
            assertEquals(3.seconds, busy.waited)
            waiting.join()
            assertEquals(Duration.ZERO, busy.left)
            busy.idle()
        }

    @Test
    fun `the run's work finds its allowance, other work none`() =
        runTest {
            val busy = BusyWaits(15.seconds, testTimeSource)
            withContext(busy) { assertSame(busy, BusyWaits.current()) }
            assertNull(BusyWaits.current())
        }
}
