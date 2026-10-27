package app.sleevesight.app.pipeline

import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * *Reactive* turned to *On demand* while the run's own settings still write automatically (#553:
 * `WritingModeChanged`), through [PipelineCoordinator]: a decision for nothing to narrate
 * leaves neither a would-write nor a pause in the ledger, so the run asking again when the network
 * returns offers Write rather than writing.
 */
class PipelineCoordinatorAutomaticWritingTest {
    private val cases =
        listOf(
            Case(
                "On demand since the run started, nothing to narrate offline: back online, Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                send(CoordinatorEvent.WritingModeChanged(WriteAutomatically.NEVER))
                network(online = false)
                play("A")
                settle()
                assertNull(coordinator.state.ledger?.wouldWrite)
                assertNull(coordinator.state.ledger?.paused)
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
                val run = checkNotNull(coordinator.state.current).id
                assertEquals(
                    listOf(false),
                    effects
                        .filterIsInstance<CoordinatorEffect.Decide>()
                        .filter {
                            it.run == run
                        }.map { it.write },
                )
            },
            Case(
                "On demand while Saver has paused, nothing to narrate: no pause in the ledger",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B", "C"),
                // Nothing to write from, offline: no Write either.
                null,
            ) {
                pause(1)
                acted()
                background(true)
                hide()
                play("B")
                settle()
                send(CoordinatorEvent.WritingModeChanged(WriteAutomatically.NEVER))
                network(online = false)
                play("C")
                settle()
                assertEquals(true, coordinator.state.expired)
                assertNull(coordinator.state.ledger?.paused)
            },
        )

    @Test
    fun `turned to On demand, a decision for nothing to narrate leaves no would-write or pause`() = checkAll(cases)
}
