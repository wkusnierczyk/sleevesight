package app.sleevesight.ai.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/*
 * The parts of OpenRouter's chat completions, models and key-exchange APIs this client reads (#1157).
 * Everything else in a response is ignored, so new fields do not break it.
 */

/** One streamed chunk: its first choice, the usage (in the last chunk), or an error that ended it. */
@Serializable
internal data class WireChunk(
    val choices: List<WireChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
internal data class WireChoice(
    val delta: WireDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** A chunk's text ([content]) and the model's reasoning, which is not shown but billed ([reasoning]). */
@Serializable
internal data class WireDelta(
    val content: String? = null,
    val reasoning: String? = null,
)

/** Tokens billed: the completion includes the reasoning. */
@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
)

/**
 * An error, in an error response's body or a chunk: its [code], the HTTP status it stands for (a
 * number, or a string from a provider), its [message], read only to tell a free model's daily
 * limit, a budget spent and an unknown model apart, never logged, and its [metadata].
 */
@Serializable
internal data class WireError(
    val code: JsonPrimitive? = null,
    val message: String? = null,
    val metadata: WireErrorMetadata? = null,
)

/** What a budget's refusal adds: which budget was spent (`limit_source`). */
@Serializable
internal data class WireErrorMetadata(
    @SerialName("limit_source") val limitSource: String? = null,
)

@Serializable
internal data class WireErrorBody(
    val error: WireError? = null,
)

/** The model list (`GET /api/v1/models`). */
@Serializable
internal data class WireModelList(
    val data: List<WireModel> = emptyList(),
)

@Serializable
internal data class WireModel(
    val id: String,
    val name: String? = null,
    val created: Long? = null,
    val architecture: WireArchitecture? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
)

@Serializable
internal data class WireArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("output_modalities") val outputModalities: List<String>? = null,
)

/** The key-exchange answer (`POST /api/v1/auth/keys`): the key. */
@Serializable
internal data class WireKey(
    val key: String? = null,
)
