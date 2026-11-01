package app.sleevesight.ai.anthropic

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
 * Turns the data of one Messages stream's events into [CompletionEvent]s: `message_start` (input
 * tokens), `content_block_delta` with a `text_delta` (a [CompletionEvent.Delta]), `message_delta`
 * (the stop reason and output tokens), `message_stop` ([CompletionEvent.Done]) and `error`
 * ([CompletionEvent.Error]). `ping`, the block boundaries, other deltas and event types this client
 * does not know are skipped, so new ones do not break it. After the one terminal event, nothing
 * more is emitted. [clock] reads a `retry-after` given as a date.
 *
 * A stream that ends without `message_stop` ends with [AiErrorKind.INCOMPLETE] and how it broke off
 * (`brokeOff`), unless `message_delta` had given the stop reason: it comes after the last of the
 * content, so the reply is whole, and only the end of the stream was lost (#665), as
 * `ContentStream` takes a finish reason read for the end.
 *
 * A turn that ends in tool calls (#801, stop reason `tool_use`) ends with [CompletionEvent.ToolUse]
 * just before its [CompletionEvent.Done]: each `tool_use` block's id, name and input (its
 * `input_json_delta`s joined), and the turn's content blocks as they must be sent back
 * ([ContentBlock.replay]), its reasoning (`thinking` with its signature, `redacted_thinking`) among
 * them, as the API requires of a turn that used tools while thinking.
 */
internal class MessageStream(
    private val clock: Clock,
) {
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var stopReason: StopReason? = null
    private var started = false
    private var ended = false
    private val blocks = sortedMapOf<Int, ContentBlock>()

    /** Set when an event's data was not JSON this client can read; the stream should be stopped. */
    var malformed: Boolean = false
        private set

    /** The events that [data] makes, if any: two for the end of a turn that calls tools. */
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

            event.type == "message_stop" -> {
                finished(stopReason ?: StopReason.OTHER)
            }

            else -> {
                listOfNotNull(handle(event))
            }
        }
    }

    // The end of a turn stopped for [reason]: its tool calls first, when it called any.
    private fun finished(reason: StopReason): List<CompletionEvent> {
        val calls = blocks.values.mapNotNull { it.call() }
        val tools =
            if (reason == StopReason.TOOL_USE && calls.isNotEmpty()) {
                val replay = JsonArray(blocks.values.mapNotNull { it.replay() }).toString()
                CompletionEvent.ToolUse(ToolTurn(calls, replay))
            } else {
                null
            }
        return listOfNotNull(tools, end(CompletionEvent.Done(usage(), reason)))
    }

    private fun handle(event: WireEvent): CompletionEvent? =
        when (event.type) {
            "message_start" -> {
                started = true
                event.message?.usage?.let(::count)
                null
            }

            "content_block_start" -> {
                val index = event.index
                val block = event.contentBlock
                if (index != null && block != null) blocks[index] = ContentBlock(block)
                null
            }

            "content_block_delta" -> {
                val delta = event.delta
                event.index?.let { blocks[it] }?.add(delta)
                delta?.takeIf { it.type == "text_delta" }?.text?.takeIf { it.isNotEmpty() }?.let {
                    CompletionEvent.Delta(it)
                }
            }

            "message_delta" -> {
                event.delta?.stopReason?.let { stopReason = stopReasonOf(it) }
                event.usage?.let(::count)
                null
            }

            "error" -> {
                end(CompletionEvent.Error(event.error?.let(::kindOf) ?: AiErrorKind.SERVER))
            }

            else -> {
                null
            }
        }

    /**
     * The terminal events after the transport finished with [result] (null when the stream was
     * stopped here), or none if they were emitted already.
     */
    fun end(result: NetResult<NetResponse>?): List<CompletionEvent> {
        val reason = stopReason
        return when {
            ended -> emptyList()
            reason != null -> finished(reason)
            result is NetResult.Failure -> listOf(end(failure(result.error)))
            result is NetResult.Success && !result.value.isSuccess -> listOf(end(errorOf(result.value, clock.now())))
            else -> listOf(end(brokeOff(null, usage())))
        }
    }

    // A connection lost or cut short after the message began leaves an incomplete reply.
    private fun failure(error: NetError): CompletionEvent.Error =
        when {
            error is NetError.RateLimited -> CompletionEvent.Error(AiErrorKind.RATE_LIMITED, error.retryAfter)
            started && (error is NetError.Offline || error is NetError.TooLarge) -> brokeOff(error, usage())
            else -> CompletionEvent.Error(kindOf(error))
        }

    private fun usage() = Usage(inputTokens, outputTokens)

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
            "tool_use" -> StopReason.TOOL_USE
            else -> StopReason.OTHER
        }
}

/**
 * One content block of a turn (#801), from its `content_block_start` ([start]) and its deltas: a
 * text's pieces, a tool call's input as JSON in pieces, a reasoning's text and signature.
 */
private class ContentBlock(
    private val start: JsonObject,
) {
    private val type = (start["type"] as? JsonPrimitive)?.content
    private val text = StringBuilder()
    private val input = StringBuilder()
    private val thinking = StringBuilder()
    private var signature: String? = null

    fun add(delta: WireDelta?) {
        when (delta?.type) {
            "text_delta" -> delta.text?.let(text::append)
            "input_json_delta" -> delta.partialJson?.let(input::append)
            "thinking_delta" -> delta.thinking?.let(thinking::append)
            "signature_delta" -> signature = delta.signature
        }
    }

    /** The tool call this block is, if it is one: its id, name and input. */
    fun call(): ToolCall? {
        val id = startText("id").takeIf { type == "tool_use" && it.isNotEmpty() }
        val name = startText("name").takeIf { it.isNotEmpty() }
        return if (id != null && name != null) ToolCall(id, name, inputJson().toString()) else null
    }

    /**
     * The block as the next request sends it back, in the assistant's turn: a text with its text
     * (none when empty, which the API refuses), a tool call with its input, a reasoning with its text
     * and signature; any other block (redacted reasoning) as it began.
     */
    fun replay(): JsonObject? =
        when (type) {
            "text" -> if (text.isEmpty()) null else JsonObject(start + ("text" to JsonPrimitive(text.toString())))
            "tool_use" -> JsonObject(start + ("input" to inputJson()))
            "thinking" ->
                JsonObject(
                    start +
                        ("thinking" to JsonPrimitive(startText("thinking") + thinking)) +
                        ("signature" to JsonPrimitive(signature ?: startText("signature"))),
                )
            else -> start
        }

    private fun startText(key: String): String =
        (start[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    // The input as an object: what the deltas joined make, or none.
    private fun inputJson(): JsonObject =
        input.toString().takeIf { it.isNotBlank() }?.let { decode<JsonObject>(it) } ?: JsonObject(emptyMap())
}
