package app.sleevesight.ai.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The parts of Anthropic's Messages and Models APIs this client reads. Everything else in a
 * response is ignored, so new fields do not break it.
 */

@Serializable
internal data class WireEvent(
    val type: String,
    val message: WireMessage? = null,
    val delta: WireDelta? = null,
    val usage: WireUsage? = null,
    val error: WireError? = null,
    /** The content block a `content_block_*` event is about, by its place in the message. */
    val index: Int? = null,
    /** A `content_block_start`'s block as it begins (#801): its type, and a tool call's id and name. */
    @SerialName("content_block") val contentBlock: JsonObject? = null,
)

@Serializable
internal data class WireMessage(
    val usage: WireUsage? = null,
)

/**
 * A `content_block_delta`'s delta (`text_delta`; for a turn that calls a tool, #801, `input_json_delta`,
 * `thinking_delta` and `signature_delta`) or a `message_delta`'s (`stop_reason`).
 */
@Serializable
internal data class WireDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
)

@Serializable
internal data class WireUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
)

/** The body of an error response, and the payload of an `error` event. */
@Serializable
internal data class WireErrorBody(
    val error: WireError? = null,
)

@Serializable
internal data class WireError(
    val type: String? = null,
    val message: String? = null,
    val details: WireErrorDetails? = null,
)

/** An error's details: `error_code` tells the tier's spend cap reached from a rate limit. */
@Serializable
internal data class WireErrorDetails(
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
internal data class WireModelPage(
    val data: List<WireModel>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null,
)

@Serializable
internal data class WireModel(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** `active`, `deprecated` (answered for those using it, closed to new ones) or `retired`. */
    val lifecycle: String? = null,
)
