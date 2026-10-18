package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preparing the next track (D13, *Evidence*; #20) in the coordinator's rules, pure: a preparation
 * starts and is granted only once the current run's requests are over and requests are allowed,
 * is never granted a model call, and is cancelled when the next item or the playing track changes.
 */
class PipelineCoordinatorPrepareTest {
    @Test
    fun `the next track is prepared once the playing track's requests are over`() {
        val world = started()
        with(world) {
            labelsMs = 1_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 500)
            assertEquals("not before the run's labels have ended", emptyList<String>(), preparations)
            settle()
            assertEquals(listOf("run A", "labels A", "prepare B", "prepare labels B"), grants)
            assertEquals(listOf("B"), prepared)
            assertEquals(listOf("A"), lookups)
        }
    }

    @Test
    fun `a preparation is never granted a model call, and pays nothing`() {
        val world = started()
        with(world) {
            acted()
            play("A")
            next("B")
            settle()
            assertEquals(listOf("B"), prepared)
            assertEquals(mapOf("Zero" to 1, "A" to 1), calls)
        }
    }

    @Test
    fun `nothing is prepared while the app is hidden, but on return or with background lookups it is`() {
        val world = started()
        with(world) {
            play("A")
            settle()
            hide()
            next("B")
            settle()
            assertEquals(emptyList<String>(), preparations)
            show()
            settle()
            assertEquals(listOf("B"), prepared)
            hide()
            background(true)
            next("C")
            settle()
            assertEquals(listOf("B", "C"), prepared)
        }
    }

    @Test
    fun `a granted preparation finishes its step when hidden, and its next step waits`() {
        val world = started()
        with(world) {
            prepareMs = 2_000
            play("A")
            next("B")
            settle()
            next("C")
            advance(100)
            hide()
            settle()
            assertEquals(
                listOf("prepare B", "prepare labels B", "prepare C"),
                grants.filter { it.startsWith("prepare") },
            )
            show()
            settle()
            assertEquals("prepare labels C", grants.last())
        }
    }

    @Test
    fun `a preparation's second try is granted only while requests are allowed`() {
        val world = started()
        with(world) {
            retrying += "B"
            play("A")
            next("B")
            settle()
            assertEquals(listOf("prepare B"), grants.filter { it.startsWith("prepare") })
            hide()
            advance(RETRY_MS * 2)
            assertEquals("hidden, the second try waits", listOf("prepare B"), preparationGrants())
            show()
            settle()
            assertEquals(
                listOf("prepare B", "prepare B", "prepare labels B"),
                grants.filter { it.startsWith("prepare") },
            )
        }
    }

    @Test
    fun `a preparation waiting for its second grant is cancelled as any other`() {
        val world = started()
        with(world) {
            retrying += "B"
            play("A")
            next("B")
            settle()
            hide()
            advance(RETRY_MS * 2)
            next("C")
            assertEquals(listOf("B"), preparationsCancelled)
            show()
            settle()
            assertEquals(listOf("prepare B", "prepare C", "prepare labels C"), preparationGrants())
        }
    }

    @Test
    fun `a preparation for a track no longer next is cancelled, and the new next one prepared`() {
        val world = started()
        with(world) {
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 100)
            assertEquals(listOf("B"), prepared)
            next("C")
            settle()
            assertEquals(listOf("B"), preparationsCancelled)
            assertEquals(listOf("B", "C"), prepared)
            next(null)
            settle()
            assertEquals(listOf("B", "C"), preparations)
        }
    }

    @Test
    fun `the queue emptied, or the setting off, cancels the preparation in progress`() {
        val world = started()
        with(world) {
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 100)
            next(null)
            settle()
            assertEquals(listOf("B"), preparationsCancelled)
            assertEquals(listOf("B"), preparations)
        }
    }

    @Test
    fun `a track change cancels the preparation, and the new track's run comes first`() {
        val world = started()
        with(world) {
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 100)
            play("B", ChangeCause.SKIP_IN_PLAYER)
            assertEquals(listOf("B"), preparationsCancelled)
            next("C")
            settle()
            assertEquals(listOf("run A", "labels A", "prepare B", "run B", "labels B", "prepare C"), grants.take(6))
            assertEquals(listOf("A", "B"), lookups)
        }
    }

    @Test
    fun `a new length of the playing track cancels the preparation when its run starts, then it starts again`() {
        val world = started()
        with(world) {
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 100)
            play("A", seconds = 200)
            assertEquals(
                "the report alone, of the same track, cancels nothing",
                emptyList<String>(),
                preparationsCancelled,
            )
            settle()
            assertEquals(listOf("B"), preparationsCancelled)
            assertEquals(listOf("B", "B"), preparations)
            assertEquals(listOf("run A", "labels A", "prepare B", "run A", "labels A", "prepare B"), grants.take(6))
        }
    }

    @Test
    fun `a replacement run's lookups never run beside a preparation's`() {
        val world = started()
        with(world) {
            lookupMs = 3_000
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 3_100)
            assertTrue("B's lookups under way", coordinator.state.preparation != null)
            play("A", seconds = 200)
            advance(DEBOUNCE_MS)
            assertEquals("run A", grants.last())
            assertEquals("cancelled when the run started", null, coordinator.state.preparation)
            advance(2_000)
            assertEquals("no preparation while the run looks up", "run A", grants.last())
            settle()
            assertEquals(listOf("run A", "labels A", "prepare B", "run A", "labels A", "prepare B"), grants.take(6))
        }
    }

    @Test
    fun `a track prepared is not prepared again, nor is the track playing`() {
        val world = started()
        with(world) {
            play("A")
            next("A")
            settle()
            assertEquals(emptyList<String>(), preparations)
            next("B")
            settle()
            next(null)
            next("B")
            settle()
            assertEquals(listOf("B"), preparations)
        }
    }

    @Test
    fun `a rebuild cancels the preparation, and the new pipeline prepares once its run's requests are over`() {
        val world = started()
        with(world) {
            prepareMs = 5_000
            play("A")
            next("B")
            advance(DEBOUNCE_MS + 100)
            keyChange()
            settle()
            assertEquals(listOf("B"), preparationsCancelled)
            assertEquals(listOf("run A", "labels A", "prepare B", "run A", "labels A", "prepare B"), grants.take(6))
        }
    }

    @Test
    fun `while Write waits for a tap, the next track is prepared`() {
        val world = started()
        with(world) {
            writesOn = mode(WriteAutomatically.NEVER)
            play("A")
            next("B")
            settle()
            assertTrue(offersWriteFor("A"))
            assertEquals(listOf("B"), prepared)
            tap()
            settle()
            assertEquals(mapOf("A" to 1), calls)
        }
    }

    @Test
    fun `nothing is prepared before the first run is acknowledged`() {
        val coordinator = PipelineCoordinator()
        val effects = coordinator.on(CoordinatorEvent.NextChanged(World().nowPlaying("B")))
        assertEquals(emptyList<CoordinatorEffect>(), effects)
        assertEquals(null, coordinator.state.next)
    }

    private fun World.preparationGrants() = grants.filter { it.startsWith("prepare") }
}
