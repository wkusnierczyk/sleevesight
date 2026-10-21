package app.sleevesight.ai.anthropic

import app.sleevesight.ai.ServerSentEvents
import app.sleevesight.ai.decode
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.net.NetBody
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Anthropic's Messages API, as a small client over `:net` (D3): one streaming call, `POST
 * /v1/messages` with `stream: true`, read as server-sent events; and the model list, `GET
 * /v1/models`. The key goes in `x-api-key`, through `NetSecrets`, so it is never logged.
 *
 * [CompletionRequest.effort] becomes `output_config.effort` (`low`, `medium`, `high`; [effortName])
 * for a model that takes it ([takesEffort]); without it, or for any other model, the model's default
 * applies.
 * Thinking is left at the API's default, which current models reason with: it cannot be switched
 * off on all of them, and the effort is what bounds it.
 *
 * The stream's text deltas become [CompletionEvent.Delta]s; other content (a model's reasoning,
 * which current models do by default and return without its text) is skipped. `message_stop`
 * ends it with [CompletionEvent.Done], carrying the usage and the stop reason; an `error` event, an
 * error status or a stream that breaks off ends it with [CompletionEvent.Error]. The request is a
 * `POST`, so `:net` never repeats it once it may have been acted on: a paid call is never sent twice.
 *
 * @param net the process's one `NetClient`.
 * @param baseUrl the API's origin, for tests.
 */
class AnthropicProvider(
    private val net: NetClient,
    private val baseUrl: String = BASE_URL,
) : AiProvider {
    override val id: ProviderId = ProviderId.ANTHROPIC

    override suspend fun models(key: Secret): ModelList {
        val models = mutableListOf<ModelInfo>()
        var after: String? = null
        var failure: AiErrorKind? = null
        var pages = 0
        do {
            when (val page = modelPage(key, after)) {
                is Page.Failed -> {
                    failure = page.kind
                }

                is Page.Read -> {
                    models += page.models
                    after = page.next
                }
            }
            pages++
        } while (failure == null && after != null && pages < MAX_MODEL_PAGES)
        return failure?.let { ModelList.Failed(it) } ?: ModelList.Listed(models)
    }

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        channelFlow {
            val reader = MessageStream()
            val events = ServerSentEvents()

            // A stream stopped here ends as the reader decided; nothing of the exception is needed.
            @Suppress("SwallowedException")
            val result =
                try {
                    net.stream(messagesRequest(request, key)) { line ->
                        events.line(line)?.let { event -> reader.read(event.data)?.let { send(it) } }
                        if (reader.malformed) throw StopStream()
                    }
                } catch (e: StopStream) {
                    null
                }
            reader.end(result)?.let { send(it) }
        }

    private sealed interface Page {
        data class Read(
            val models: List<ModelInfo>,
            val next: String?,
        ) : Page

        data class Failed(
            val kind: AiErrorKind,
        ) : Page
    }

    private suspend fun modelPage(
        key: Secret,
        after: String?,
    ): Page {
        val query = mapOf("limit" to MODEL_PAGE_SIZE.toString()) + listOfNotNull(after?.let { "after_id" to it })
        val request = NetRequest("$baseUrl/v1/models", query = query, headers = HEADERS, secrets = secrets(key))
        return when (val result = net.send(request)) {
            is NetResult.Failure -> {
                Page.Failed(kindOf(result.error))
            }

            is NetResult.Success -> {
                val response = result.value
                val page = if (response.isSuccess) decode<WireModelPage>(response.body) else null
                when {
                    !response.isSuccess -> Page.Failed(kindOf(response))
                    page == null -> Page.Failed(AiErrorKind.UNEXPECTED_RESPONSE)
                    else -> Page.Read(page.data.map(::modelInfo), page.lastId.takeIf { page.hasMore })
                }
            }
        }
    }

    private fun messagesRequest(
        request: CompletionRequest,
        key: Secret,
    ): NetRequest {
        val body =
            buildJsonObject {
                put("model", request.model)
                put("max_tokens", request.maxTokens)
                put("stream", true)
                request.effort?.takeIf { takesEffort(request.model) }?.let { effort ->
                    putJsonObject("output_config") { put("effort", effortName(effort)) }
                }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", request.prompt)
                    }
                }
            }
        return NetRequest(
            url = "$baseUrl/v1/messages",
            method = NetMethod.POST,
            headers = HEADERS + ("accept" to "text/event-stream"),
            secrets = secrets(key),
            body = NetBody(body.toString(), "application/json"),
            timeout = STREAM_TIMEOUT,
        )
    }

    /** Thrown from the line handler to end a stream whose events cannot be read. */
    private class StopStream : RuntimeException()

    companion object {
        /**
         * The model families that take `output_config.effort` at `low`, `medium` and `high`, by id
         * prefix, from Anthropic's API reference on 2026-10-14: Claude Opus 4.5 and later, Sonnet 4.6
         * and later, Haiku 5.5 and later, Fable and Mythos 5. Older models still listed (Claude Haiku
         * 4.5, Sonnet 4.5) answer it with an error, so an unknown model gets the request without it.
         * The model list carries no capabilities to ask instead ([ModelInfo]). A new family is added
         * here when it is released.
         */
        val EFFORT_MODELS: List<String> =
            listOf(
                "claude-opus-4-5",
                "claude-opus-4-6",
                "claude-opus-4-7",
                "claude-opus-4-8",
                "claude-opus-5",
                "claude-sonnet-4-6",
                "claude-sonnet-5",
                "claude-haiku-5",
                "claude-fable-5",
                "claude-mythos-5",
            )

        /** Whether [model] takes `output_config.effort`. */
        fun takesEffort(model: String): Boolean = EFFORT_MODELS.any { model == it || model.startsWith("$it-") }

        /**
         * [effort] as `output_config.effort` names it. Anthropic's scale has no `minimal`, so a
         * request with it (never one from the settings, which offer only Anthropic's own levels)
         * is sent as `low`, the least it takes.
         */
        fun effortName(effort: ReasoningEffort): String =
            when (effort) {
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
                ReasoningEffort.MEDIUM -> "medium"
                ReasoningEffort.HIGH -> "high"
            }

        const val BASE_URL: String = "https://api.anthropic.com"

        /** The API version this client speaks, sent as `anthropic-version`. */
        const val API_VERSION: String = "2023-06-01"

        /**
         * A whole stream, reasoning included, may take this long; `:net`'s socket timeout still
         * bounds each silence.
         */
        val STREAM_TIMEOUT: Duration = 5.minutes

        private const val MODEL_PAGE_SIZE = 100

        /** A bound on pagination, against a server that always says there is more. */
        private const val MAX_MODEL_PAGES = 10

        private val HEADERS = mapOf("anthropic-version" to API_VERSION)

        private fun secrets(key: Secret) = NetSecrets(headers = mapOf("x-api-key" to key.reveal()))

        private fun modelInfo(model: WireModel) =
            ModelInfo(
                id = model.id,
                displayName = model.displayName ?: model.id,
                createdAt = model.createdAt?.let { Instant.parseOrNull(it) },
            )
    }
}

/** The kind of a request that got no usable answer from `:net`. */
internal fun kindOf(error: NetError): AiErrorKind =
    when (error) {
        is NetError.Offline -> AiErrorKind.OFFLINE
        is NetError.RateLimited -> AiErrorKind.RATE_LIMITED
        is NetError.Unauthorized -> AiErrorKind.UNAUTHORIZED
        is NetError.Server -> if (error.status == OVERLOADED_STATUS) AiErrorKind.OVERLOADED else AiErrorKind.SERVER
        is NetError.InvalidRequest -> AiErrorKind.INVALID_REQUEST
        is NetError.Parse, is NetError.Protocol, is NetError.TooLarge -> AiErrorKind.UNEXPECTED_RESPONSE
    }

/** The kind of an error response that `:net` passes on as a response (400, 402, 404, 413 and the like). */
internal fun kindOf(response: NetResponse): AiErrorKind =
    decode<WireErrorBody>(response.body)?.error?.type?.let(::kindOfType)
        ?: when (response.status) {
            BAD_REQUEST -> AiErrorKind.INVALID_REQUEST
            PAYMENT_REQUIRED -> AiErrorKind.BILLING
            NOT_FOUND -> AiErrorKind.MODEL_NOT_FOUND
            CONTENT_TOO_LARGE -> AiErrorKind.REQUEST_TOO_LARGE
            else -> AiErrorKind.UNEXPECTED_RESPONSE
        }

/** The kind of an error Anthropic names by its `error.type`, or null for a type this client does not know. */
internal fun kindOfType(type: String): AiErrorKind? =
    when (type) {
        "invalid_request_error" -> AiErrorKind.INVALID_REQUEST
        "authentication_error", "permission_error" -> AiErrorKind.UNAUTHORIZED
        "billing_error" -> AiErrorKind.BILLING
        "not_found_error" -> AiErrorKind.MODEL_NOT_FOUND
        "request_too_large" -> AiErrorKind.REQUEST_TOO_LARGE
        "rate_limit_error" -> AiErrorKind.RATE_LIMITED
        "api_error" -> AiErrorKind.SERVER
        "overloaded_error" -> AiErrorKind.OVERLOADED
        else -> null
    }

private const val BAD_REQUEST = 400
private const val PAYMENT_REQUIRED = 402
private const val NOT_FOUND = 404
private const val CONTENT_TOO_LARGE = 413
private const val OVERLOADED_STATUS = 529
