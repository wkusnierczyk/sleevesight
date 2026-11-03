package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause.USER_ACTION
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a track would be written by itself (#522, `CoordinatorState.writesItself`), which the song's,
 * the artist's and the album's insights ask before a call they make by themselves: by the rules
 * alone, whatever the key insight came to.
 */
class PipelineCoordinatorItemGateTest {
    @Test
    fun aKeyInsightFromTheCacheUnderAnExpiredSaverDoesNotLetTheItemsWriteByThemselves() {
        val world = started()
        world.cache = true
        world.pause(1)
        world.acted()
        world.background(true)
        world.play("B")
        world.settle()
        assertTrue("on screen, acted: written by itself", world.coordinator.state.writesItself)

        world.hide()
        world.play("A")
        world.settle()
        world.play("B")
        world.settle()
        // B's insight is served from the cache: no Write offered, so not lapsed, yet the first action
        // expired, and nothing is written by itself.
        assertFalse("B offers no Write", world.offersWriteFor("B"))
        assertFalse(world.coordinator.state.lapsed)
        assertFalse(world.coordinator.state.writesItself)
    }

    @Test
    fun underOnTrackSkipATrackThatFollowedByItselfIsNotWrittenByItself() {
        val world = started()
        world.writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
        // As the host tells it, from the settings.
        world.send(CoordinatorEvent.WritingModeChanged(WriteAutomatically.WHEN_I_SKIP))
        world.acted()
        world.play("A")
        world.settle()
        assertFalse("a natural end", world.coordinator.state.writesItself)
        world.play("B", USER_ACTION)
        world.settle()
        assertTrue("a skip", world.coordinator.state.writesItself)
    }

    @Test
    fun beforeTheFirstActionNothingIsWrittenByItself() {
        val world = started()
        world.play("A")
        world.settle()
        assertFalse(world.coordinator.state.writesItself)
    }
}
