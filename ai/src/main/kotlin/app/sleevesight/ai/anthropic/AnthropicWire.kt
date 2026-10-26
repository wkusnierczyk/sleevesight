package app.sleevesight.ai.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

@Serializable
internal data class WireMessage(
    val usage: WireUsage? = null,
)

/** A `content_block_delta`'s delta (`text_delta` and others) or a `message_delta`'s (`stop_reason`). */
@Serializable
internal data class WireDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
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
