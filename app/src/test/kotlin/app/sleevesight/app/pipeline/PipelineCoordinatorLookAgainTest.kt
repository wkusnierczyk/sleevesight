package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Look again* on the card paid for (#617), as `PipelineCoordinatorTest` runs its sequences: a run
 * that looks every source up afresh, past the cache, with what the run before gathered, and never
 * calls the AI service itself: when its evidence says something the card's does not (spacing aside),
 * *Rewrite insight* is offered, and its tap alone writes; when not, nothing is offered. Never a
 * call beside automatic writing; a track change cuts it off; Stop ends the Rewrite as any Write.
 */
class PipelineCoordinatorLookAgainTest {
    // The first action made, and A written automatically, its sources landed.
    private fun written(cache: Boolean = false): World =
        started().apply {
            this.cache = cache
            acted()
            play("A")
            settle()
            assertEquals(1, calls["A"])
        }

    @Test
    fun `nothing new offers nothing, and the card stays, the cache missing it or not`() {
        for (cache in listOf(false, true)) {
            val world = written(cache)
            world.lookAgain()
            world.settle()

            assertEquals("cache $cache", listOf("A"), world.lookedAgain)
            assertEquals("cache $cache", 1, world.calls["A"])
            assertEquals("cache $cache", "A #1", world.cardText())
            assertTrue("cache $cache", world.coordinator.state.foundNothingNew)
            assertFalse("cache $cache", world.coordinator.state.lookingAgain)
            assertNull("cache $cache", world.coordinator.state.writeKey)
            assertEquals("cache $cache", world.key("A"), world.coordinator.state.lookAgainKey)
        }
    }

    @Test
    fun `a look again never calls the AI service, whatever it finds`() {
        for (found in listOf("Evidence 1.", "Evidence 2.", "  evidence\n 1. ")) {
            val world = written()
            world.evidenceText = found
            world.lookAgain()
            world.settle()
            world.settle()
            assertEquals(found, 1, world.calls["A"])
            assertEquals(found, "A #1", world.cardText())
        }
    }

    @Test
    fun `the same evidence spaced otherwise is nothing new`() {
        val world = written()
        world.evidenceText = "  Evidence\n  1.  "
        world.lookAgain()
        world.settle()

        assertEquals(1, world.calls["A"])
        assertTrue(world.coordinator.state.foundNothingNew)
    }

    @Test
    fun `with nothing new, a card kept under the current settings does not replace the one shown`() {
        val world = written(cache = true)
        world.cards[world.key("A") to world.service] = "A, kept under other settings"
        world.lookAgain()
        world.settle()

        assertEquals(1, world.calls["A"])
        assertEquals("A #1", world.cardText())
    }

    @Test
    fun `a run that asks its sources again online after a look again calls nothing`() {
        val world = written()
        world.evidenceText = "Evidence 2."
        world.send(CoordinatorEvent.NetworkChanged(online = false))
        world.offline = true
        world.offlineFinds = true
        world.lookAgain()
        world.settle()
        assertTrue(world.coordinator.state.foundSomethingNew)

        world.offline = false
        world.send(CoordinatorEvent.NetworkChanged(online = true))
        world.settle()

        assertEquals(listOf("A"), world.askedAgain)
        assertEquals(1, world.calls["A"])
        assertEquals("A #1", world.cardText())
    }

    @Test
    fun `something new offers Rewrite insight, and its tap alone writes, once`() {
        val world = written(cache = true)
        world.evidenceText = "Evidence 2."
        world.service++
        world.lookAgain()
        world.settle()

        assertEquals(1, world.calls["A"])
        assertTrue(world.coordinator.state.foundSomethingNew)
        assertTrue(world.coordinator.state.rewrites())
        assertEquals(world.key("A"), world.coordinator.state.writeKey)

        world.tap()
        world.settle()
        assertEquals(2, world.calls["A"])
        assertEquals("A #2", world.cardText())
        assertFalse(world.coordinator.state.foundSomethingNew)
        // Written, and not again unasked, however long it plays.
        world.settle()
        assertEquals(2, world.calls["A"])
    }

    @Test
    fun `its run looks up past the cache, with what the run before gathered, and is the user's action`() {
        val world = written(cache = true)
        val before =
            checkNotNull(
                world.coordinator.state.run
                    ?.gathered,
            )
        val actions = world.actions
        world.lookupMs = 2_000
        world.lookAgain()

        assertTrue(world.coordinator.state.lookingAgain)
        assertNull(world.coordinator.state.lookAgainKey)
        world.settle()
        assertEquals(listOf("Zero", "A", "A"), world.lookups)
        assertEquals(listOf(before), world.lookedAgainBefore)
        assertEquals(actions + 1, world.actions)
    }

    @Test
    fun `a look again on a card from the cache before the first action starts no automatic writing`() {
        val world = started()
        world.cache = true
        world.cards[world.key("A") to 0] = "A kept"
        world.play("A")
        world.settle()
        assertFalse(world.coordinator.state.acted)

        world.lookAgain()
        world.settle()
        world.play("B")
        world.settle()

        assertEquals(listOf("A"), world.lookedAgain)
        assertEquals(1, world.actions)
        assertFalse(world.coordinator.state.acted)
        assertEquals(null, world.calls["B"])
    }

    @Test
    fun `no look again while a call is under way, so it never doubles automatic writing`() {
        val world = written()
        world.streamMs = 10_000
        world.play("B")
        world.advance(DEBOUNCE_MS + 1_000)
        assertEquals(1, world.calls["B"])
        assertNull(world.coordinator.state.lookAgainKey)

        world.lookAgain(world.key("B"))
        world.settle()

        assertEquals(emptyList<String>(), world.lookedAgain)
        assertEquals(1, world.calls["B"])
    }

    @Test
    fun `a track change during a look again cuts it off, and the new track is written once`() {
        val world = written()
        world.evidenceText = "Evidence 2."
        world.lookupMs = 5_000
        world.lookAgain()
        world.advance(1_000)
        world.play("B", ChangeCause.SKIP_IN_PLAYER)
        world.settle()

        assertEquals(1, world.calls["A"])
        assertEquals(1, world.calls["B"])
        assertFalse(world.coordinator.state.lookingAgain)
    }

    @Test
    fun `Stop ends the Rewrite tapped after a look again as any Write's, and the card written before stays`() {
        val world = written()
        world.evidenceText = "Evidence 2."
        world.streamMs = 10_000
        world.lookAgain()
        world.settle()
        world.tap()
        world.advance(1_000)
        assertEquals(2, world.calls["A"])

        world.stopTap()
        world.settle()

        assertEquals(listOf("A"), world.stopped)
        assertEquals("A #1", world.cardText())
        assertTrue(world.coordinator.state.rewrites())
        assertEquals(2, world.calls["A"])
    }

    @Test
    fun `a tap for another run's key, or with no card shown, does nothing`() {
        val world = written()
        world.lookAgain(world.key("Elsewhere"))
        world.settle()
        assertEquals(emptyList<String>(), world.lookedAgain)

        val unwritten = started()
        unwritten.play("A")
        unwritten.settle()
        assertNull(unwritten.coordinator.state.lookAgainKey)
        unwritten.lookAgain(unwritten.key("A"))
        unwritten.settle()
        assertEquals(emptyList<String>(), unwritten.lookedAgain)
    }
}
