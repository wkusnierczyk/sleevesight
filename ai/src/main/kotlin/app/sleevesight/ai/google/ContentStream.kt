package app.sleevesight.ai.google

import app.sleevesight.ai.brokeOff
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
 * Turns the data of one `streamGenerateContent` stream's events into [CompletionEvent]s. Each event
 * is a whole `GenerateContentResponse`: the first candidate's text parts become
 * [CompletionEvent.Delta]s (a `thought` part, the model's reasoning, is skipped), its
 * `finishReason` is the stop reason, and each `usageMetadata` replaces the counts before it. The
 * stream has no event of its own for its end: it ends after the chunk with the finish reason, so
 * [end] emits [CompletionEvent.Done] once the transport has finished, if a finish reason came, and
 * [CompletionEvent.Error] otherwise, with how the reply broke off (`brokeOff`). A prompt the service
 * blocked (`promptFeedback.blockReason`, with no candidate) is a refusal; an `error` in place of a
 * chunk ends it with its kind. After the one terminal event, nothing more is emitted.
 */
internal class ContentStream(
    private val clock: Clock,
) {
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var stopReason: StopReason? = null
    private var started = false
    private var ended = false

    /** Set when an event's data was not JSON this client can read; the stream should be stopped. */
    var malformed: Boolean = false
        private set

    /** The events that [data] makes: its text, if any, or the terminal error. */
    fun read(data: String): CompletionEvent? {
        val chunk = if (ended) null else decode<WireResponse>(data)
        return when {
            ended -> {
                null
            }

            chunk == null -> {
                malformed = true
                end(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE))
            }

            chunk.error != null -> {
                end(CompletionEvent.Error(kindOf(chunk.error) ?: AiErrorKind.SERVER))
            }

            else -> {
                handle(chunk)
            }
        }
    }

    private fun handle(chunk: WireResponse): CompletionEvent? {
        started = true
        chunk.usageMetadata?.let(::count)
        chunk.promptFeedback?.blockReason?.let { stopReason = StopReason.REFUSAL }
        val candidate = chunk.candidates.firstOrNull()
        candidate?.finishReason?.let { stopReason = stopReasonOf(it) }
        val text =
            candidate
                ?.content
                ?.parts
                .orEmpty()
                .filter { !it.thought }
                .mapNotNull { it.text }
                .joinToString("")
        return text.takeIf { it.isNotEmpty() }?.let { CompletionEvent.Delta(it) }
    }

    /**
     * The terminal event after the transport finished with [result] (null when the stream was
     * stopped here), or null if one was emitted already. A finish reason already read completes
     * the reply whatever the transport says after it: nothing more was to come.
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

    // A connection lost or cut short after the reply began leaves an incomplete reply.
    private fun failure(error: NetError): CompletionEvent.Error =
        when {
            error is NetError.RateLimited -> CompletionEvent.Error(AiErrorKind.RATE_LIMITED, error.retryAfter)
            started && (error is NetError.Offline || error is NetError.TooLarge) -> {
                brokeOff(error, Usage(inputTokens, outputTokens))
            }
            else -> CompletionEvent.Error(kindOf(error))
        }

    private fun end(event: CompletionEvent): CompletionEvent {
        ended = true
        return event
    }

    // The thinking tokens are billed as output (the pricing page: "Output price (including thinking
    // tokens)"), as Anthropic's output tokens include its reasoning.
    private fun count(usage: WireUsage) {
        if (usage.promptTokenCount != null || usage.toolUsePromptTokenCount != null) {
            inputTokens = (usage.promptTokenCount ?: 0) + (usage.toolUsePromptTokenCount ?: 0)
        }
        if (usage.candidatesTokenCount != null || usage.thoughtsTokenCount != null) {
            outputTokens = (usage.candidatesTokenCount ?: 0) + (usage.thoughtsTokenCount ?: 0)
        }
    }

    private fun stopReasonOf(reason: String): StopReason =
        when (reason) {
            "STOP" -> StopReason.END
            // `CONTINUATION`: the per-request limit was reached, and the reply goes on only in a
            // further request, which this client does not make.
            "MAX_TOKENS", "CONTINUATION" -> StopReason.MAX_TOKENS
            // `ESCALATION`: filtered by an escalation rule; `PUP_LIMITED_DISABLED`: the account is
            // limited for breaking the Prohibited Use Policy. Either way the service declined.
            "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "ESCALATION",
            "PUP_LIMITED_DISABLED",
            -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
}
