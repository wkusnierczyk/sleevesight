package app.sleevesight.ai.openai

import app.sleevesight.ai.brokeOff
import app.sleevesight.ai.decode
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.NetError
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/**
 * Turns the data of one Responses stream's events into [CompletionEvent]s: `response.created`
 * (the response began), `response.output_text.delta` (a [CompletionEvent.Delta]),
 * `response.refusal.delta` (the model declined: its text is not the reply, so it is not emitted),
 * `response.completed` and `response.incomplete` ([CompletionEvent.Done], with the usage), and
 * `response.failed` and `error` ([CompletionEvent.Error]). A prompt declined for a usage policy
 * ([POLICY_CODES]), in a failed response, an `error` event or an error response, is declined as a
 * refusal is: [CompletionEvent.Done] with [StopReason.REFUSAL], with no usage when none is
 * given. The reasoning items, the item and part boundaries, and event types this client does not
 * know are skipped, so new ones do not break it. After the one terminal event, nothing more is
 * emitted. [clock] reads a `Retry-After` given as a date. A stream that ends without a terminal
 * event ends with [AiErrorKind.INCOMPLETE] and how it broke off (`brokeOff`), with no usage: the
 * service gives it only in the terminal event.
 *
 * A response whose output calls tools (#801, `function_call` items) ends with
 * [CompletionEvent.ToolUse] just before its [CompletionEvent.Done], with [StopReason.TOOL_USE]: each
 * call's `call_id`, name and arguments, and the output items to send back as the next request's
 * input: all of them, in order (the reasoning, with its encrypted content, an assistant message,
 * the calls), each as its `response.output_item.done` gave it.
 */
internal class ResponseStream(
    private val clock: Clock,
) {
    private var started = false
    private var refused = false
    private var ended = false
    private val items = mutableListOf<JsonObject>()

    /** Set when an event's data was not JSON this client can read; the stream should be stopped. */
    var malformed: Boolean = false
        private set

    /** The events that [data] makes, if any: two for the end of a response that calls tools. */
    fun read(data: String): List<CompletionEvent> {
        val event = if (ended) null else decode<WireEvent>(data)
        return when {
            ended -> {
                emptyList()
            }

            event == null -> {
                malformed = true
                listOf(end(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE)))
            }

            event.type == "response.output_item.done" -> {
                event.item?.let(items::add)
                emptyList()
            }

            event.type == "response.completed" && !refused && calls().isNotEmpty() -> {
                // Every output item, in order, as the next request's input must hold the turn.
                val replay = JsonArray(items.toList())
                listOf(
                    CompletionEvent.ToolUse(ToolTurn(calls(), replay.toString())),
                    end(done(event.response, StopReason.TOOL_USE)),
                )
            }

            else -> {
                listOfNotNull(handle(event))
            }
        }
    }

    // The tool calls among the output items done.
    private fun calls(): List<ToolCall> =
        items.filter { typeOf(it) == FUNCTION_CALL }.mapNotNull { item ->
            val id = text(item, "call_id") ?: return@mapNotNull null
            val name = text(item, "name") ?: return@mapNotNull null
            ToolCall(id, name, text(item, "arguments") ?: "{}")
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
                val code = event.code
                // The event carries no usage.
                if (code in POLICY_CODES) {
                    end(REFUSED)
                } else {
                    end(CompletionEvent.Error(code?.let(::kindOfCode) ?: AiErrorKind.SERVER))
                }
            }

            else -> {
                null
            }
        }

    /**
     * The terminal event after the transport finished with [result] (null when the stream was
     * stopped here), or none if one was emitted already.
     */
    fun end(result: NetResult<NetResponse>?): List<CompletionEvent> = listOfNotNull(last(result))

    private fun last(result: NetResult<NetResponse>?): CompletionEvent? {
        val last =
            when {
                ended -> null
                result is NetResult.Failure -> failure(result.error)
                result is NetResult.Success && declinesPrompt(result.value) -> REFUSED
                result is NetResult.Success && !result.value.isSuccess -> errorOf(result.value, clock.now())
                else -> brokeOff(null, NO_USAGE)
            }
        return last?.let(::end)
    }

    // A connection lost or cut short after the response began leaves an incomplete reply.
    private fun failure(error: NetError): CompletionEvent.Error =
        when {
            error is NetError.RateLimited -> CompletionEvent.Error(AiErrorKind.RATE_LIMITED, error.retryAfter)
            started && (error is NetError.Offline || error is NetError.TooLarge) -> brokeOff(error, NO_USAGE)
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
        val NO_USAGE = Usage(0, 0)

        const val FUNCTION_CALL = "function_call"

        fun typeOf(item: JsonObject): String? = text(item, "type")

        fun text(
            item: JsonObject,
            key: String,
        ): String? = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A refusal with no usage given. */
        val REFUSED = CompletionEvent.Done(NO_USAGE, StopReason.REFUSAL)
    }
}
