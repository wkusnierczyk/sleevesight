package app.sleevesight.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The parts of OpenAI's Responses and Models APIs this client reads. Everything else in a response
 * is ignored, so new fields do not break it.
 */

/**
 * One streaming event: its `type`, a text or refusal `delta`, the `response` so far, or an error's
 * `code`; for `response.output_item.done`, the output [item] done (#801: a reasoning item, a tool call).
 */
@Serializable
internal data class WireEvent(
    val type: String,
    val delta: String? = null,
    val response: WireResponse? = null,
    val code: String? = null,
    val item: JsonObject? = null,
)

/** The response object, as the lifecycle events (`response.completed` and the like) carry it. */
@Serializable
internal data class WireResponse(
    val status: String? = null,
    val usage: WireUsage? = null,
    val error: WireError? = null,
    @SerialName("incomplete_details") val incompleteDetails: WireIncomplete? = null,
)

/** Tokens billed: the input includes cached tokens, the output the reasoning tokens. */
@Serializable
internal data class WireUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
)

@Serializable
internal data class WireIncomplete(
    val reason: String? = null,
)

/** The body of an error response, `{"error": {...}}`. */
@Serializable
internal data class WireErrorBody(
    val error: WireError? = null,
)

/** An error: its `type` and `code`, and in an error body the `param` at fault, if one. */
@Serializable
internal data class WireError(
    val type: String? = null,
    val code: String? = null,
    val param: String? = null,
)

@Serializable
internal data class WireModelList(
    val data: List<WireModel>,
)

@Serializable
internal data class WireModel(
    val id: String,
    val created: Long? = null,
    @SerialName("shutdown_date") val shutdownDate: String? = null,
)
