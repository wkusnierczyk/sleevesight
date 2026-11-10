package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
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
 * connection failed or timed out ([AiErrorKind.CONNECTION_FAILED], [AiErrorKind.TIMED_OUT]), or with
 * its reply cut short ([AiErrorKind.INCOMPLETE]) is asked again, after a wait that doubles each time
 * ([FIRST_WAIT], then twice as long, each with up to a quarter more at random, so the clients a
 * service refused together do not come back together), or after the wait the service asked for
 * (`retryAfter`), while the next try starts within [allowance] of the first failure; the last wait is
 * shortened to what is left, when at least [FIRST_WAIT] is, for one last try at its end. A wait the
 * service asked for is kept as asked, so one longer than what is left ends the tries. Past it, the
 * last failure is the call's. A try under way when the allowance runs out runs to its end. With no
 * network ([AiErrorKind.OFFLINE]), or any other failure, which no retry mends soon, the error is the
 * call's at once.
 *
 * Nothing new is shown while it asks again: the caller sees one call, which ends later. A reply that
 * broke off after some of its text, or a tool call, reached the caller is not asked again: the caller
 * has shown that text, and a new reply would follow it. The tries cost what they cost: a busy refusal
 * is not billed, but a reply that broke off may be, for what it had written.
 *
 * A Stop cancels the collection, and with it any wait and try. [allowance] is read once per call, as
 * it starts. [ended] hears, for the diagnostics, each try that failed (with the wait before the next,
 * if any) and each that ended other than at its reply's end ([PipelineEvent.AiTried]).
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
            var firstFailure: TimeMark? = null
            var attempt = 1
            while (true) {
                val started = timeSource.markNow()
                var received = 0
                var passedOn = false
                var end: CompletionEvent? = null
                provider.stream(request, key).collect { event ->
                    when (event) {
                        is CompletionEvent.Delta -> {
                            received += event.text.length
                            passedOn = true
                            emit(event)
                        }

                        is CompletionEvent.ToolUse -> {
                            passedOn = true
                            emit(event)
                        }

                        is CompletionEvent.Done, is CompletionEvent.Error -> {
                            end = event
                        }
                    }
                }
                val failed = end as? CompletionEvent.Error
                val wait =
                    failed?.takeIf { !passedOn && it.kind in RETRIED }?.let { error ->
                        val since = firstFailure ?: timeSource.markNow().also { firstFailure = it }
                        nextWait(attempt, error, total - since.elapsedNow())
                    }
                tried(request, attempt, end, received, started.elapsedNow(), wait)
                if (wait == null) {
                    end?.let { emit(it) }
                    return@flow
                }
                delay(wait)
                attempt++
            }
        }

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
        val wait = backoff + backoff * (JITTER * random.nextDouble())
        return when {
            asked != null -> asked.takeIf { it <= left }
            wait <= left -> wait
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

        /** Up to this share of each wait is added at random. */
        private const val JITTER = 0.25

        /** A bound on the doubling, far past any allowance offered. */
        private const val MAX_DOUBLINGS = 10

        /** The failures asked again: busy, a connection failed or timed out, a reply cut short. */
        val RETRIED: Set<AiErrorKind> =
            setOf(
                AiErrorKind.RATE_LIMITED,
                AiErrorKind.OVERLOADED,
                AiErrorKind.CONNECTION_FAILED,
                AiErrorKind.TIMED_OUT,
                AiErrorKind.INCOMPLETE,
            )

        // The ends of a reply that the diagnostics do not name: complete, or calling its tools.
        private val WHOLE = setOf(StopReason.END, StopReason.TOOL_USE)
    }
}
