package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session layer's cause reaching the decision through the host (D5): under *When I skip*, a
 * skip writes and a natural end offers Write, and a tap on Write taken is passed on as an action.
 * The first action lasts as long as the host, one per process: a return to the app keeps it, and a
 * new host (a cold start) starts without it (#190). *Pause automatic writing after* reaches the
 * coordinator from the settings, and the view says when Write is offered because of it (#203).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostAutoWriteTest : PipelineHostFixture() {
    @Test
    fun `when I skip, a skip in the player writes and a natural end offers Write`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
            started()
            play("One")
            settle()
            tap()
            settle()
            assertEquals(1, provider.requests)
            assertEquals("a tap taken is an action", 1, actions)

            play("Two", cause = ChangeCause.SKIP_IN_PLAYER)
            settle()
            assertEquals(1, provider.requestsFor("Two"))

            play("Three")
            settle()
            assertEquals(0, provider.requestsFor("Three"))
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertEquals("no tap, no action", 1, actions)
        }

    @Test
    fun `a cold start waits for the first action`() =
        runTest {
            // A new host, as a new process makes: a skip is not written until a tap.
            started()
            play("One", cause = ChangeCause.SKIP_IN_PLAYER)
            settle()
            assertEquals(0, provider.requests)
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `automatic writing pauses after the track changes away the setting names, and the view says why`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = 2, lookUpInBackground = true)
            actedThenAway()
            play("Two")
            settle()
            assertEquals(1, provider.requestsFor("Two"))
            assertFalse("written, not offered", host.state.value.lapsed)

            play("Three")
            settle()
            assertEquals(0, provider.requestsFor("Three"))
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
            assertTrue("offered because automatic writing paused", host.state.value.lapsed)
        }

    @Test
    fun `with the pause off, automatic writing goes on however long the app is away`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = null, lookUpInBackground = true)
            actedThenAway()
            for (title in listOf("Two", "Three", "Four", "Five", "Six", "Seven")) {
                play(title)
                settle()
                assertEquals(title, 1, provider.requestsFor(title))
            }
            assertFalse(host.state.value.lapsed)
        }

    // A first action (One written on a tap), then the app left.
    private fun TestScope.actedThenAway() {
        started()
        play("One")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requestsFor("One"))
        host.activityStopped(changingConfigurations = false)
        runCurrent()
    }
}
