package app.sleevesight.ai.openrouter

import app.sleevesight.ai.UNREADABLE_EVENT
import app.sleevesight.ai.brokeOff
import app.sleevesight.ai.codeDetail
import app.sleevesight.ai.decode
import app.sleevesight.ai.failedWith
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.NetError
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlin.time.Clock

/**
 * Turns the data of one chat-completions stream's events into [CompletionEvent]s (#1157). Each
 * event is a chunk: its first choice's `delta.content` becomes a [CompletionEvent.Delta] (its
 * `reasoning`, the model's thinking, is skipped, but counts as generated), its `finish_reason` is
 * the stop reason, and a `usage` replaces the counts before it (OpenRouter sends it in the last
 * chunk). `[DONE]` ends the stream; so does the transport, after a finish reason. An `error` in a
 * chunk (a provider failing mid-reply, with `finish_reason` `error`) ends it with its kind, as does a
 * moderation refusal (403), which ends as a refusal does. After the one terminal event, nothing more
 * is emitted.
 */
internal class ChatStream(
    private val clock: Clock,
) {
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var stopReason: StopReason? = null
    private var started = false

    // Whether any of the reply was generated (#1196): text, reasoning, or output tokens counted.
    private var generated = false
    private var ended = false

    /** Set when an event's data was not JSON this client can read; the stream should be stopped. */
    var malformed: Boolean = false
        private set

    /** The event that [data] makes: its text, if any, or the terminal event. */
    fun read(data: String): CompletionEvent? {
        val chunk = if (ended || data.trim() == DONE) null else decode<WireChunk>(data)
        return when {
            ended -> {
                null
            }

            data.trim() == DONE -> {
                end(null)
            }

            chunk == null -> {
                malformed = true
                end(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE, detail = UNREADABLE_EVENT))
            }

            chunk.error != null -> {
                end(streamError(chunk.error))
            }

            else -> {
                handle(chunk)
            }
        }
    }

    private fun handle(chunk: WireChunk): CompletionEvent? {
        started = true
        chunk.usage?.let { usage ->
            usage.promptTokens?.let { inputTokens = it }
            usage.completionTokens?.let { outputTokens = it }
        }
        val choice = chunk.choices.firstOrNull()
        choice?.finishReason?.let { stopReason = stopReasonOf(it) }
        val text = choice?.delta?.content.orEmpty()
        generated = generated || text.isNotEmpty() || !choice?.delta?.reasoning.isNullOrEmpty() || outputTokens > 0
        return text.takeIf { it.isNotEmpty() }?.let { CompletionEvent.Delta(it) }
    }

    /**
     * The terminal event after the transport finished with [result] (null when the stream was
     * stopped here, or `[DONE]` came), or null if one was emitted already. A finish reason already
     * read completes the reply whatever the transport says after it.
     */
    fun end(result: NetResult<NetResponse>?): CompletionEvent? {
        val reason = stopReason
        val event =
            when {
                ended -> null
                reason != null -> CompletionEvent.Done(Usage(inputTokens, outputTokens), reason)
                result is NetResult.Failure -> failure(result.error)
                result is NetResult.Success && !result.value.isSuccess -> errorOf(result.value, clock.now())
                else -> brokeOff(null, Usage(inputTokens, outputTokens))
            }
        return event?.let(::end)
    }

    // A moderation refusal ends as a refusal does; any other error with its kind.
    private fun streamError(error: WireError): CompletionEvent {
        val status = error.code?.content?.toIntOrNull()
        return if (status == FORBIDDEN) {
            CompletionEvent.Done(Usage(inputTokens, outputTokens), StopReason.REFUSAL)
        } else {
            CompletionEvent.Error(kindOf(status, error.message), detail = codeDetail(error.code?.content))
        }
    }

    // A connection lost or cut short after the reply began leaves an incomplete reply.
    private fun failure(error: NetError): CompletionEvent.Error =
        when {
            error is NetError.RateLimited -> failedWith(error, AiErrorKind.RATE_LIMITED)
            started && (error is NetError.Offline || error is NetError.TooLarge) -> {
                brokeOff(error, Usage(inputTokens, outputTokens))
            }
            else -> failedWith(error, kindOf(error))
        }

    private fun end(event: CompletionEvent): CompletionEvent {
        ended = true
        return if (event is CompletionEvent.Error && generated) event.copy(generated = true) else event
    }

    private fun stopReasonOf(reason: String): StopReason? =
        when (reason) {
            "stop" -> StopReason.END
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            // An error's chunk carries the error itself, which ends the stream.
            "error" -> null
            else -> StopReason.OTHER
        }

    private companion object {
        const val DONE = "[DONE]"
        const val FORBIDDEN = 403
    }
}
