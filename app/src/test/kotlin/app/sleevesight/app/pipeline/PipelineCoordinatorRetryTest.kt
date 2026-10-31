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
    fun `the network returning while Retry asks keeps its run from writing unasked`() {
        val world = unreachable()
        val calls = world.calls.toMap()
        world.offline = false
        world.retry(SourceId.WEB)
        // Back online before the retried run has landed: back online replaces it, still retried.
        world.network(online = true)
        world.settle()

        assertEquals(calls, world.calls)
    }

    @Test
    fun `after Retry, Write is offered and a tap on it writes`() {
        val world = unreachable()
        world.offline = false
        world.retry(SourceId.WEB)
        world.settle()
        world.tap()
        world.settle()

        assertEquals(1, world.calls["A"])
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
    fun `a source whose label requests failed is offered Retry, which asks those requests alone again`() {
        // Online, all reached; MusicBrainz's artist tags then fail (#772).
        val world =
            started().apply {
                acted()
                play("A")
                settle()
            }
        assertNull(world.coordinator.state.askAgainKey)
        val run = checkNotNull(world.coordinator.state.current).id
        world.send(CoordinatorEvent.LabelsFailed(run, setOf(SourceId.MUSICBRAINZ)))
        world.advance(0)
        assertEquals(world.key("A"), world.coordinator.state.askAgainKey)
        assertEquals(setOf(SourceId.MUSICBRAINZ), world.coordinator.state.askable)

        val calls = world.calls.toMap()
        world.retry(SourceId.MUSICBRAINZ)
        world.settle()
        assertEquals(listOf("A"), world.askedAgain)
        val carried = world.carried.single()
        // Its lookup kept, its label requests asked again; no AI call.
        assertEquals(emptySet<SourceId>(), carried.asked)
        assertEquals(setOf(SourceId.MUSICBRAINZ), carried.labelsAsked)
        assertEquals(calls, world.calls)
    }

    @Test
    fun `a source shown but not kept is offered Retry, and only a tap asks it again`() {
        // Online, MusicBrainz's lyrics links not known (#728): nothing unreachable, so back online
        // asks nothing, but *Retry* is offered for MusicBrainz.
        val world =
            started().apply {
                acted()
                musicBrainzProvisional = true
                play("A")
                settle()
            }
        assertEquals(world.key("A"), world.coordinator.state.askAgainKey)
        assertEquals(setOf(SourceId.MUSICBRAINZ), world.coordinator.state.askable)
        val calls = world.calls.toMap()
        world.retry(SourceId.MUSICBRAINZ)
        world.settle()

        assertEquals(listOf("A"), world.askedAgain)
        assertEquals(setOf(SourceId.MUSICBRAINZ), world.carried.single().asked)
        assertEquals(calls, world.calls)
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
