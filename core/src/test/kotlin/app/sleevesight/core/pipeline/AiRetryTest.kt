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
import app.sleevesight.core.settings.AiWebSearch
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
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

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
    fun `a connection not made, one not made in time and a reply cut short before its text are asked again`() =
        runTest {
            val cut =
                CompletionEvent.Error(
                    AiErrorKind.INCOMPLETE,
                    breakoff = Breakoff(BreakoffCause.NO_END, Usage(9, 0)),
                )
            val failures =
                listOf(
                    CompletionEvent.Error(AiErrorKind.CONNECT_FAILED),
                    CompletionEvent.Error(AiErrorKind.CONNECT_TIMED_OUT),
                    cut,
                    CompletionEvent.Error(
                        AiErrorKind.INCOMPLETE,
                        breakoff = Breakoff(BreakoffCause.CONNECTION_LOST, Usage(9, 0)),
                    ),
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
                Scripted(this, listOf(CompletionEvent.Error(AiErrorKind.CONNECT_FAILED)), listOf(offline), answer)
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

    @Test
    fun `the total runs from the failure's arrival, not from its stream's close`() =
        runTest {
            var calls = 0
            // The busy answer comes at once, and the stream closes 31 s later.
            val lingering =
                object : AiProvider {
                    override val id: ProviderId = ProviderId.GOOGLE

                    override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

                    override fun stream(
                        request: CompletionRequest,
                        key: Secret,
                    ): Flow<CompletionEvent> =
                        flow {
                            calls++
                            emit(busy)
                            delay(31.seconds)
                        }
                }
            assertEquals(listOf<CompletionEvent>(busy), retrying(lingering, 30.seconds).stream(request, KEY).toList())
            assertEquals(1, calls)
        }

    @Test
    fun `a reply cut off by the network gone is not asked again, and its log line names the transport's label`() =
        runTest {
            val gone =
                CompletionEvent.Error(
                    AiErrorKind.INCOMPLETE,
                    breakoff = Breakoff(BreakoffCause.NO_NETWORK, Usage(3, 0), "SocketException (ENETDOWN)"),
                )
            val heard = mutableListOf<PipelineEvent>()
            val provider = Scripted(this, listOf(gone), answer)
            val events =
                AiRetry(
                    provider,
                    { 30.seconds },
                    testScheduler.timeSource,
                    NO_JITTER,
                    heard::add,
                ).stream(request, KEY).toList()
            assertEquals(listOf<CompletionEvent>(gone), events)
            assertEquals(1, provider.starts.size)
            assertEquals(ReplyFailures.CONNECTION_LOST, ReplyFailures.of(gone))
            assertEquals(
                "ai call    google model: failed, incomplete (no network, SocketException (ENETDOWN)), try 1, " +
                    "0 characters received, 0 ms, not asked again",
                EventLine.of(heard.single()),
            )
        }

    @Test
    fun `a gate that closes during the wait ends the tries at once, with no second request`() =
        runTest {
            val provider = Scripted(this, listOf(busy), answer)
            var open = true
            launch {
                delay(1.seconds)
                open = false
            }
            val counted = mutableListOf<Long>()
            val events =
                underTerms({ open }, { counted += currentTime }) {
                    retrying(provider, 30.seconds).stream(request, KEY).toList()
                }
            assertEquals(listOf<CompletionEvent>(busy), events)
            assertEquals(listOf(0L), provider.starts)
            assertTrue(counted.isEmpty())
            // Within a gate check of its closing, not at the end of the 2 s wait.
            assertTrue(currentTime <= 1_250L, "ended at $currentTime ms")
        }

    @Test
    fun `a track change during the wait sends no second request`() =
        runTest {
            val provider = Scripted(this, listOf(busy), listOf(busy), answer)
            var playing = "first"
            // The caller's gate: the track it asked for is still the one playing.
            val asked = playing
            launch {
                delay(3.seconds)
                playing = "second"
            }
            val events =
                underTerms({ playing == asked }, {}) { retrying(provider, 30.seconds).stream(request, KEY).toList() }
            assertEquals(listOf<CompletionEvent>(busy), events)
            // The try at 2 s, the gate open; none at 6 s, the track changed at 3 s.
            assertEquals(listOf(0L, 2_000L), provider.starts)
        }

    @Test
    fun `each try again is counted to the caller, right before it is sent`() =
        runTest {
            val provider = Scripted(this, listOf(busy), listOf(busy), answer)
            val counted = mutableListOf<Long>()
            underTerms(
                counted = { counted += currentTime },
            ) { retrying(provider, 30.seconds).stream(request, KEY).toList() }
            assertEquals(listOf(2_000L, 6_000L), counted)
        }

    @Test
    fun `the last try, its wait shortened to the total, is made though the clock runs a little late`() =
        runTest {
            // A clock 1 % ahead of the waits, as a real one runs past each wait by the scheduling.
            val late =
                object : AbstractLongTimeSource(DurationUnit.MICROSECONDS) {
                    override fun read(): Long = testScheduler.currentTime * 1_010
                }
            val provider = Scripted(this, *Array(10) { listOf(busy) })
            AiRetry(provider, { 20.seconds }, late, NO_JITTER).stream(request, KEY).toList()
            // After 14 s the 6 s left (less the clock's lead) are waited for one last try, near 20 s.
            assertEquals(5, provider.starts.size, provider.starts.toString())
            assertTrue(provider.starts.last() in 19_000L..20_000L, provider.starts.toString())
        }

    @Test
    fun `a question's cost counts every request, a reply cut short and the answer after it`() =
        runTest {
            val cut =
                CompletionEvent.Error(
                    AiErrorKind.INCOMPLETE,
                    breakoff = Breakoff(BreakoffCause.NO_END, Usage(9, 0)),
                )
            val provider = Scripted(this, listOf(cut), answer)
            val question =
                object : WebQuestion {
                    override val knowledge = "KNOWLEDGE"
                    override val query = "q"

                    override fun once(pages: List<app.sleevesight.core.sources.WebPage>) = "ONCE"

                    override fun agent(limit: Int) = "AGENT"
                }
            val counted = mutableListOf<QuestionCost>()
            val reply =
                WebQuestions(AiAccess(retrying(provider, 30.seconds), KEY), null)
                    .ask(question, AiWebSearch.OFF, 3, request, counted = { counted += it })
            assertEquals(2, provider.starts.size)
            assertEquals(QuestionCost(calls = 2), reply.cost)
            assertEquals(listOf(QuestionCost.CALL, QuestionCost.CALL), counted)
        }

    @Test
    fun `a timeout after the request was sent, a reply that fell silent and one too long are not asked again`() =
        runTest {
            val failures =
                listOf(
                    CompletionEvent.Error(AiErrorKind.TIMED_OUT, detail = "SocketTimeoutException"),
                    CompletionEvent.Error(
                        AiErrorKind.INCOMPLETE,
                        breakoff = Breakoff(BreakoffCause.TIMED_OUT, Usage(9, 0)),
                    ),
                    CompletionEvent.Error(
                        AiErrorKind.INCOMPLETE,
                        breakoff = Breakoff(BreakoffCause.TOO_LARGE, Usage(9, 0)),
                    ),
                )
            for (failed in failures) {
                val provider = Scripted(this, listOf(failed), answer)
                assertEquals(
                    listOf<CompletionEvent>(failed),
                    retrying(provider, 30.seconds).stream(request, KEY).toList(),
                )
                assertEquals(1, provider.starts.size, failed.toString())
            }
        }

    @Test
    fun `a connection that failed once the request may have been sent is not asked again`() =
        runTest {
            val failed = CompletionEvent.Error(AiErrorKind.CONNECTION_FAILED, detail = "SocketException (ECONNRESET)")
            val provider = Scripted(this, listOf(failed), answer)
            assertEquals(listOf<CompletionEvent>(failed), retrying(provider, 30.seconds).stream(request, KEY).toList())
            assertEquals(listOf(0L), provider.starts)
        }

    @Test
    fun `a reply cut short once some of it was generated, or whose final count has output, is not asked again`() =
        runTest {
            // A thinking model cut off mid-thought: no text yet, but its thinking generated, and billed.
            val breakoffs =
                listOf(BreakoffCause.NO_END, BreakoffCause.CONNECTION_LOST).flatMap { cause ->
                    listOf(Breakoff(cause, Usage(9, 1)), Breakoff(cause, Usage(9, 0), generated = true))
                }
            for (breakoff in breakoffs) {
                val failed = CompletionEvent.Error(AiErrorKind.INCOMPLETE, breakoff = breakoff)
                val provider = Scripted(this, listOf(failed), answer)
                assertEquals(
                    listOf<CompletionEvent>(failed),
                    retrying(provider, 30.seconds).stream(request, KEY).toList(),
                )
                assertEquals(1, provider.starts.size, breakoff.toString())
            }
        }

    @Test
    fun `a short wait the service asks for is a floor, not a faster pace`() =
        runTest {
            val soon = CompletionEvent.Error(AiErrorKind.RATE_LIMITED, retryAfter = 500.milliseconds)
            val provider = Scripted(this, *Array(40) { listOf(soon) })
            retrying(provider, 30.seconds).stream(request, KEY).toList()
            assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L), provider.starts)
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
