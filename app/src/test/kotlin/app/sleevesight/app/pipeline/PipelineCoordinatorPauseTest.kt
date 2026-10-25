package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause.USER_ACTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic writing expires when the app is unattended (#203), through [PipelineCoordinator]: the
 * track changes reported while no activity is started are counted, an activity start resets the
 * count, and with *Saver* at n, n changes away are written as usual and the
 * first action expires at the next, so nothing more is written without a new tap. *Off* never
 * expires it.
 */
class PipelineCoordinatorPauseTest {
    private val cases =
        listOf(
            Case(
                "background on, 3: three tracks away are written, the fourth offers Write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "A", "B", "C", "D", "E"),
                "E",
            ) {
                pause(3)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C", "D", "E")) {
                    play(title)
                    settle()
                }
            },
            Case(
                "the default, 5: five tracks away are written, the sixth offers Write",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1, "D" to 1, "E" to 1),
                listOf("Zero", "A", "B", "C", "D", "E", "F"),
                "F",
            ) {
                pause(5)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C", "D", "E", "F")) {
                    play(title)
                    settle()
                }
            },
            Case(
                "background off, a return after 4 changes away (3 + 1) waits for a tap",
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
                "background off, a return after exactly 3 changes away keeps the first action",
                mapOf("Zero" to 1, "C" to 1),
                listOf("Zero", "C"),
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
                "background off, a return before 3 changes away keeps the first action",
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
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1, "D" to 1, "E" to 1),
                listOf("Zero", "A", "B", "C", "D", "E", "F"),
                "F",
            ) {
                pause(2)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C")) {
                    play(title)
                    settle()
                }
                // Back, and a tap on the Write C offers: written, and the first action made again.
                show()
                tap()
                settle()
                hide()
                for (title in listOf("D", "E", "F")) {
                    play(title)
                    settle()
                }
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
                (listOf("Zero", "A", "B", "C", "D", "E", "F")).associateWith { 1 },
                listOf("Zero", "A", "B", "C", "D", "E", "F"),
            ) {
                pause(3)
                acted()
                background(true)
                hide()
                for (title in listOf("A", "B", "C")) {
                    play(title)
                    settle()
                }
                show()
                hide()
                for (title in listOf("D", "E", "F")) {
                    play(title)
                    settle()
                }
            },
            Case(
                "a new length of the playing track is no track change",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "B"),
                "B",
            ) {
                pause(1)
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
                "tracks reported while a configuration change restarts the activity are not away",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                pause(1)
                acted()
                send(CoordinatorEvent.ActivityStopped(changingConfigurations = true))
                play("A")
                play("B")
                show()
                settle()
            },
            Case(
                "an action in Sleevesight while away is the first action, and starts the count again",
                mapOf("Zero" to 1, "A" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "A", "B", "C"),
            ) {
                pause(1)
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
        world.pause(1)
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
        for (title in listOf("A", "B")) {
            fresh.play(title)
            fresh.settle()
        }
        assertTrue("B offers Write", fresh.offersWriteFor("B"))
        assertFalse("before any action, nothing expired", fresh.coordinator.state.expired)
        assertFalse("before any action, not lapsed", fresh.coordinator.state.lapsed)

        // *Manual*: Write is offered whatever the first action, so not because it expired.
        val never = started()
        never.writesOn = emptySet()
        never.pause(1)
        never.acted()
        never.background(true)
        never.hide()
        for (title in listOf("A", "B")) {
            never.play(title)
            never.settle()
        }
        assertTrue("B offers Write", never.offersWriteFor("B"))
        assertTrue("the first action expired", never.coordinator.state.expired)
        assertFalse("but Write is offered under Never anyway", never.coordinator.state.lapsed)
    }

    @Test
    fun offlineAtThePauseTheRunAskingAgainOffersWriteAndSaysWhy() {
        // Offline at the pause: B decided for nothing to narrate with the first action expired, so
        // it would not have written (no would-write), only because of the expiry (paused). Back
        // online its run asking again offers Write (#192), and the view says why.
        val world = started()
        world.pause(1)
        world.acted()
        world.background(true)
        world.hide()
        world.play("A")
        world.settle()
        world.network(online = false)
        world.play("B")
        world.settle()
        val ledger = world.coordinator.state.ledger
        assertNull(ledger?.wouldWrite)
        assertTrue("paused", ledger?.paused != null)
        world.network(online = true)
        world.settle()
        assertEquals(listOf("B"), world.askedAgain)
        assertEquals(mapOf("Zero" to 1, "A" to 1), world.calls)
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because the first action expired", world.coordinator.state.lapsed)
    }

    @Test
    fun aDecisionThatWouldHaveWrittenBeforeThePauseOffersWriteAfterIt() {
        // Offline, B decided for nothing to narrate while the first action stood: it would have
        // written. A skip away and back expires the first action before B settles again, so back
        // online its run asking again offers Write, and says why.
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

    @Test
    fun theReasonForAPausedWriteIsReadUnderTheSettingsOfTheRunAskingAgain() {
        // B, offline, finds something and offers Write only because the first action expired. Back
        // on screen, the mode is set to Never, then the network returns: the run asking again offers
        // Write because of Never, so the view does not say the pause is why. Under the same mode it
        // does.
        for (never in listOf(false, true)) {
            val world = started()
            world.pause(1)
            world.acted()
            world.background(true)
            world.hide()
            world.play("A")
            world.settle()
            world.offlineFinds = true
            world.network(online = false)
            world.play("B")
            world.settle()
            assertTrue("B offers Write", world.offersWriteFor("B"))
            assertTrue("because of the pause", world.coordinator.state.lapsed)
            world.show()
            if (never) world.writesOn = emptySet()
            world.network(online = true)
            world.settle()
            assertEquals(listOf("B"), world.askedAgain)
            assertEquals(mapOf("Zero" to 1, "A" to 1), world.calls)
            assertTrue("B offers Write again, never = $never", world.offersWriteFor("B"))
            assertEquals("lapsed, never = $never", !never, world.coordinator.state.lapsed)
        }
    }

    @Test
    fun aTapKeptForARunAskingAgainIsDroppedWhenTheFirstActionExpires() {
        // A, offline before any action, offers Write; back online its run asks again, slowly, and a
        // tap shown for A is kept for it (#192). The app is left, and six tracks are skipped
        // through and back to A before its evidence arrives: the first action expired at the
        // sixth, and the kept tap with it, so A offers Write and nothing is paid. With the pause
        // off, the kept tap writes A, as before.
        for (after in listOf(5, null)) {
            val world = started()
            world.pause(after)
            world.offlineFinds = true
            world.network(online = false)
            world.play("A")
            world.settle()
            assertTrue("A offers Write", world.offersWriteFor("A"))
            world.lookupMs = 5_000
            world.network(online = true)
            world.tap(world.key("A"))
            assertTrue(
                "the tap kept",
                world.coordinator.state.run
                    ?.tapped == true,
            )
            world.background(true)
            world.hide()
            for (title in listOf("B", "C", "D", "E", "F", "A")) {
                world.play(title)
                world.advance(400)
            }
            world.settle()
            assertEquals(listOf("A"), world.askedAgain)
            if (after == null) {
                assertEquals(mapOf("A" to 1), world.calls)
            } else {
                assertEquals(emptyMap<String, Int>(), world.calls)
                assertTrue("A offers Write", world.offersWriteFor("A"))
            }
        }
    }

    private companion object {
        val TEN = (1..10).map { "T$it" }
    }
}
