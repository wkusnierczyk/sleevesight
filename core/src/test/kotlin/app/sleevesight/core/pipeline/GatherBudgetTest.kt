package app.sleevesight.core.pipeline

import app.sleevesight.core.sources.BusyWaits
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gather's budget across its two calls, the sources' and then the search in the track's language
 * (#983, #1120): the time spent waiting out a busy MusicBrainz is credited to the call it was spent
 * in, up to its end, and to no other.
 */
class GatherBudgetTest {
    @Test
    fun `waiting up to the end of the first call is credited to it, not carried past its deficit into the next`() =
        runTest {
            val busy = BusyWaits(15.seconds, testTimeSource)
            val budget = GatherBudget(testTimeSource, busy = busy)

            // 19 s of lookups, then 14 s waiting out MusicBrainz, ended when the wait ends.
            val first =
                budget.within {
                    delay(19.seconds)
                    busy.waiting { delay(14.seconds) }
                }
            assertNull(first)
            assertEquals(33.seconds, testScheduler.currentTime.milliseconds)

            // 20 s and the 14 s waited, 34 s in all: 1 s left for the next.
            val second = budget.within { awaitCancellation() }
            assertEquals(1.seconds, second)
            assertEquals(34.seconds, testScheduler.currentTime.milliseconds)
        }

    @Test
    fun `waiting between the calls, while no budget runs, is credited to neither`() =
        runTest {
            val busy = BusyWaits(15.seconds, testTimeSource)
            val budget = GatherBudget(testTimeSource, busy = busy)

            assertNull(budget.within { delay(5.seconds) })
            busy.waiting { delay(10.seconds) }
            val cut = budget.within { awaitCancellation() }

            // 15 s left of the 20, nothing added for the 10 s waited between.
            assertEquals(15.seconds, cut)
            assertEquals(30.seconds, testScheduler.currentTime.milliseconds)
        }
}
