package io.github.wkusnierczyk.sleevesight.ai.anthropic

import io.github.wkusnierczyk.sleevesight.core.ai.AiErrorKind
import io.github.wkusnierczyk.sleevesight.core.ai.CompletionEvent
import io.github.wkusnierczyk.sleevesight.core.ai.StopReason
import io.github.wkusnierczyk.sleevesight.core.ai.Usage
import io.github.wkusnierczyk.sleevesight.net.NetError
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult

/**
 * Turns the data of one Messages stream's events into [CompletionEvent]s: `message_start` (input
 * tokens), `content_block_delta` with a `text_delta` (a [CompletionEvent.Delta]), `message_delta`
 * (the stop reason and output tokens), `message_stop` ([CompletionEvent.Done]) and `error`
 * ([CompletionEvent.Error]). `ping`, the block boundaries, other deltas and event types this client
 * does not know are skipped, so new ones do not break it. After the one terminal event, nothing
 * more is emitted.
 */
internal class MessageStream {
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var stopReason: StopReason = StopReason.OTHER
    private var started = false
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
            "message_start" -> {
                started = true
                event.message?.usage?.let(::count)
                null
            }

            "content_block_delta" -> {
                event.delta?.takeIf { it.type == "text_delta" }?.text?.takeIf { it.isNotEmpty() }?.let {
                    CompletionEvent.Delta(it)
                }
            }

            "message_delta" -> {
                event.delta?.stopReason?.let { stopReason = stopReasonOf(it) }
                event.usage?.let(::count)
                null
            }

            "message_stop" -> {
                end(CompletionEvent.Done(Usage(inputTokens, outputTokens), stopReason))
            }

            "error" -> {
                end(CompletionEvent.Error(event.error?.type?.let(::kindOfType) ?: AiErrorKind.SERVER))
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
                result is NetResult.Success && !result.value.isSuccess -> CompletionEvent.Error(kindOf(result.value))
                else -> CompletionEvent.Error(AiErrorKind.INCOMPLETE)
            }
        return error?.let(::end)
    }

    // A connection lost or cut short after the message began leaves an incomplete reply.
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

    // Anthropic reports the usage so far in each event that carries it; the latest counts win.
    private fun count(usage: WireUsage) {
        val input = listOfNotNull(usage.inputTokens, usage.cacheCreationInputTokens, usage.cacheReadInputTokens)
        if (input.isNotEmpty()) inputTokens = input.sum()
        usage.outputTokens?.let { outputTokens = it }
    }

    private fun stopReasonOf(reason: String): StopReason =
        when (reason) {
            "end_turn", "stop_sequence" -> StopReason.END
            "max_tokens" -> StopReason.MAX_TOKENS
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
}
