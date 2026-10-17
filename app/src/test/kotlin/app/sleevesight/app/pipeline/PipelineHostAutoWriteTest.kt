package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session layer's cause reaching the decision through the host (D5): under *When I skip*, a
 * skip writes and a natural end offers Write, and a tap on Write taken is passed on as an action.
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
            host.write()
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
}
