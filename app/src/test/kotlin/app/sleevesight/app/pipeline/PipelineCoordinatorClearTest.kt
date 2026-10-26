package app.sleevesight.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * *Clear history* in the coordinator (#547): the run, the preparation and the ledger go, and the
 * track playing is not looked up again, by a report of it or a rebuild, until another one plays.
 */
class PipelineCoordinatorClearTest {
    private fun World.clear() {
        send(CoordinatorEvent.Cleared)
        advance(0)
    }

    private val cases =
        listOf(
            Case(
                "a clear empties the ledger; the track playing runs again only after another one (written: acted)",
                mapOf("A" to 1, "B" to 1),
                listOf("A", "B"),
            ) {
                play("A", seconds = 106)
                settle()
                tap()
                settle()
                clear()
                assertNull(coordinator.state.ledger)
                assertNull(coordinator.state.run)
                assertEquals(1L, coordinator.state.clears)
                play("A", seconds = 279)
                settle()
                play("B")
                settle()
            },
            Case(
                "a clear during the model call cancels the run, and records nothing of it",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                streamMs = 3_000
                play("A")
                settle()
                tap()
                advance(1_000)
                clear()
                settle()
                assertNull(coordinator.state.ledger)
                assertNull(coordinator.state.run)
                assertNull(cardText())
            },
            Case(
                "a rebuild after a clear does not run the track playing",
                emptyMap(),
                listOf("A", "B"),
                "B",
            ) {
                play("A")
                settle()
                clear()
                keyChange()
                settle()
                assertEquals(listOf("A"), lookups)
                play("B")
                settle()
            },
            Case(
                "the track playing comes back as a new decision once another one has played",
                emptyMap(),
                listOf("A", "B", "A"),
                "A",
            ) {
                play("A")
                settle()
                clear()
                play("B")
                settle()
                play("A")
                settle()
            },
        )

    @Test
    fun theCasesHold() = checkAll(cases)
}
