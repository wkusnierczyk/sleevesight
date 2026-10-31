package app.sleevesight.app.pipeline

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * *Retry* beside a source that could not be reached (#724), as `PipelineCoordinatorTest` runs its
 * sequences: offered for the playing track's run once its sources have landed with some
 * unreachable; a tap asks that source alone again, as back online asks every one (#192), keeping
 * the others as they landed, and calls no AI service of its own.
 */
class PipelineCoordinatorRetryTest {
    // The first action made, and A played offline: the web search and Wikipedia unreachable.
    private fun unreachable(): World =
        started().apply {
            acted()
            wikipediaDown = false
            network(online = false)
            play("A")
            settle()
        }

    private fun World.retry(
        source: SourceId,
        key: RunKey = coordinator.state.askAgainKey ?: key("No run offers Retry"),
    ) {
        send(CoordinatorEvent.RetryTapped(key, source))
        advance(0)
    }

    @Test
    fun `Retry is offered for the run whose sources landed unreachable, naming them`() {
        val world = unreachable()
        assertEquals(world.key("A"), world.coordinator.state.askAgainKey)
        assertEquals(setOf(SourceId.WEB, SourceId.WIKIPEDIA), world.coordinator.state.askable)
    }

    @Test
    fun `a tap asks that source alone again, keeping the others, and calls nothing`() {
        val world = unreachable()
        val calls = world.calls.toMap()
        world.retry(SourceId.WIKIPEDIA)
        world.settle()

        assertEquals(listOf("A"), world.askedAgain)
        val carried = world.carried.single()
        assertEquals(setOf(SourceId.WIKIPEDIA), carried.asked)
        assertEquals(setOf(SourceId.WEB, SourceId.WIKIPEDIA), carried.unreachable)
        assertEquals(listOf("Zero", "A", "A"), world.lookups)
        assertEquals(calls, world.calls)
    }

    @Test
    fun `a tap that brings lines writes nothing unasked, unlike back online, Write waiting for a tap`() {
        val world = unreachable()
        val calls = world.calls.toMap()
        // Back online, unheard: only the tap asks again, and the web search now finds its line.
        world.offline = false
        world.retry(SourceId.WEB)
        world.settle()

        assertEquals(listOf("A"), world.askedAgain)
        assertEquals(calls, world.calls)
    }

    @Test
    fun `a tap for a source that was reached, or for another run, does nothing`() {
        val world = unreachable()
        world.retry(SourceId.MUSICBRAINZ)
        world.retry(SourceId.WEB, world.key("B"))
        world.settle()
        assertEquals(emptyList<String>(), world.askedAgain)
    }

    @Test
    fun `with every source reached, no Retry is offered`() {
        val world =
            started().apply {
                acted()
                play("A")
                settle()
            }
        assertNull(world.coordinator.state.askAgainKey)
        assertEquals(emptySet<SourceId>(), world.coordinator.state.askable)
    }
}
