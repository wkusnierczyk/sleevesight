package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause.USER_ACTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic writing expires when the app is unattended (#203), through [PipelineCoordinator]: the
 * track changes reported while no activity is started are counted, an activity start resets the
 * count, and at the count *Pause automatic writing after* names the first action expires, so
 * nothing more is written without a new tap. *Off* never expires it.
 */
class PipelineCoordinatorPauseTest {
    private val cases =
        listOf(
            Case(
                "background on, 3 tracks away: the first two are written, the third offers Write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1),
                listOf("Zero", "A", "B", "C", "D"),
                "D",
            ) {
                pause(3)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C", "D")) {
                    play(title)
                    settle()
                }
            },
            Case(
                "the default, 5 tracks away: four are written, the fifth offers Write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1, "D" to 1),
                listOf("Zero", "A", "B", "C", "D", "E"),
                "E",
            ) {
                pause(5)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C", "D", "E")) {
                    play(title)
                    settle()
                }
            },
            Case(
                "background off, a return after 3 or more tracks away waits for a tap",
                mapOf("Zero" to 1),
                listOf("Zero", "D"),
                "D",
            ) {
                pause(3)
                acted()
                hide()
                for (title in listOf("A", "B", "C", "D")) {
                    play(title)
                    settle()
                }
                show()
                settle()
            },
            Case(
                "background off, a return after exactly 3 tracks away waits for a tap",
                mapOf("Zero" to 1),
                listOf("Zero", "C"),
                "C",
            ) {
                pause(3)
                acted()
                hide()
                for (title in listOf("A", "B", "C")) {
                    play(title)
                    settle()
                }
                show()
                settle()
            },
            Case(
                "background off, a return before 3 tracks away keeps the first action",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                pause(3)
                acted()
                hide()
                play("A")
                settle()
                play("B")
                settle()
                show()
                settle()
            },
            Case(
                "Off: the first action never expires",
                (listOf("Zero") + TEN).associateWith { 1 },
                listOf("Zero") + TEN,
            ) {
                pause(null)
                acted()
                background(true)
                hide()
                for (title in TEN) {
                    play(title)
                    settle()
                }
            },
            Case(
                "a tap after the pause writes, and automatic writing goes on, counted afresh",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "A", "B", "C", "D", "E"),
                "E",
            ) {
                pause(2)
                acted()
                background(true)
                hide()
                play("A")
                settle()
                play("B")
                settle()
                // Back, and a tap on the Write B offers: written, and the first action made again.
                show()
                tap()
                settle()
                hide()
                play("C")
                settle()
                play("D")
                settle()
                play("E")
                settle()
            },
            Case(
                "the count does not run while the app is on screen",
                (listOf("Zero") + TEN).associateWith { 1 },
                listOf("Zero") + TEN,
            ) {
                pause(2)
                acted()
                for (title in TEN) {
                    play(title)
                    settle()
                }
            },
            Case(
                "a return to the app starts the count again",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1, "D" to 1),
                listOf("Zero", "A", "B", "C", "D"),
            ) {
                pause(3)
                acted()
                background(true)
                hide()
                play("A")
                settle()
                play("B")
                settle()
                show()
                hide()
                play("C")
                settle()
                play("D")
                settle()
            },
            Case(
                "a new length of the playing track is no track change",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "B"),
                "B",
            ) {
                pause(2)
                acted()
                background(true)
                hide()
                play("A", seconds = 100)
                settle()
                play("A", seconds = 200)
                settle()
                play("B")
                settle()
            },
            Case(
                "a track reported while a configuration change restarts the activity is not away",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A"),
            ) {
                pause(1)
                acted()
                send(CoordinatorEvent.ActivityStopped(changingConfigurations = true))
                play("A")
                show()
                settle()
            },
            Case(
                "an action in Sleevesight while away is the first action, and starts the count again",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "A", "B", "C"),
            ) {
                pause(2)
                acted()
                background(true)
                hide()
                play("A")
                settle()
                play("B", USER_ACTION)
                settle()
                play("C")
                settle()
            },
        )

    @Test
    fun theFirstActionExpiresAfterTheTrackChangesAway() = checkAll(cases)

    @Test
    fun theHomeScreenIsToldWhyWriteIsOffered() {
        val world = started()
        world.pause(2)
        world.acted()
        world.background(true)
        world.hide()
        world.play("A")
        world.settle()
        assertFalse("written, not offered", world.coordinator.state.lapsed)
        world.play("B")
        world.settle()
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because the first action expired", world.coordinator.state.lapsed)

        // Back on screen: still offered, still for that reason; a tap writes it and clears it.
        world.show()
        assertTrue("on return", world.coordinator.state.lapsed)
        world.tap()
        assertFalse("tapped", world.coordinator.state.lapsed)
        world.settle()
        assertEquals(1, world.calls["B"])
        assertFalse("written", world.coordinator.state.expired)
    }

    @Test
    fun writeOfferedForAnotherReasonIsNotLapsed() {
        // No first action yet: the count runs, but there is nothing to expire.
        val fresh = started()
        fresh.pause(1)
        fresh.background(true)
        fresh.hide()
        fresh.play("A")
        fresh.settle()
        assertTrue("A offers Write", fresh.offersWriteFor("A"))
        assertFalse("before any action, nothing expired", fresh.coordinator.state.expired)
        assertFalse("before any action, not lapsed", fresh.coordinator.state.lapsed)

        // *Never*: Write is offered whatever the first action, so not because it expired.
        val never = started()
        never.writesOn = emptySet()
        never.pause(1)
        never.acted()
        never.background(true)
        never.hide()
        never.play("A")
        never.settle()
        assertTrue("A offers Write", never.offersWriteFor("A"))
        assertTrue("the first action expired", never.coordinator.state.expired)
        assertFalse("but Write is offered under Never anyway", never.coordinator.state.lapsed)
    }

    @Test
    fun anExpiredFirstActionMeansTheRunAskingAgainOffersWrite() {
        // Offline at the pause: the track decided for nothing to narrate, with no first action,
        // so back online its run asking again offers Write (#192).
        val world = started()
        world.pause(2)
        world.acted()
        world.background(true)
        world.hide()
        world.play("A")
        world.settle()
        world.network(online = false)
        world.play("B")
        world.settle()
        assertEquals(
            null,
            world.coordinator.state.ledger
                ?.wouldWrite,
        )
        world.network(online = true)
        world.settle()
        assertEquals(listOf("B"), world.askedAgain)
        assertEquals(mapOf("Zero" to 1, "A" to 1), world.calls)
        assertTrue("B offers Write", world.offersWriteFor("B"))
    }

    @Test
    fun aDecisionThatWouldHaveWrittenBeforeThePauseOffersWriteAfterIt() {
        // Offline, B decided for nothing to narrate while the first action stood: it would have
        // written. A skip away and back expires the first action before B settles again, so back
        // online its run asking again offers Write, and says why.
        val world = started()
        world.pause(3)
        world.acted()
        world.background(true)
        world.hide()
        world.play("A")
        world.settle()
        world.network(online = false)
        world.play("B")
        world.settle()
        assertTrue(
            "would have written",
            world.coordinator.state.ledger
                ?.wouldWrite != null,
        )
        world.play("C")
        world.advance(100)
        world.play("B")
        world.settle()
        assertFalse("expired", world.coordinator.state.acted)
        world.network(online = true)
        world.settle()
        assertEquals(listOf("B"), world.askedAgain)
        assertEquals(mapOf("Zero" to 1, "A" to 1), world.calls)
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because the first action expired", world.coordinator.state.lapsed)
    }

    private companion object {
        val TEN = (1..10).map { "T$it" }
    }
}
