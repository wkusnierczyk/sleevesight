package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [provider], asking again when a call fails for a while only (#1164, *Retry when the AI fails*,
 * `Settings.aiRetry`), as a busy MusicBrainz is asked again (#1120): every AI call the pipeline makes
 * goes through it (the insight, a genre's list and reasons, the song's, artist's and album's
 * insights, *Ask AI*), from the one place its client is handed to the pipeline (`AiAccess`).
 *
 * A call that fails as busy ([AiErrorKind.RATE_LIMITED], [AiErrorKind.OVERLOADED]), with its
 * connection failed or not made in time ([AiErrorKind.CONNECTION_FAILED],
 * [AiErrorKind.CONNECT_TIMED_OUT]), or with its reply cut short, its connection lost or its stream
 * ended early ([AiErrorKind.INCOMPLETE]) is asked again, after a wait that doubles each time
 * ([FIRST_WAIT], then twice as long, each with up to a quarter more at random, so the clients a
 * service refused together do not come back together), or after the wait the service asked for
 * (`retryAfter`) when that is longer, while the next try starts within [allowance] of the first
 * failure; the last wait is shortened to what is left, when at least [FIRST_WAIT] is, for one last
 * try at its end. A wait the service asked for is kept as asked, so one longer than what is left
 * ends the tries. Past it, the last failure is the call's. The allowance runs from the first
 * failure's arrival, not from the end of its stream. A try under way when the allowance runs out
 * runs to its end. With no network ([AiErrorKind.OFFLINE], or a reply cut off by it,
 * [BreakoffCause.NO_NETWORK]), a timeout after the request was sent ([AiErrorKind.TIMED_OUT], or a
 * reply that fell silent, [BreakoffCause.TIMED_OUT]: likely billed, and likely to again), a reply
 * too long ([BreakoffCause.TOO_LARGE]), or any other failure, which no retry mends soon, the error is
 * the call's at once. The service's clients retry
 * nothing themselves: this is the one retry of an AI call.
 *
 * Nothing new is shown while it asks again: the caller sees one call, which ends later. A reply that
 * broke off after some of its text, or a tool call, reached the caller is not asked again: the caller
 * has shown that text, and a new reply would follow it. The tries cost what they cost: a busy refusal
 * is not billed, but a reply that broke off may be, for what it had written.
 *
 * A Stop cancels the collection, and with it any wait and try. The caller's [AiCallTerms], set around
 * its call ([underTerms]), go with it: its gate, asked while waiting and right before each try again,
 * ends the tries once it closes (the track changed, the app left); and each try again is counted to
 * it, as the caller counted the first, so what the call cost counts every request sent. [allowance]
 * is read once per call, as it starts. [ended] hears, for the diagnostics, each try that failed
 * (with the wait before the next, if any) and each that ended other than at its reply's end
 * ([PipelineEvent.AiTried]).
 */
class AiRetry(
    private val provider: AiProvider,
    private val allowance: () -> Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
    private val ended: (PipelineEvent) -> Unit = {},
) : AiProvider by provider {
    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        flow {
            val total = allowance()
            // The caller's terms for its call (`AiCallTerms`): its gate, and the count of what it pays.
            val terms = currentCoroutineContext()[AiCallTerms] ?: AiCallTerms()
            var firstFailure: TimeMark? = null
            var attempt = 1
            while (true) {
                val started = timeSource.markNow()
                // The total runs from the first failure as it arrives, whenever the stream closes after it.
                val made = tryOnce(request, key) { if (firstFailure == null) firstFailure = timeSource.markNow() }
                val end = made.end
                val wait =
                    (end as? CompletionEvent.Error)?.takeIf { !made.passedOn && retried(it) }?.let { error ->
                        nextWait(attempt, error, total - checkNotNull(firstFailure).elapsedNow())
                    }
                tried(request, attempt, end, made.received, started.elapsedNow(), wait)
                if (wait == null) {
                    end?.let { emit(it) }
                    return@flow
                }
                // The caller's gate is asked while waiting and right before the next try: once it says
                // no (the track changed, the app left), the wait ends and no request is sent.
                val open = waitOpen(wait, terms)
                // Never a try past the total, but for the scheduling's own lateness: the last wait,
                // shortened to end on it, ends a little after it on a real clock.
                if (!open || checkNotNull(firstFailure).elapsedNow() > total + LATE) {
                    end?.let { emit(it) }
                    return@flow
                }
                terms.counted()
                attempt++
            }
        }

    // How one try went: its end, how much of its text came, and whether any of it, or a tool call,
    // was passed on.
    private class Try {
        var end: CompletionEvent? = null
        var received = 0
        var passedOn = false
    }

    // One try of [request]: its text and tool calls passed on as they come, its end held back;
    // [failing] told as an error arrives.
    private suspend fun FlowCollector<CompletionEvent>.tryOnce(
        request: CompletionRequest,
        key: Secret,
        failing: () -> Unit,
    ): Try {
        val made = Try()
        provider.stream(request, key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    made.received += event.text.length
                    made.passedOn = true
                    emit(event)
                }

                is CompletionEvent.ToolUse -> {
                    made.passedOn = true
                    emit(event)
                }

                is CompletionEvent.Done -> {
                    made.end = event
                }

                is CompletionEvent.Error -> {
                    made.end = event
                    failing()
                }
            }
        }
        return made
    }

    // Waits [wait], asking [terms]' gate every [GATE_CHECK] and once more at its end: false as soon as
    // it says no.
    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `delay` as suspending; it is.
    private suspend fun waitOpen(
        wait: Duration,
        terms: AiCallTerms,
    ): Boolean {
        var left = wait
        while (left.isPositive()) {
            val step = minOf(left, GATE_CHECK)
            delay(step)
            left -= step
            if (!terms.mayPay()) return false
        }
        return terms.mayPay()
    }

    // Whether [error] is asked again: one of [RETRIED]; a reply cut short only when its connection
    // was lost or its stream ended early, not when it timed out (likely billed, and likely to again),
    // was too long, or the network went away.
    private fun retried(error: CompletionEvent.Error): Boolean =
        error.kind in RETRIED && (error.breakoff?.cause ?: BreakoffCause.NO_END) in BROKEN_OFF

    /**
     * The wait before try [attempt] + 1 after [error], with [left] of the allowance; null to give up.
     */
    private fun nextWait(
        attempt: Int,
        error: CompletionEvent.Error,
        left: Duration,
    ): Duration? {
        val asked = error.retryAfter?.takeIf { it.isPositive() }
        val backoff = FIRST_WAIT * (1 shl (attempt - 1).coerceAtMost(MAX_DOUBLINGS))
        // The service's wait is a floor: a short one does not make the tries come faster.
        val wait = maxOf(backoff + backoff * (JITTER * random.nextDouble()), asked ?: Duration.ZERO)
        return when {
            wait <= left -> wait
            asked != null && asked > left -> null
            left >= FIRST_WAIT -> left
            else -> null
        }
    }

    // For the diagnostics: a try that failed, or that ended other than at its reply's end.
    @Suppress("LongParameterList") // The try, how it ended, and what follows.
    private fun tried(
        request: CompletionRequest,
        attempt: Int,
        end: CompletionEvent?,
        received: Int,
        latency: Duration,
        retryIn: Duration?,
    ) {
        val error = end as? CompletionEvent.Error
        val stop = (end as? CompletionEvent.Done)?.stopReason?.takeUnless { it in WHOLE }
        if (error == null && stop == null) return
        ended(
            PipelineEvent.AiTried(
                provider = provider.id,
                model = request.model,
                attempt = attempt,
                kind = error?.kind,
                stopReason = stop,
                detail = error?.detail,
                breakoff = error?.breakoff,
                received = received,
                latency = latency,
                retryIn = retryIn,
            ),
        )
    }

    companion object {
        /** The first wait before asking again: 2 s, then 4, 8, 16 s, which add up to 30 s. */
        val FIRST_WAIT: Duration = 2.seconds

        /** How late a try may start past the total, for the scheduling's own delay. */
        private val LATE: Duration = 1.seconds

        /** How often the caller's gate is asked while waiting. */
        private val GATE_CHECK: Duration = 250.milliseconds

        /** Up to this share of each wait is added at random. */
        private const val JITTER = 0.25

        /** A bound on the doubling, far past any allowance offered. */
        private const val MAX_DOUBLINGS = 10

        /**
         * The failures asked again: busy, a connection that failed or could not be made in time, a
         * reply cut short. Not a timeout after the request was sent ([AiErrorKind.TIMED_OUT]).
         */
        val RETRIED: Set<AiErrorKind> =
            setOf(
                AiErrorKind.RATE_LIMITED,
                AiErrorKind.OVERLOADED,
                AiErrorKind.CONNECTION_FAILED,
                AiErrorKind.CONNECT_TIMED_OUT,
                AiErrorKind.INCOMPLETE,
            )

        // How a reply cut short ([AiErrorKind.INCOMPLETE]) may have broken off to be asked again.
        private val BROKEN_OFF = setOf(BreakoffCause.NO_END, BreakoffCause.CONNECTION_LOST)

        // The ends of a reply that the diagnostics do not name: complete, or calling its tools.
        private val WHOLE = setOf(StopReason.END, StopReason.TOOL_USE)
    }
}

/**
 * What an AI call's tries again ([AiRetry]) answer to, set by its caller around the call
 * ([underTerms], #1164): [mayPay], the caller's gate for a paid step, asked while waiting and right
 * before each try again, none made once it says no; and [counted], told of each try again as it is
 * sent, as the caller counted the first. A call with none set is retried with no gate but its
 * cancellation, and counts nothing.
 */
class AiCallTerms(
    val mayPay: () -> Boolean = { true },
    val counted: () -> Unit = {},
) : AbstractCoroutineContextElement(AiCallTerms) {
    companion object Key : CoroutineContext.Key<AiCallTerms>
}

/** [block], an AI call, under the caller's [mayPay] and with its tries again [counted] ([AiCallTerms]). */
suspend fun <T> underTerms(
    mayPay: () -> Boolean = { true },
    counted: () -> Unit = {},
    block: suspend () -> T,
): T = withContext(AiCallTerms(mayPay, counted)) { block() }
