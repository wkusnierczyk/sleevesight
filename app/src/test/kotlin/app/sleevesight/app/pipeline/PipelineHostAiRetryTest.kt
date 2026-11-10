package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.pipeline.AiRetry
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The insight's call asked again when the AI service is busy (#1164, `AiRetry`), through the host and
 * a real pipeline: not once a rebuild is pending (a key saved, the service switched), whose call
 * would otherwise run on, on the replaced client, while the rebuild waits for it to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostAiRetryTest : PipelineHostFixture() {
    private val noJitter =
        object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }

    @Test
    fun `a busy call is asked again after its wait, and answered`() =
        runTest {
            aiThrough = { AiRetry(it, { 30.seconds }, testScheduler.timeSource, noJitter) }
            host = newHost()
            started()
            play("Sundown")
            settle()
            provider.error = AiErrorKind.OVERLOADED
            tap()
            runCurrent()
            assertEquals(1, provider.requests)
            provider.error = null
            advanceTimeBy(3.seconds)
            assertEquals(2, provider.requests)
            assertEquals(
                true,
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    @Test
    fun `a rebuild requested during the wait sends no second request`() =
        runTest {
            aiThrough = { AiRetry(it, { 30.seconds }, testScheduler.timeSource, noJitter) }
            host = newHost()
            started()
            play("Sundown")
            settle()
            provider.error = AiErrorKind.OVERLOADED
            tap()
            runCurrent()
            assertEquals(1, provider.requests)

            // A key saved during the 2 s wait: a rebuild, pending until the call ends.
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            provider.error = null
            advanceTimeBy(1.minutes)
            assertEquals(1, provider.requestsFor("Sundown"))
        }
}
