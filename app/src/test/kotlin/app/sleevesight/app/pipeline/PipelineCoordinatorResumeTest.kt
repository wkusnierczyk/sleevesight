package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause.SKIP_IN_PLAYER
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How automatic writing starts and resumes now that no button does it (#625), through
 * [PipelineCoordinator]: choosing an automatic mode is the first action (D5), *On demand* is not;
 * opening Sleevesight resumes what *Saver* paused, but a track that came to play while it was
 * paused still offers Write, and says why, rather than being paid for unasked; and a track that
 * followed the last one by itself under *On track skip (auto)* offers Write, and says why.
 */
class PipelineCoordinatorResumeTest {
    @Test
    fun anAutomaticModeChosenIsTheFirstAction() {
        val world = started()
        world.play("A")
        world.settle()
        assertTrue("before any action, A offers Write", world.offersWriteFor("A"))
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.ON_EVERY_TRACK_CHANGE))
        world.advance(0)
        assertTrue("the first action made", world.coordinator.state.acted)
        // A, decided already, is not written by the choice; the next track is, automatically.
        assertTrue("A still offers Write", world.offersWriteFor("A"))
        world.play("B")
        world.settle()
        assertEquals(mapOf("B" to 1), world.calls)
    }

    @Test
    fun onDemandChosenIsNoAction() {
        val world = started()
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.NEVER))
        world.advance(0)
        assertFalse(world.coordinator.state.acted)
        world.play("A")
        world.settle()
        assertEquals(emptyMap<String, Int>(), world.calls)
        assertTrue("A offers Write", world.offersWriteFor("A"))
    }

    @Test
    fun anAutomaticModeChosenWhilePausedResumesAutomaticWriting() {
        val world = paused(background = true)
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.ON_EVERY_TRACK_CHANGE))
        world.advance(0)
        assertFalse("no longer paused", world.coordinator.state.expired)
        world.play("C")
        world.settle()
        assertEquals(1, world.calls["C"])
    }

    @Test
    fun openingTheAppResumesAutomaticWritingButNotForTheTrackThatCameWhilePaused() {
        // Looked up in the background: B was decided while paused, and offers Write.
        val world = paused(background = true)
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because of the pause", world.coordinator.state.lapsed)
        world.show()
        assertTrue("the first action made again", world.coordinator.state.acted)
        assertFalse("no longer paused", world.coordinator.state.expired)
        assertTrue("B still offers Write", world.offersWriteFor("B"))
        assertTrue("and says why", world.coordinator.state.lapsed)
        assertTrue("the card does not say it writes automatically", world.coordinator.state.waitsForTap)
        world.play("C")
        world.settle()
        assertEquals(mapOf("Zero" to 1, "A" to 1, "C" to 1), world.calls)
        assertFalse(world.coordinator.state.waitsForTap)
    }

    @Test
    fun withoutBackgroundLookupsTheTrackPlayingAtTheReturnIsNotWrittenUnasked() {
        // Nothing is looked up away: B's run decides only once the app is on screen again, after
        // the opening has resumed automatic writing, and still offers Write, as it came to play
        // while it was paused.
        val world = paused(background = false)
        world.show()
        world.settle()
        assertEquals(mapOf("Zero" to 1), world.calls)
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because of the pause", world.coordinator.state.lapsed)
        world.tap()
        world.settle()
        assertEquals("a tap writes it", 1, world.calls["B"])
        world.play("C")
        world.settle()
        assertEquals("resumed", 1, world.calls["C"])
    }

    @Test
    fun theSameTrackReportedAgainAfterTheReturnStaysUnwritten() {
        // A new length of B after the return is no track change: it came to play while paused.
        val world = paused(background = false)
        world.show()
        world.play("B", seconds = 300)
        world.settle()
        assertEquals(null, world.calls["B"])
        assertTrue("B offers Write", world.coordinator.state.offersWrite(world.key("B", seconds = 300)))
        assertTrue("because of the pause", world.coordinator.state.lapsed)
    }

    @Test
    fun aTrackThatFollowedTheLastOneByItselfUnderOnTrackSkipOffersWriteAndSaysWhy() {
        val world = started()
        world.writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
        world.acted()
        world.play("A")
        world.settle()
        assertTrue("A offers Write", world.offersWriteFor("A"))
        assertTrue("because it followed by itself", world.coordinator.state.followed)
        assertFalse(world.coordinator.state.lapsed)
        world.play("B", SKIP_IN_PLAYER)
        world.settle()
        assertEquals(1, world.calls["B"])
        assertFalse("written", world.coordinator.state.followed)
    }

    @Test
    fun followedIsNotSaidForAnotherReason() {
        // Before the first action: Write is offered for that, whatever the cause.
        val fresh = started()
        fresh.writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
        fresh.play("A")
        fresh.settle()
        assertTrue(fresh.offersWriteFor("A"))
        assertFalse(fresh.coordinator.state.followed)
        // Under On demand: nothing writes automatically.
        val never = started()
        never.writesOn = mode(WriteAutomatically.NEVER)
        never.acted()
        never.play("A")
        never.settle()
        assertTrue(never.offersWriteFor("A"))
        assertFalse(never.coordinator.state.followed)
    }

    @Test
    fun aModeChosenBeforeTheFirstActionAppliesFromTheNextTrack() {
        // Codex's sequence: A still looked up under the default when On track skip (auto) is
        // chosen, before any action. A is not written, though its run's settings would have; the
        // next track the mode writes on is.
        val world = started()
        world.lookupMs = 5_000
        world.play("A")
        world.advance(DEBOUNCE_MS + 100)
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.WHEN_I_SKIP))
        world.advance(0)
        world.settle()
        assertEquals(emptyMap<String, Int>(), world.calls)
        assertTrue("A offers Write", world.offersWriteFor("A"))
        world.lookupMs = 0
        world.play("B", SKIP_IN_PLAYER)
        world.settle()
        assertEquals(mapOf("B" to 1), world.calls)
    }

    @Test
    fun aModeChosenWhileATrackIsLookedUpAppliesFromTheNextTrack() {
        // The first action made already: the mode chosen again leaves the track playing to a tap.
        val world = started()
        world.acted()
        world.lookupMs = 5_000
        world.play("A")
        world.advance(DEBOUNCE_MS + 100)
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.ON_EVERY_TRACK_CHANGE))
        world.advance(0)
        world.settle()
        assertEquals(null, world.calls["A"])
        assertTrue("A offers Write", world.offersWriteFor("A"))
        world.play("B")
        world.settle()
        assertEquals(1, world.calls["B"])
    }

    @Test
    fun theModeSelectedNowNarrowsARunStartedUnderAnother() {
        // On track skip (auto) chosen, its save not yet read by the runs: a run started after it
        // still has On track change (auto)'s settings, but a track that followed by itself is
        // not written, and says why; a skip is.
        val world = started()
        world.acted()
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.WHEN_I_SKIP))
        world.advance(0)
        world.play("A")
        world.settle()
        assertEquals(null, world.calls["A"])
        assertTrue("A offers Write", world.offersWriteFor("A"))
        assertTrue("because it followed by itself", world.coordinator.state.followed)
        world.play("B", SKIP_IN_PLAYER)
        world.settle()
        assertEquals(1, world.calls["B"])
        // And the setting's change, once read, narrows the same way.
        val read = started()
        read.acted()
        read.send(CoordinatorEvent.WritingModeChanged(WriteAutomatically.WHEN_I_SKIP))
        read.advance(0)
        read.play("A")
        read.settle()
        assertEquals(null, read.calls["A"])
    }

    @Test
    fun aModeChosenWhileAPausedTracksRunIsLookedUpLeavesItToATapAndSaysWhy() {
        val world = started()
        world.pause(1)
        world.acted()
        world.background(true)
        world.hide()
        world.play("A")
        world.settle()
        world.lookupMs = 5_000
        world.play("B")
        world.advance(DEBOUNCE_MS + 100)
        assertTrue("paused", world.coordinator.state.expired)
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.ON_EVERY_TRACK_CHANGE))
        world.advance(0)
        assertFalse("resumed", world.coordinator.state.expired)
        world.settle()
        assertEquals(null, world.calls["B"])
        assertTrue("B offers Write", world.offersWriteFor("B"))
        assertTrue("because of the pause", world.coordinator.state.lapsed)
    }

    @Test
    fun theFirstReportOfTheProcessIsNotSaidToHaveFollowed() {
        // A mode chosen before anything played: the first track, a natural end by default, did not
        // follow another, so the card does not say it did.
        val world = started()
        world.writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
        world.send(CoordinatorEvent.ModeChosen(WriteAutomatically.WHEN_I_SKIP))
        world.advance(0)
        world.play("A")
        world.settle()
        assertTrue("A offers Write", world.offersWriteFor("A"))
        assertFalse(world.coordinator.state.followed)
    }

    @Test
    fun aTapClearsWhatTheTrackWaitedFor() {
        val world = paused(background = true)
        world.show()
        assertTrue(world.coordinator.state.waitsForTap)
        world.tap()
        assertFalse("tapped", world.coordinator.state.waitsForTap)
        assertEquals(
            false,
            world.coordinator.state.latest
                ?.whilePaused,
        )
    }

    // The first action made on Zero (a tap), the app left, with *Saver* at one change away: A is
    // written as usual, if looked up away ([background]), and B, the change after, expires it.
    private fun paused(background: Boolean): World =
        started().apply {
            pause(1)
            acted()
            background(background)
            hide()
            play("A")
            settle()
            play("B")
            settle()
            assertTrue("paused", coordinator.state.expired)
        }
}
