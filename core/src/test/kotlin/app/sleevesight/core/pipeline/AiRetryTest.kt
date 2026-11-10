package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * AI calls are asked again when they fail for a while only (#1164, *Retry when the AI fails*), as a
 * busy MusicBrainz is (#1120): a busy service, a failed connection or a reply cut short is asked
 * again, the waits doubling from 2 s, for up to the total set, after which the error is shown; no
 * network, or a failure no retry mends, shows at once; a Stop ends the tries.
 */
class AiRetryTest {
    private val request = CompletionRequest("model", "prompt")
    private val busy = CompletionEvent.Error(AiErrorKind.OVERLOADED, detail = "HTTP 503")
    private val answer = listOf(CompletionEvent.Delta("An answer."), CompletionEvent.Done(Usage(9, 3), StopReason.END))

    @Test
    fun `a busy service is asked again after 2 s and then 4 s, and its answer is all the caller sees`() =
        runTest {
            val provider = Scripted(this, listOf(busy), listOf(busy), answer)
            val events = retrying(provider, 30.seconds).stream(request, KEY).toList()
            assertEquals(answer, events)
            assertEquals(listOf(0L, 2_000L, 6_000L), provider.starts)
        }

    @Test
    fun `the tries stop at the total set, the last wait shortened to what is left, and the error is shown`() =
        runTest {
            val provider = Scripted(this, *Array(10) { listOf(busy) })
            val events = retrying(provider, 30.seconds).stream(request, KEY).toList()
            assertEquals(listOf<CompletionEvent>(busy), events)
            // 2 + 4 + 8 + 16 s add up to 30 s: four more tries, the last at the total.
            assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L), provider.starts)
            assertEquals(30_000L, currentTime)

            val shorter = Scripted(this, *Array(10) { listOf(busy) })
            retrying(shorter, 20.seconds).stream(request, KEY).toList()
            // After 14 s the 16 s wait does not fit: the 6 s left are waited for one last try.
            assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 20_000L), shorter.starts.map { it - 30_000L })
        }

    @Test
    fun `a try that takes long counts against the total, so fewer are made`() =
        runTest {
            val slow = Scripted(this, *Array(10) { listOf(busy) }, takes = 10.seconds)
            retrying(slow, 30.seconds).stream(request, KEY).toList()
            // From the first failure at 10 s: tries at 12, 26 and, the last wait shortened, 40 s.
            assertEquals(listOf(0L, 12_000L, 26_000L, 40_000L), slow.starts)
        }

    @Test
    fun `don't retry makes one try and shows the error at once`() =
        runTest {
            val provider = Scripted(this, listOf(busy), answer)
            assertEquals(listOf<CompletionEvent>(busy), retrying(provider, Duration.ZERO).stream(request, KEY).toList())
            assertEquals(listOf(0L), provider.starts)
        }

    @Test
    fun `no network, or a failure no retry mends, is shown at once`() =
        runTest {
            for (kind in listOf(
                AiErrorKind.OFFLINE,
                AiErrorKind.UNAUTHORIZED,
                AiErrorKind.SERVER,
                AiErrorKind.BILLING,
            )) {
                val failed = CompletionEvent.Error(kind)
                val provider = Scripted(this, listOf(failed), answer)
                assertEquals(
                    listOf<CompletionEvent>(failed),
                    retrying(provider, 1.minutes).stream(request, KEY).toList(),
                )
                assertEquals(1, provider.starts.size, kind.name)
            }
        }

    @Test
    fun `a failed connection, a timeout and a reply cut short before its text are asked again`() =
        runTest {
            val cut =
                CompletionEvent.Error(
                    AiErrorKind.INCOMPLETE,
                    breakoff = Breakoff(BreakoffCause.NO_END, Usage(9, 0)),
                )
            val failures =
                listOf(
                    CompletionEvent.Error(AiErrorKind.CONNECTION_FAILED),
                    CompletionEvent.Error(AiErrorKind.TIMED_OUT),
                    cut,
                )
            for (failed in failures) {
                val provider = Scripted(this, listOf(failed), answer)
                assertEquals(answer, retrying(provider, 30.seconds).stream(request, KEY).toList(), failed.toString())
            }
        }

    @Test
    fun `a failed connection asked again with the network gone ends as no connection`() =
        runTest {
            val offline = CompletionEvent.Error(AiErrorKind.OFFLINE, detail = "UnknownHostException")
            val provider =
                Scripted(this, listOf(CompletionEvent.Error(AiErrorKind.CONNECTION_FAILED)), listOf(offline), answer)
            assertEquals(listOf<CompletionEvent>(offline), retrying(provider, 30.seconds).stream(request, KEY).toList())
            assertEquals(listOf(0L, 2_000L), provider.starts)
        }

    @Test
    fun `a reply that broke off after some of its text reached the caller is not asked again`() =
        runTest {
            val cut =
                CompletionEvent.Error(
                    AiErrorKind.INCOMPLETE,
                    breakoff = Breakoff(BreakoffCause.CONNECTION_LOST, Usage(9, 2)),
                )
            val provider = Scripted(this, listOf(CompletionEvent.Delta("Half"), cut), answer)
            val events = retrying(provider, 30.seconds).stream(request, KEY).toList()
            assertEquals(listOf(CompletionEvent.Delta("Half"), cut), events)
            assertEquals(1, provider.starts.size)
        }

    @Test
    fun `the wait the service asks for is kept, and one longer than what is left ends the tries`() =
        runTest {
            val asked = CompletionEvent.Error(AiErrorKind.RATE_LIMITED, retryAfter = 5.seconds)
            val provider = Scripted(this, listOf(asked), answer)
            assertEquals(answer, retrying(provider, 30.seconds).stream(request, KEY).toList())
            assertEquals(listOf(0L, 5_000L), provider.starts)

            val tooLong = CompletionEvent.Error(AiErrorKind.RATE_LIMITED, retryAfter = 40.seconds)
            val refused = Scripted(this, listOf(tooLong), answer)
            assertEquals(listOf<CompletionEvent>(tooLong), retrying(refused, 30.seconds).stream(request, KEY).toList())
            assertEquals(1, refused.starts.size)
        }

    @Test
    fun `a stop during a wait ends the tries`() =
        runTest {
            val provider = Scripted(this, *Array(10) { listOf(busy) })
            val events = mutableListOf<CompletionEvent>()
            val call = launch { retrying(provider, 1.minutes).stream(request, KEY).collect { events += it } }
            advanceTimeBy(3.seconds)
            runCurrent()
            call.cancel()
            advanceTimeBy(2.minutes)
            assertEquals(listOf(0L, 2_000L), provider.starts)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `the waits have up to a quarter more at random`() =
        runTest {
            val provider = Scripted(this, listOf(busy), answer)
            val most =
                object : Random() {
                    override fun nextBits(bitCount: Int): Int = -1 ushr (32 - bitCount)
                }
            AiRetry(provider, { 30.seconds }, testScheduler.timeSource, most).stream(request, KEY).toList()
            val second = provider.starts[1]
            assertTrue(second in 2_400L..2_500L, "second try at $second ms")
        }

    @Test
    fun `each failed try is told for the diagnostics, with what failed and the wait before the next`() =
        runTest {
            val heard = mutableListOf<PipelineEvent>()
            val cutShort =
                listOf(CompletionEvent.Delta("Half"), CompletionEvent.Done(Usage(9, 2), StopReason.MAX_TOKENS))
            val provider = Scripted(this, listOf(busy), cutShort)
            AiRetry(
                provider,
                { 30.seconds },
                testScheduler.timeSource,
                NO_JITTER,
                heard::add,
            ).stream(request, KEY).toList()
            assertEquals(
                listOf<PipelineEvent>(
                    PipelineEvent.AiTried(
                        ProviderId.GOOGLE,
                        "model",
                        attempt = 1,
                        kind = AiErrorKind.OVERLOADED,
                        detail = "HTTP 503",
                        retryIn = 2.seconds,
                    ),
                    PipelineEvent.AiTried(
                        ProviderId.GOOGLE,
                        "model",
                        attempt = 2,
                        stopReason = StopReason.MAX_TOKENS,
                        received = 4,
                    ),
                ),
                heard,
            )
            assertEquals(
                "ai call    google model: failed, overloaded (HTTP 503), try 1, 0 characters received, 0 ms, " +
                    "asked again in 2000 ms",
                EventLine.of(heard[0]),
            )
            assertEquals(
                "ai call    google model: ended max_tokens, try 2, 4 characters received, 0 ms",
                EventLine.of(heard[1]),
            )
        }

    @Test
    fun `a reply that ends whole is not told for the diagnostics`() =
        runTest {
            val heard = mutableListOf<PipelineEvent>()
            AiRetry(Scripted(this, answer), { 30.seconds }, testScheduler.timeSource, NO_JITTER, heard::add)
                .stream(request, KEY)
                .toList()
            assertTrue(heard.isEmpty())
        }

    private fun TestScope.retrying(
        provider: AiProvider,
        total: Duration,
    ) = AiRetry(provider, { total }, testScheduler.timeSource, NO_JITTER)

    /** Replies to its [n]th call with the [n]th of [tries] (the last, past them), each after [takes]. */
    private class Scripted(
        private val scope: TestScope,
        vararg val tries: List<CompletionEvent>,
        private val takes: Duration = Duration.ZERO,
    ) : AiProvider {
        override val id: ProviderId = ProviderId.GOOGLE
        val starts = mutableListOf<Long>()

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                val n = starts.size
                starts += scope.currentTime
                delay(takes)
                tries[n.coerceAtMost(tries.lastIndex)].forEach { emit(it) }
            }
    }

    private companion object {
        val KEY = Secret("key")

        // No jitter: a random that always draws zero.
        val NO_JITTER =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
            }
    }
}
