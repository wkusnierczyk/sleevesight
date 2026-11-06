package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A later play of a track whose insight only another model wrote (#911): another track in between,
 * or a new process. While the run would not write by itself (*On demand*, or an automatic mode
 * waiting for the user), the insight kept is shown, with *Rewrite insight* offered to write it with
 * the model chosen now, a paid call only on a tap; under an automatic mode that writes by itself, the
 * chosen model writes it anew, a rewrite as the tap's is (#972). The model's own insight, when kept,
 * is always the one shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostLaterPlayTest : PipelineHostFixture() {
    // The process the host runs in, cancelled for a cold start.
    private var process: Job? = null

    @Test
    fun `under On demand, another track in between, the insight kept is shown with Rewrite offered`() =
        runTest {
            writtenWithFirstModel()
            play("Beta")
            settle()
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()

            play("Alpha")
            settle()
            assertKeptWithRewrite(FIRST_MODEL)

            // The tap writes it with the model chosen, as a rewrite, which its footer then says.
            tap()
            settle()
            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals(OTHER_MODEL, provider.models.last())
            val rewritten =
                host.state.value.track
                    ?.insight as InsightState.Written
            assertEquals(OTHER_MODEL, rewritten.writtenBy?.model)
            assertEquals(setOf(RewritePart.MODEL), rewritten.rewritten)
        }

    @Test
    fun `under On demand, after a new process, the insight kept is shown with Rewrite offered`() =
        runTest {
            writtenWithFirstModel()

            coldStart()
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            play("Alpha")
            settle()
            assertKeptWithRewrite(FIRST_MODEL)
        }

    @Test
    fun `under an automatic mode, waiting for the first action, the insight kept is shown with Rewrite offered`() =
        runTest {
            writtenWithFirstModel()

            // A new process waits for the user's first action: nothing is written by itself.
            coldStart()
            settings.value =
                settings.value
                    .copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
                    .withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            play("Alpha")
            settle()
            assertKeptWithRewrite(FIRST_MODEL)
        }

    @Test
    fun `with automatic writing paused by Saver, the insight kept is shown with Rewrite offered`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            writtenWithFirstModel(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            // The first change away is written as usual; at the next the first action expires.
            play("Beta")
            settle()
            assertEquals(1, provider.requestsFor("Beta"))

            play("Alpha")
            settle()
            assertTrue("offered because automatic writing paused", host.state.value.lapsed)
            assertKeptWithRewrite(FIRST_MODEL)
        }

    @Test
    fun `under On track skip, a track that followed by itself shows the insight kept with Rewrite offered`() =
        runTest {
            writtenWithFirstModel(WriteAutomatically.WHEN_I_SKIP)
            play("Beta")
            settle()
            assertEquals("followed by itself: not written", 0, provider.requestsFor("Beta"))
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()

            play("Alpha")
            settle()
            assertTrue("offered because it followed by itself", host.state.value.followed)
            assertKeptWithRewrite(FIRST_MODEL)
        }

    @Test
    fun `under an automatic mode that writes by itself, the chosen model rewrites it, saying so`() =
        runTest {
            writtenWithFirstModel(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            play("Beta")
            settle()
            assertEquals("written by itself, the first action made", 1, provider.requestsFor("Beta"))
            // A first write, with nothing kept: not a rewrite.
            assertEquals(
                emptySet<RewritePart>(),
                (
                    host.state.value.track
                        ?.insight as InsightState.Written
                ).rewritten,
            )
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()

            play("Alpha")
            settle()
            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals(OTHER_MODEL, provider.models.last())
            val written =
                host.state.value.track
                    ?.insight as InsightState.Written
            assertEquals(OTHER_MODEL, written.writtenBy?.model)
            // It replaced the insight kept from the first model, as a tap on Rewrite does (#972):
            // "Rewritten with" the model, and nothing more offered.
            assertEquals(setOf(RewritePart.MODEL), written.rewritten)
            assertFalse(host.state.value.rewrite)
        }

    @Test
    fun `an automatic write that failed, retried on the same play, is a rewrite, saying so`() =
        runTest {
            anotherModelWrites()
            provider.error = AiErrorKind.SERVER
            play("Alpha")
            settle()
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Failed,
            )

            provider.error = null
            tap()
            settle()
            assertEquals(3, provider.requestsFor("Alpha"))
            val written =
                host.state.value.track
                    ?.insight as InsightState.Written
            assertEquals(OTHER_MODEL, written.writtenBy?.model)
            assertEquals(setOf(RewritePart.MODEL), written.rewritten)
        }

    @Test
    fun `after an automatic write fails, another model chosen shows the insight kept with Rewrite`() =
        runTest {
            anotherModelWrites()
            provider.error = AiErrorKind.SERVER
            play("Alpha")
            settle()
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Failed,
            )

            // The call granted left the ledger written, with no card: the one kept takes its place.
            provider.error = null
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, THIRD_MODEL)
            settle()
            assertKeptWithRewrite(FIRST_MODEL, requests = 2)
            tap()
            settle()
            assertEquals(THIRD_MODEL, provider.models.last())
        }

    @Test
    fun `after an automatic write is stopped, another model chosen shows the insight kept with Rewrite`() =
        runTest {
            anotherModelWrites()
            provider.streaming = 10.seconds
            play("Alpha")
            settle()
            host.stop(checkNotNull(host.state.value.stopCall))
            settle()
            assertEquals(
                InsightState.Stopped,
                host.state.value.track
                    ?.insight,
            )

            provider.streaming = Duration.ZERO
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, THIRD_MODEL)
            settle()
            assertKeptWithRewrite(FIRST_MODEL, requests = 2)
        }

    // Alpha written with the first model, then another track written by itself, and another model
    // chosen: the next play of Alpha is written by itself with it.
    private fun TestScope.anotherModelWrites() {
        writtenWithFirstModel(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
        play("Beta")
        settle()
        settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
        settle()
    }

    @Test
    fun `of several kept, the one written last is shown`() =
        runTest {
            writtenWithFirstModel()
            clock.at += 1.minutes
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            tap()
            settle()
            play("Beta")
            settle()
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, THIRD_MODEL)
            settle()

            play("Alpha")
            settle()
            assertKeptWithRewrite(OTHER_MODEL, requests = 2)
        }

    @Test
    fun `the chosen model's own insight is shown, never another's in its place`() =
        runTest {
            writtenWithFirstModel()
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            tap()
            settle()
            // Another's is written later, so it is the newest kept.
            clock.at += 1.minutes
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, THIRD_MODEL)
            settle()
            tap()
            settle()
            play("Beta")
            settle()
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()

            play("Alpha")
            settle()
            val shown =
                host.state.value.track
                    ?.insight as InsightState.Written
            assertEquals(OTHER_MODEL, shown.writtenBy?.model)
            assertTrue(shown.cached)
            assertFalse(host.state.value.rewrite)
            assertEquals(3, provider.requestsFor("Alpha"))
        }

    // Alpha written with the first model on a tap, the first action, under [mode].
    private fun TestScope.writtenWithFirstModel(mode: WriteAutomatically = WriteAutomatically.NEVER) {
        settings.value = settings.value.copy(writeAutomatically = mode).withModel(ProviderId.ANTHROPIC, FIRST_MODEL)
        startProcess()
        play("Alpha")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requestsFor("Alpha"))
        assertEquals(FIRST_MODEL, provider.models.last())
    }

    // The host started in a process of its own, with the activity on screen.
    private fun TestScope.startProcess() {
        val job = Job(backgroundScope.coroutineContext[Job])
        process = job
        host.activityStarted()
        host.start(CoroutineScope(backgroundScope.coroutineContext + job))
        runCurrent()
    }

    // A cold start: the process ends, and a new one starts a new host on the same cache.
    private fun TestScope.coldStart() {
        process?.cancel()
        runCurrent()
        host = newHost()
        startProcess()
    }

    // The insight [model] wrote, kept, is shown with *Rewrite insight* offered to write it with the model
    // chosen; no call made for it beyond [requests].
    private fun assertKeptWithRewrite(
        model: String,
        requests: Int = 1,
    ) {
        val view = host.state.value
        val shown = view.track?.insight as InsightState.Written
        assertEquals(model, shown.writtenBy?.model)
        assertTrue(view.cardShown)
        assertTrue(view.rewrite)
        assertEquals(setOf(RewritePart.MODEL), view.rewriteWith?.parts)
        assertEquals(settings.value.model, view.rewriteWith?.with?.model)
        assertEquals(requests, provider.requestsFor("Alpha"))
    }

    private companion object {
        const val FIRST_MODEL = "claude-first"
        const val OTHER_MODEL = "claude-other"
        const val THIRD_MODEL = "claude-third"
    }
}
