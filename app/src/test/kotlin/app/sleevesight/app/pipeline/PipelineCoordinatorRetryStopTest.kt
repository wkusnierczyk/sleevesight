package app.sleevesight.app.pipeline

import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A model call that failed, and Stop (#163), as `PipelineCoordinatorTest` runs its sequences:
 * Write offered again after a call that failed or was stopped, a tap on it a new call, never one
 * unasked; Stop for the call it was shown for alone.
 */
class PipelineCoordinatorRetryStopTest {
    private val cases =
        listOf(
            Case(
                "a call that fails offers Write again: a tap is a new call, the grants never make one",
                mapOf("A" to 2),
                listOf("A"),
            ) {
                failing = true
                play("A")
                settle()
                tap()
                settle()
                assertEquals(mapOf("A" to 1), calls)
                assertTrue(offersWriteFor("A"))
                // The first action is made, and A is a track change written on: still nothing unasked.
                settle()
                assertEquals(mapOf("A" to 1), calls)
                failing = false
                tap()
                settle()
            },
            Case(
                "an automatic call that fails is never made again unasked, a rebuild's run included",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B", "B"),
                "B",
            ) {
                acted()
                failing = true
                play("B")
                settle()
                assertTrue(offersWriteFor("B"))
                keyChange()
                settle()
            },
            Case(
                "a new length of a track whose call failed starts nothing, and Write stays offered",
                mapOf("A" to 1),
                listOf("A"),
                "A",
            ) {
                failing = true
                play("A", seconds = 106)
                settle()
                tap()
                settle()
                play("A", seconds = 279)
                settle()
            },
            Case("a tap to call again for a track left does nothing", mapOf("A" to 1), listOf("A", "B"), "B") {
                writesOn = mode(WriteAutomatically.NEVER)
                failing = true
                play("A")
                settle()
                tap()
                settle()
                val offered = checkNotNull(coordinator.state.writeKey)
                play("B")
                tap(offered)
                settle()
            },
            Case(
                "Stop ends the call under way, and offers Write again; nothing is written again unasked",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                streamMs = 10_000
                play("A")
                settle()
                assertEquals(CallId(checkNotNull(coordinator.state.run).id, 1), coordinator.state.stopCall)
                stopTap()
                assertEquals(listOf("A"), stopped)
                assertEquals(null, coordinator.state.stopCall)
                assertEquals(true, coordinator.state.ledger?.written)
                assertEquals(null, cardText())
                assertTrue(offersWriteFor("A"))
                settle()
                keyChange()
                settle()
            },
            Case(
                "Stop stays offered while a rebuild waits for the call, and ending it lets the swap go on",
                mapOf("A" to 1),
                listOf("A", "A"),
                "A",
            ) {
                streamMs = 60_000
                play("A")
                settle()
                tap()
                advance(2_000)
                keyChange()
                assertEquals(null, coordinator.state.current)
                assertEquals(CallId(checkNotNull(coordinator.state.run).id, 1), coordinator.state.stopCall)
                stopTap()
                settle()
                assertEquals(listOf("A"), stopped)
                // The rebuild's run of the written track finds no card: Write offered, never a call.
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case("a tap on Write after Stop is a new call", mapOf("A" to 2), listOf("A")) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(2_000)
                stopTap()
                tap()
                advance(30_000)
                assertEquals("A #2", cardText())
            },
            Case(
                "Stop for another run's call, or with no call under way, does nothing",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                assertEquals(null, coordinator.state.stopCall)
                val run = checkNotNull(coordinator.state.run).id
                stopTap(CallId(run, 1))
                tap()
                advance(2_000)
                stopTap(CallId(RunId(run.value + 1), 1))
                stopTap(CallId(run, 2))
                advance(30_000)
                assertEquals(emptyList<String>(), stopped)
                assertEquals("A #1", cardText())
                stopTap(CallId(run, 1))
                assertEquals("A #1", cardText())
            },
            Case(
                "an automatic call that fails, a tap's call after it, and a late Stop for the first: nothing stopped",
                mapOf("Zero" to 1, "A" to 2),
                listOf("Zero", "A"),
            ) {
                acted()
                streamMs = 10_000
                failing = true
                play("A")
                advance(DEBOUNCE_MS + 1_000)
                // Written automatically: the decision to write is the run's first call.
                val first = checkNotNull(coordinator.state.stopCall)
                assertEquals(1, first.number)
                advance(30_000)
                assertTrue(offersWriteFor("A"))
                failing = false
                tap()
                advance(2_000)
                assertEquals(first.copy(number = 2), coordinator.state.stopCall)
                stopTap(first)
                advance(30_000)
                assertEquals(emptyList<String>(), stopped)
                assertEquals("A #2", cardText())
            },
            Case(
                "a Stop shown for an earlier call, delivered after the next call started, stops nothing",
                mapOf("A" to 2),
                listOf("A"),
            ) {
                streamMs = 10_000
                failing = true
                play("A")
                settle()
                tap()
                val first = checkNotNull(coordinator.state.stopCall)
                advance(30_000)
                assertTrue(offersWriteFor("A"))
                failing = false
                tap()
                advance(2_000)
                assertEquals(first.copy(number = 2), coordinator.state.stopCall)
                // The screen's Stop for the first call, late: the second goes on to its card.
                stopTap(first)
                advance(30_000)
                assertEquals(emptyList<String>(), stopped)
                assertEquals("A #2", cardText())
            },
        )

    @Test
    fun eachRetryAndStopSequenceGrantsOnlyWhatTheRulesAllow() = checkAll(cases)

    @Test
    fun anOldRunsRetryAskedChangesNothing() {
        val world = started()
        world.play("A")
        world.settle()
        world.tap()
        world.settle()
        val old = checkNotNull(world.coordinator.state.run).id

        // A rebuild swaps in a new run, which waits for its lookups; the old run's question
        // whether to call again, queued before it was cancelled, comes after the swap.
        world.lookupMs = 10_000
        world.keyChange()
        val before = world.coordinator.state
        assertTrue(checkNotNull(before.run).id != old)
        val effects = world.coordinator.on(CoordinatorEvent.RetryAsked(old))

        assertEquals(emptyList<CoordinatorEffect>(), effects)
        assertEquals(before, world.coordinator.state)
        assertEquals(null, world.coordinator.state.writeKey)
        assertEquals(null, world.coordinator.state.stopCall)
    }
}
