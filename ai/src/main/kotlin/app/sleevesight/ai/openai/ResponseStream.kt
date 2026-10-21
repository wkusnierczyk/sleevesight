package app.sleevesight.ai.openai

import app.sleevesight.ai.decode
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.NetError
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlin.time.Clock

/**
 * Turns the data of one Responses stream's events into [CompletionEvent]s: `response.created`
 * (the response began), `response.output_text.delta` (a [CompletionEvent.Delta]),
 * `response.refusal.delta` (the model declined: its text is not the reply, so it is not emitted),
 * `response.completed` and `response.incomplete` ([CompletionEvent.Done], with the usage), and
 * `response.failed` and `error` ([CompletionEvent.Error]). A response failed for a usage policy
 * ([POLICY_CODES]) is the model declining, as a refusal is: [CompletionEvent.Done] with
 * [StopReason.REFUSAL]. The reasoning items, the item and part boundaries, and event types this
 * client does not know are skipped, so new ones do not break it. After the one terminal event,
 * nothing more is emitted. [clock] reads a `Retry-After` given as a date.
 */
internal class ResponseStream(
    private val clock: Clock,
) {
    private var started = false
    private var refused = false
    private var ended = false

    /** Set when an event's data was not JSON this client can read; the stream should be stopped. */
    var malformed: Boolean = false
        private set

    /** The event that [data] makes, if any. */
    fun read(data: String): CompletionEvent? {
        val event = if (ended) null else decode<WireEvent>(data)
        return when {
            ended -> {
                null
            }

            event == null -> {
                malformed = true
                end(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE))
            }

            else -> {
                handle(event)
            }
        }
    }

    private fun handle(event: WireEvent): CompletionEvent? =
        when (event.type) {
            "response.created" -> {
                started = true
                null
            }

            "response.output_text.delta" -> {
                event.delta?.takeIf { it.isNotEmpty() }?.let { CompletionEvent.Delta(it) }
            }

            "response.refusal.delta", "response.refusal.done" -> {
                refused = true
                null
            }

            "response.completed" -> {
                end(done(event.response, if (refused) StopReason.REFUSAL else StopReason.END))
            }

            "response.incomplete" -> {
                val reason = event.response?.incompleteDetails?.reason
                end(done(event.response, if (refused) StopReason.REFUSAL else incompleteReason(reason)))
            }

            "response.failed" -> {
                end(failed(event.response))
            }

            "error" -> {
                end(CompletionEvent.Error(event.code?.let(::kindOfCode) ?: AiErrorKind.SERVER))
            }

            else -> {
                null
            }
        }

    /**
     * The terminal event after the transport finished with [result] (null when the stream was
     * stopped here), or null if one was emitted already.
     */
    fun end(result: NetResult<NetResponse>?): CompletionEvent? {
        val error =
            when {
                ended -> null
                result is NetResult.Failure -> failure(result.error)
                result is NetResult.Success && !result.value.isSuccess -> errorOf(result.value, clock.now())
                else -> CompletionEvent.Error(AiErrorKind.INCOMPLETE)
            }
        return error?.let(::end)
    }

    // A connection lost or cut short after the response began leaves an incomplete reply.
    private fun failure(error: NetError): CompletionEvent.Error =
        when {
            error is NetError.RateLimited -> CompletionEvent.Error(AiErrorKind.RATE_LIMITED, error.retryAfter)
            started && (error is NetError.Offline || error is NetError.TooLarge) -> {
                CompletionEvent.Error(AiErrorKind.INCOMPLETE)
            }
            else -> CompletionEvent.Error(kindOf(error))
        }

    private fun end(event: CompletionEvent): CompletionEvent {
        ended = true
        return event
    }

    /** The usage is the response's own, in the terminal event; a response without it counts none. */
    private fun done(
        response: WireResponse?,
        reason: StopReason,
    ): CompletionEvent.Done {
        val usage = response?.usage
        return CompletionEvent.Done(Usage(usage?.inputTokens ?: 0, usage?.outputTokens ?: 0), reason)
    }

    /** A failed response's end: declined for a usage policy, else an error by its code, else its type. */
    private fun failed(response: WireResponse?): CompletionEvent {
        val error = response?.error
        return if (error?.code in POLICY_CODES) {
            done(response, StopReason.REFUSAL)
        } else {
            CompletionEvent.Error(
                error?.code?.let(::kindOfCode) ?: error?.type?.let(::kindOfType) ?: AiErrorKind.SERVER,
            )
        }
    }

    private fun incompleteReason(reason: String?): StopReason =
        when (reason) {
            "max_output_tokens" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

    private companion object {
        /**
         * The codes of a response failed for a usage policy, from the Responses reference on
         * 2026-10-21: the model, or OpenAI for it, declined the prompt.
         */
        val POLICY_CODES = setOf("bio_policy", "misalignment_policy_violation", "invalid_prompt")
    }
}
