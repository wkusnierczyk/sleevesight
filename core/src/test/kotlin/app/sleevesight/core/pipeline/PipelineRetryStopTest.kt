package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A model call that failed, and Stop (#163), as the run makes them under its control: another
 * call only when the control says a tap asked for it; a call stopped keeps nothing, and a Stop
 * after the reply's end changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineRetryStopTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(
        adapters: List<FakeAdapter>,
        provider: AiProvider = FakeProvider(),
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = normalizer,
                adapters = adapters,
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    private fun mb() = FakeAdapter(SourceId.MUSICBRAINZ)

    @Test
    fun `a call that failed is made again only when a tap asks for it`() =
        runTest {
            var replies = 0
            val provider =
                FakeProvider {
                    if (++replies == 1) {
                        listOf(CompletionEvent.Error(AiErrorKind.OVERLOADED))
                    } else {
                        FakeProvider.done("It is a song [MB1].")
                    }
                }
            val pipeline = pipeline(listOf(mb()), provider = provider)
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            val run = backgroundScope.launch { pipeline.run(sundown, control) }
            runCurrent()
            assertEquals(InsightState.Failed("overloaded"), pipeline.state.value!!.insight)
            assertEquals(1, control.retriesAsked)
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
            // It waits for the tap; nothing is called again meanwhile.
            advanceTimeBy(10.minutes)
            assertEquals(1, provider.requests.size)
            assertTrue(run.isActive)

            control.retries.send(true)
            runCurrent()
            assertEquals(2, provider.requests.size)
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertEquals(listOf("It is a song [MB1]."), control.cardsWritten)
            assertEquals(2, control.ended.count { it == RunStep.MODEL_CALL })
            assertTrue(run.isCompleted)
        }

    @Test
    fun `a failed call is not made again when the control says no, and the run ends`() =
        runTest {
            val provider = FakeProvider { listOf(CompletionEvent.Error(AiErrorKind.SERVER)) }
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            control.retries.send(false)
            val state = pipeline(listOf(mb()), provider = provider).run(sundown, control)

            assertEquals(InsightState.Failed("server"), state.insight)
            assertEquals(1, control.retriesAsked)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `a call stopped shows as stopped, keeps nothing, and is made again only on a tap`() =
        runTest {
            val cache = MemoryCache()
            val provider = FakeProvider(delay = 10.seconds)
            val pipeline = pipeline(listOf(mb()), provider = provider, cache = cache)
            val events = recording(pipeline)
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            val run = backgroundScope.launch { pipeline.run(sundown, control) }
            advanceTimeBy(2.seconds)
            assertIs<InsightState.Writing>(pipeline.state.value!!.insight)

            control.stop.complete(Unit)
            runCurrent()
            assertEquals(InsightState.Stopped, pipeline.state.value!!.insight)
            assertTrue(events.any { it is PipelineEvent.ModelStopped })
            assertEquals(emptyMap(), cache.cards)
            assertEquals(emptyList(), control.cardsWritten)
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
            assertEquals(1, control.retriesAsked)
            assertTrue(run.isActive)

            control.stop = CompletableDeferred()
            control.retries.send(true)
            advanceTimeBy(11.seconds)
            assertEquals(2, provider.requests.size)
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertEquals(1, cache.cards.size)
            assertTrue(run.isCompleted)
        }

    @Test
    fun `a Stop that came before the call starts sends nothing`() =
        runTest {
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider)
            val events = recording(pipeline)
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            control.stop.complete(Unit)
            control.retries.send(false)
            val state = pipeline.run(sundown, control)
            runCurrent()

            assertEquals(InsightState.Stopped, state.insight)
            assertEquals(0, provider.requests.size)
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
            // No call went out, so the log shows none: only the Stop.
            assertEquals(0, events.count { it is PipelineEvent.ModelCall })
            assertEquals(1, events.count { it is PipelineEvent.ModelStopped })
        }

    @Test
    fun `a reply whose end has come is kept, however long the transport stays open, and a Stop then changes nothing`() =
        runTest {
            val cache = MemoryCache()
            var closed = false
            // The reply ends (`Done`, as at `message_stop`), and the body stays open.
            val provider =
                object : AiProvider {
                    override val id: ProviderId = ProviderId.ANTHROPIC

                    override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

                    override fun stream(
                        request: CompletionRequest,
                        key: Secret,
                    ): Flow<CompletionEvent> =
                        flow {
                            try {
                                FakeProvider.done("It is a song [MB1].").forEach { emit(it) }
                                awaitCancellation()
                            } finally {
                                closed = true
                            }
                        }
                }
            val pipeline = pipeline(listOf(mb()), provider = provider, cache = cache)
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            val run = backgroundScope.launch { pipeline.run(sundown, control) }
            runCurrent()
            // The Stop lands after the reply's end, while the transport would still be open.
            control.stop.complete(Unit)
            runCurrent()

            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertEquals(listOf("It is a song [MB1]."), control.cardsWritten)
            assertEquals(1, cache.cards.size)
            assertEquals(0, control.retriesAsked)
            // The stream was closed at the reply's end, not left waiting for the transport.
            assertTrue(closed)
            assertTrue(run.isCompleted)
        }
}
