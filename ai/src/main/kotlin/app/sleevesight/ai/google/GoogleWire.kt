package app.sleevesight.ai.google

import kotlinx.serialization.Serializable

/*
 * The parts of the Gemini API's `GenerateContentResponse`, `ListModelsResponse` and error body this
 * client reads (https://ai.google.dev/api/generate-content, https://ai.google.dev/api/models,
 * https://ai.google.dev/gemini-api/docs/generate-content/api-errors). Everything else in a response
 * is ignored, so new fields do not break it. The API's JSON names are camelCase, as here.
 */

/** One chunk of a `streamGenerateContent` stream, or an `error` sent in its place. */
@Serializable
internal data class WireResponse(
    val candidates: List<WireCandidate> = emptyList(),
    val promptFeedback: WirePromptFeedback? = null,
    val usageMetadata: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
internal data class WireCandidate(
    val content: WireContent? = null,
    val finishReason: String? = null,
)

@Serializable
internal data class WireContent(
    val parts: List<WirePart> = emptyList(),
)

/** A part of the reply: text, or the model's reasoning (`thought`), which is not shown. */
@Serializable
internal data class WirePart(
    val text: String? = null,
    val thought: Boolean = false,
)

@Serializable
internal data class WirePromptFeedback(
    val blockReason: String? = null,
)

@Serializable
internal data class WireUsage(
    val promptTokenCount: Long? = null,
    val toolUsePromptTokenCount: Long? = null,
    val candidatesTokenCount: Long? = null,
    val thoughtsTokenCount: Long? = null,
)

/** The body of an error response: `{"error": {"code", "message", "status", "details"}}`. */
@Serializable
internal data class WireErrorBody(
    val error: WireError? = null,
)

@Serializable
internal data class WireError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val details: List<WireErrorDetail> = emptyList(),
)

/** One of an error's details; an `ErrorInfo` has a [reason], such as `API_KEY_INVALID`. */
@Serializable
internal data class WireErrorDetail(
    val reason: String? = null,
)

@Serializable
internal data class WireModelPage(
    val models: List<WireModel> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
internal data class WireModel(
    val name: String,
    val displayName: String? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
)
