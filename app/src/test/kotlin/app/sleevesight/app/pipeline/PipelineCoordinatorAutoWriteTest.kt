package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause.NATURAL_END
import app.sleevesight.core.pipeline.ChangeCause.SKIP_IN_PLAYER
import app.sleevesight.core.pipeline.ChangeCause.USER_ACTION
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Write insights automatically* in each of its modes (D5; architecture, *Writing insights
 * automatically*), through [PipelineCoordinator]: which changes write without a tap, by the cause
 * the session layer gives the latest report, and nothing before the user's first action since the
 * app started: a cold start, a new process, with a new coordinator; a return to the app keeps it,
 * however long the app was away (#190).
 */
class PipelineCoordinatorAutoWriteTest {
    private val cases =
        listOf(
            Case(
                "on every track change: after the first action, a natural end, a skip and an action each write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "A", "B", "C"),
            ) {
                acted()
                play("A")
                settle()
                play("B", SKIP_IN_PLAYER)
                settle()
                play("C", USER_ACTION)
                settle()
            },
            Case(
                "when I skip: a skip in the player and an action in Sleevesight write; a natural end offers Write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1),
                listOf("Zero", "A", "B", "C"),
                "C",
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                acted()
                play("A", SKIP_IN_PLAYER)
                settle()
                play("B", USER_ACTION)
                settle()
                play("C")
                settle()
            },
            Case(
                "when I skip: Write on a natural end writes it, and the next skip writes again",
                mapOf("Zero" to 1, "A" to 1, "B" to 1),
                listOf("Zero", "A", "B"),
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                acted()
                play("A")
                settle()
                tap()
                settle()
                play("B", SKIP_IN_PLAYER)
                settle()
            },
            Case(
                "never: nothing is written without a tap, a skip and an action included",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "B"),
                "B",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                play("A", SKIP_IN_PLAYER)
                settle()
                play("B", USER_ACTION)
                settle()
            },
            Case(
                "when I skip: an action in Sleevesight is the first action, and writes its track",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                play("A", USER_ACTION)
                settle()
            },
            Case(
                "when I skip: the latest report's cause decides, a skip back to the track during its run included",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                acted()
                lookupMs = 2_000
                // B ends A on its own; during B's lookups the user skips back, then to B again.
                play("B")
                advance(DEBOUNCE_MS + 500)
                play("A", SKIP_IN_PLAYER)
                advance(100)
                play("B", SKIP_IN_PLAYER)
                settle()
            },
        )

    @Test
    fun eachModeWritesTheChangesD5Says() = checkAll(cases)

    @Test
    fun nothingIsWrittenBeforeTheFirstActionInAnyMode() {
        for (each in WriteAutomatically.entries) {
            val world = started()
            world.writesOn = mode(each)
            world.play("A", SKIP_IN_PLAYER)
            world.settle()
            assertEquals("$each, before any action", emptyMap<String, Int>(), world.calls)
            assertTrue("$each, Write offered", world.offersWriteFor("A"))

            // An action, then a return to the app: the action stands, and the mode decides.
            world.tap()
            world.settle()
            world.hide()
            world.show()
            world.play("B", SKIP_IN_PLAYER)
            world.settle()
            val skips = SKIP_IN_PLAYER in mode(each)
            val expected = if (skips) mapOf("A" to 1, "B" to 1) else mapOf("A" to 1)
            assertEquals("$each, after a return to the app", expected, world.calls)
            assertEquals("$each, Write offered after a return", !skips, world.offersWriteFor("B"))
        }
    }

    // A start after a long absence is a return, not an opening (#190): hours away, several track
    // changes reported, then the app shown again. The pause (#203) is set to *Off*, so the first
    // action cannot expire and the test is about the start alone; the pause has its own tests.
    @Test
    fun aStartAfterALongAbsenceKeepsTheFirstActionInEveryMode() {
        for (each in WriteAutomatically.entries) {
            for (cause in listOf(NATURAL_END, SKIP_IN_PLAYER)) {
                val world = started()
                world.writesOn = mode(each)
                world.pause(null)
                world.acted()
                world.hide()
                for (title in listOf("A", "B", "C", "D", "E", "F", "G")) {
                    world.advance(HOUR_MS)
                    world.play(title, cause)
                    world.settle()
                }
                world.advance(HOUR_MS)
                world.show()
                world.settle()
                val writes = cause in mode(each)
                val expected = if (writes) mapOf("Zero" to 1, "G" to 1) else mapOf("Zero" to 1)
                assertEquals("$each, $cause, after a long absence", expected, world.calls)
                assertEquals("$each, $cause, Write offered", !writes, world.offersWriteFor("G"))
            }
        }
    }

    @Test
    fun aTapTakenIsAnActionAndOneNotTakenIsNot() {
        val world = started()
        world.tap()
        assertEquals("nothing offered", 0, world.actions)
        world.play("A")
        world.settle()
        world.tap()
        assertEquals("Write taken", 1, world.actions)
        world.tap()
        assertEquals("written already", 1, world.actions)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
