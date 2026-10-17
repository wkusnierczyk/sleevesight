package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A model call that failed, and Stop (#163), through the host and a real pipeline: Write offered
 * again after a call that failed or was stopped, a tap on it a new paid call, never one unasked;
 * Stop offered while a call is under way, ending it with nothing kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostRetryStopTest : PipelineHostFixture() {
    private fun insight(): InsightState? =
        host.state.value.track
            ?.insight

    /** A tap on Stop as the screen sends it: for the call Stop is offered for. */
    private fun stop() {
        host.stop(checkNotNull(host.state.value.stopCall))
    }

    @Test
    fun `a call that fails offers Write again, and a tap on it is a new call`() =
        runTest {
            started()
            play("Sundown")
            settle()
            provider.error = AiErrorKind.OVERLOADED
            tap()
            settle()
            assertEquals(InsightState.Failed("overloaded"), insight())
            assertTrue(host.state.value.retry)
            assertEquals(1, provider.requests)

            // Nothing calls again on its own, the first action made and every track change written on.
            settle()
            assertEquals(1, provider.requests)

            provider.error = null
            tap()
            settle()
            assertEquals(2, provider.requests)
            assertTrue(insight() is InsightState.Written)
            assertEquals(false, host.state.value.retry)
            assertEquals(null, host.state.value.writeKey)
        }

    @Test
    fun `an automatic call that fails waits for a tap, and a rebuild does not call again`() =
        runTest {
            started()
            play("Zero")
            settle()
            tap()
            settle()
            provider.error = AiErrorKind.SERVER
            play("Sundown")
            settle()
            assertEquals(1, provider.requestsFor("Sundown"))
            assertTrue(host.state.value.retry)

            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            assertEquals(1, provider.requestsFor("Sundown"))
            // The rebuild's run of the written track, with no card paid for: Write offered alone.
            assertEquals(InsightState.Idle(canWrite = true), insight())
        }

    @Test
    fun `Stop ends the call under way, keeps nothing, and offers Write again`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            provider.streaming = 30.seconds
            started()
            play("Sundown")
            settle()
            assertEquals(null, host.state.value.stopCall)
            tap()
            advanceTimeBy(2.seconds)
            assertTrue(insight() is InsightState.Writing)
            assertEquals(null, host.state.value.writeKey)
            assertTrue(host.state.value.stopCall != null)

            stop()
            runCurrent()
            assertEquals(InsightState.Stopped, insight())
            assertEquals(null, host.state.value.stopCall)
            assertTrue(host.state.value.retry)
            assertEquals(0, provider.completed)
            settle()
            assertEquals(1, provider.requests)

            // Write again: a new call, which this time ends.
            tap()
            advanceTimeBy(40.seconds)
            assertEquals(2, provider.requests)
            assertEquals(1, provider.completed)
            assertTrue(insight() is InsightState.Written)
        }

    @Test
    fun `a Stop shown for a call that has ended stops nothing, the next call included`() =
        runTest {
            started()
            play("Sundown")
            settle()
            provider.error = AiErrorKind.OFFLINE
            provider.streaming = 10.seconds
            tap()
            runCurrent()
            settle()
            assertTrue(host.state.value.retry)

            // Write again: the second call streams; the Stop shown for the first lands now.
            provider.error = null
            tap()
            advanceTimeBy(2.seconds)
            val second = checkNotNull(host.state.value.stopCall)
            assertEquals(2, second.number)
            host.stop(second.copy(number = 1))
            advanceTimeBy(20.seconds)
            assertEquals(2, provider.requests)
            assertEquals(1, provider.completed)
            assertTrue(insight() is InsightState.Written)
        }

    @Test
    fun `an automatic call that fails, then a tap's call, and a late Stop for the first stops nothing`() =
        runTest {
            started()
            play("Zero")
            settle()
            tap()
            settle()
            provider.error = AiErrorKind.SERVER
            play("Sundown")
            settle()
            assertTrue(host.state.value.retry)

            provider.error = null
            provider.streaming = 10.seconds
            tap()
            advanceTimeBy(2.seconds)
            val second = checkNotNull(host.state.value.stopCall)
            assertEquals(2, second.number)
            host.stop(second.copy(number = 1))
            advanceTimeBy(20.seconds)
            assertEquals(2, provider.requestsFor("Sundown"))
            assertTrue(insight() is InsightState.Written)
        }

    @Test
    fun `a rewrite stopped shows the card paid for again, says so, and offers Rewrite again`() =
        runTest {
            started()
            play("Sundown")
            settle()
            tap()
            settle()
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            settle()
            assertTrue(host.state.value.rewrite)

            provider.streaming = 30.seconds
            tap()
            advanceTimeBy(2.seconds)
            stop()
            runCurrent()
            val view = host.state.value
            assertTrue(view.track?.insight is InsightState.Written)
            assertEquals(PipelineHost.STOPPED, view.rewriteFailed)
            assertTrue(view.rewrite)
            assertEquals(false, view.retry)
            assertEquals(2, provider.requests)
            assertEquals(1, provider.completed)
        }
}
