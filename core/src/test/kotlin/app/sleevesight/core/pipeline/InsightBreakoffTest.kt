package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * An insight whose reply broke off says how, on the card and in the diagnostics, with the tokens
 * counted, how much of it came and when, never its text; one that ended with no text fails rather
 * than show and keep an empty card (#665).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightBreakoffTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(
        provider: FakeProvider,
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ)),
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

    private fun brokeOff(
        cause: BreakoffCause,
        detail: String? = null,
    ): List<CompletionEvent> =
        listOf(
            CompletionEvent.Delta("It is a "),
            CompletionEvent.Delta("song"),
            CompletionEvent.Error(AiErrorKind.INCOMPLETE, breakoff = Breakoff(cause, Usage(900, 1), detail)),
        )

    @Test
    fun `a connection lost and a timeout are told apart on the card, and a reply that only ended is incomplete`() =
        runTest {
            val cases =
                listOf(
                    brokeOff(BreakoffCause.CONNECTION_LOST, "SocketException") to ReplyFailures.CONNECTION_LOST,
                    brokeOff(BreakoffCause.TIMED_OUT, "SocketTimeoutException") to ReplyFailures.TIMED_OUT,
                    brokeOff(BreakoffCause.NO_END) to ReplyFailures.INCOMPLETE,
                    brokeOff(BreakoffCause.TOO_LARGE) to ReplyFailures.INCOMPLETE,
                    listOf(CompletionEvent.Error(AiErrorKind.INCOMPLETE)) to ReplyFailures.INCOMPLETE,
                )
            for ((reply, label) in cases) {
                val state = pipeline(FakeProvider { reply }).run(sundown, write = true)
                assertEquals(InsightState.Failed(label), state.insight, label)
            }
        }

    @Test
    fun `the diagnostics name how the reply broke off, the tokens so far, its length and when, not its text`() =
        runTest {
            val provider =
                FakeProvider(delay = 3.seconds) { brokeOff(BreakoffCause.TIMED_OUT, "SocketTimeoutException") }
            val pipeline = pipeline(provider)
            val events = recording(pipeline)

            pipeline.run(sundown, write = true)
            runCurrent()

            val failed = events.filterIsInstance<PipelineEvent.ModelFailed>().single()
            val breakoff = Breakoff(BreakoffCause.TIMED_OUT, Usage(900, 1), "SocketTimeoutException")
            assertEquals(
                PipelineEvent.ModelFailed(
                    failed.key,
                    AiErrorKind.INCOMPLETE,
                    breakoff,
                    "It is a song".length,
                    3.seconds,
                ),
                failed,
            )
            val line = EventLine.of(failed)
            assertEquals(false, "song" in line, line)
        }

    @Test
    fun `a reply with no text fails, by the token limit or as empty, and no empty card is kept`() =
        runTest {
            val cases =
                listOf(
                    FakeProvider.done("", StopReason.MAX_TOKENS) to ReplyFailures.LENGTH_LIMIT,
                    FakeProvider.done("  \n ", StopReason.END) to ReplyFailures.EMPTY,
                    FakeProvider.done("", StopReason.OTHER) to ReplyFailures.EMPTY,
                )
            for ((reply, label) in cases) {
                val cache = MemoryCache()
                val events = mutableListOf<PipelineEvent>()
                val pipeline = pipeline(FakeProvider { reply }, cache)
                backgroundScope.launch { pipeline.events.toList(events) }
                runCurrent()

                val state = pipeline.run(sundown, write = true)
                runCurrent()

                assertEquals(InsightState.Failed(label), state.insight, label)
                assertEquals(emptyMap(), cache.cards, label)
                // The stop reason and the tokens are in the diagnostics, as for any reply that ended.
                assertEquals(1, events.count { it is PipelineEvent.ModelDone }, label)
            }
        }

    @Test
    fun `the call's token limit follows the effort in the settings`() =
        runTest {
            val provider = FakeProvider()
            for ((effort, limit) in listOf(ReasoningEffort.LOW to 4096, ReasoningEffort.HIGH to 16384)) {
                settings = Settings(aiProvider = ProviderId.ANTHROPIC).withEffort(ProviderId.ANTHROPIC, effort)
                pipeline(provider).run(sundown, write = true)
                assertEquals(limit, provider.requests.last().maxTokens, "$effort")
            }
        }
}
