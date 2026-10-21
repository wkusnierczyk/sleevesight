package app.sleevesight.ai.openai

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
import app.sleevesight.net.HostPolicy
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * OpenAI's Responses API, as a small client over `:net` (D3): one streaming call, `POST
 * /v1/responses` with `stream: true`, read as server-sent events; and the model list, `GET
 * /v1/models`. The key goes in `Authorization: Bearer`, through `NetSecrets`, so it is never logged.
 *
 * The prompt is the request's `input`, as one user message. `store` is `false`: the response is
 * not kept on OpenAI's side for later retrieval, which a one-shot card never does (by default the
 * API keeps it for at least 30 days).
 *
 * [CompletionRequest.effort] becomes `reasoning.effort` (`low`, `medium`, `high`) for a model that
 * takes those levels ([takesEffort]); without it, or for any other model, the model's default
 * applies.
 *
 * The stream's text deltas become [CompletionEvent.Delta]s; reasoning, which the API returns
 * without its text, is skipped. `response.completed` and `response.incomplete` end it with
 * [CompletionEvent.Done], carrying the usage and the stop reason; `response.failed`, an `error`
 * event, an error status or a stream that breaks off end it with [CompletionEvent.Error]. The
 * request is a `POST`, so `:net` never repeats it once it may have been acted on: a paid call is
 * never sent twice.
 *
 * OpenAI answers 429 both for too many requests and for an account out of credit, and only the
 * body says which; so its host's policy ([HOST_POLICY]) has `:net` pass a 429 on, unretried, and
 * the second becomes [AiErrorKind.BILLING].
 *
 * @param net the process's one `NetClient`, configured with [HOST_POLICY] under [HOST].
 * @param baseUrl the API's origin, for tests.
 */
class OpenAiProvider(
    private val net: NetClient,
    private val baseUrl: String = BASE_URL,
) : AiProvider {
    override val id: ProviderId = ProviderId.OPENAI

    override suspend fun models(key: Secret): ModelList {
        val request = NetRequest("$baseUrl/v1/models", secrets = secrets(key))
        return when (val result = net.send(request)) {
            is NetResult.Failure -> {
                ModelList.Failed(kindOf(result.error))
            }

            is NetResult.Success -> {
                val response = result.value
                val list = if (response.isSuccess) decode<WireModelList>(response.body) else null
                when {
                    !response.isSuccess -> ModelList.Failed(kindOf(response))
                    list == null -> ModelList.Failed(AiErrorKind.UNEXPECTED_RESPONSE)
                    else -> ModelList.Listed(textModels(list.data))
                }
            }
        }
    }

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        channelFlow {
            val reader = ResponseStream()
            val events = ServerSentEvents()

            // A stream stopped here ends as the reader decided; nothing of the exception is needed.
            @Suppress("SwallowedException")
            val result =
                try {
                    net.stream(responsesRequest(request, key)) { line ->
                        events.line(line)?.let { event -> reader.read(event.data)?.let { send(it) } }
                        if (reader.malformed) throw StopStream()
                    }
                } catch (e: StopStream) {
                    null
                }
            reader.end(result)?.let { send(it) }
        }

    private fun responsesRequest(
        request: CompletionRequest,
        key: Secret,
    ): NetRequest {
        val body =
            buildJsonObject {
                put("model", request.model)
                put("input", request.prompt)
                put("max_output_tokens", request.maxTokens)
                put("stream", true)
                put("store", false)
                request.effort?.takeIf { takesEffort(request.model) }?.let { effort ->
                    putJsonObject("reasoning") { put("effort", effortName(effort)) }
                }
            }
        return NetRequest(
            url = "$baseUrl/v1/responses",
            method = NetMethod.POST,
            headers = mapOf("accept" to "text/event-stream"),
            secrets = secrets(key),
            body = NetBody(body.toString(), "application/json"),
            timeout = STREAM_TIMEOUT,
        )
    }

    /** Thrown from the line handler to end a stream whose events cannot be read. */
    private class StopStream : RuntimeException()

    companion object {
        const val HOST: String = "api.openai.com"

        const val BASE_URL: String = "https://$HOST"

        /**
         * OpenAI's host policy, which `:wiring`'s list of host policies must hold under [HOST]: no
         * interval of its own (OpenAI's limits are per account and tier, and it answers 429 when
         * one is reached), and its 429 passed on, so the body can say whether it is a rate limit
         * or an account out of credit ([HostPolicy.passOn429]).
         */
        val HOST_POLICY: HostPolicy = HostPolicy(minInterval = Duration.ZERO, passOn429 = true)

        /**
         * The model families that take `reasoning.effort` at `low`, `medium` and `high`, by id
         * prefix, from OpenAI's model pages on 2026-10-21: GPT-6 (Astra, Sol, Luna), GPT-6.1,
         * GPT-5.6, 5.5, 5.4, 5.2, 5.1 and 5, GPT-5.3-Codex, o4-mini, o3 and o1. The ones excluded
         * by [NO_EFFORT_PARTS] take fewer levels or none: the Pro models only `medium` and up (GPT-5
         * Pro only `high`), the chat and search models none. Models without reasoning (GPT-4.1,
         * GPT-4o) answer `reasoning` with an error, so an unknown model gets the request without
         * it. The model list carries no capabilities to ask instead ([ModelInfo]). A new family is
         * added here when it is released.
         */
        val EFFORT_MODELS: List<String> =
            listOf(
                "gpt-6",
                "gpt-6.1",
                "gpt-5.6",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.3-codex",
                "gpt-5.2",
                "gpt-5.1",
                "gpt-5",
                "o4-mini",
                "o3",
                "o1",
            )

        /** The parts of a model id (between hyphens) that mark one not taking low, medium and high. */
        private val NO_EFFORT_PARTS = setOf("pro", "chat", "search")

        /** Whether [model] takes `reasoning.effort` at `low`, `medium` and `high`. */
        fun takesEffort(model: String): Boolean =
            EFFORT_MODELS.any { model == it || model.startsWith("$it-") } &&
                model.split('-').none { it in NO_EFFORT_PARTS }

        /** [effort] as `reasoning.effort` names it. */
        fun effortName(effort: ReasoningEffort): String =
            when (effort) {
                ReasoningEffort.LOW -> "low"
                ReasoningEffort.MEDIUM -> "medium"
                ReasoningEffort.HIGH -> "high"
            }

        /**
         * The parts of a model id that mark one this client cannot use: models for audio, speech,
         * images, embeddings or moderation, and those that need tools (deep research) or the
         * legacy completions endpoint (instruct).
         */
        private val NOT_TEXT_PARTS =
            setOf(
                "audio",
                "realtime",
                "transcribe",
                "tts",
                "live",
                "translate",
                "whisper",
                "image",
                "embedding",
                "moderation",
                "search",
                "deep",
                "instruct",
            )

        /**
         * Whether [id] is a text model the Responses API answers with text: a GPT or o-series model,
         * or `chat-latest` (fine-tuned ones, `ft:<model>:...`, included), and none of
         * [NOT_TEXT_PARTS]. The model list holds every model the key may use, embeddings and speech
         * models among them.
         */
        fun isTextModel(id: String): Boolean {
            val base = id.removePrefix("ft:").substringBefore(':')
            val family = FAMILIES.any { base.startsWith(it) } || base == "chat-latest" || O_SERIES.matches(base)
            return family && base.split('-').none { it in NOT_TEXT_PARTS }
        }

        private val FAMILIES = listOf("gpt-", "chatgpt-")

        private val O_SERIES = Regex("o[0-9].*")

        /**
         * A whole stream, reasoning included, may take this long; `:net`'s socket timeout still
         * bounds each silence.
         */
        val STREAM_TIMEOUT: Duration = 5.minutes

        private fun secrets(key: Secret) = NetSecrets(headers = mapOf("authorization" to "Bearer ${key.reveal()}"))

        /** The text models of the list, newest first: the list itself is in no stated order. */
        private fun textModels(models: List<WireModel>): List<ModelInfo> =
            models
                .filter { isTextModel(it.id) }
                .map { ModelInfo(it.id, it.id, it.created?.let(Instant::fromEpochSeconds)) }
                .sortedWith(compareByDescending<ModelInfo> { it.createdAt }.thenBy { it.id })
    }
}

/** The kind of a request that got no usable answer from `:net`. */
internal fun kindOf(error: NetError): AiErrorKind =
    when (error) {
        is NetError.Offline -> AiErrorKind.OFFLINE
        is NetError.RateLimited -> AiErrorKind.RATE_LIMITED
        is NetError.Unauthorized -> AiErrorKind.UNAUTHORIZED
        is NetError.Server -> if (error.status == UNAVAILABLE) AiErrorKind.OVERLOADED else AiErrorKind.SERVER
        is NetError.InvalidRequest -> AiErrorKind.INVALID_REQUEST
        is NetError.Parse, is NetError.Protocol, is NetError.TooLarge -> AiErrorKind.UNEXPECTED_RESPONSE
    }

/**
 * The kind of an error response that `:net` passes on as a response (400, 404, 429 and the like):
 * by the body's `error.code`, else its `error.type`, else the status.
 */
internal fun kindOf(response: NetResponse): AiErrorKind {
    val error = decode<WireErrorBody>(response.body)?.error
    return error?.code?.let(::kindOfCode)
        ?: error?.type?.let(::kindOfType)
        ?: when (response.status) {
            BAD_REQUEST -> AiErrorKind.INVALID_REQUEST
            PAYMENT_REQUIRED -> AiErrorKind.BILLING
            NOT_FOUND -> AiErrorKind.MODEL_NOT_FOUND
            CONTENT_TOO_LARGE -> AiErrorKind.REQUEST_TOO_LARGE
            TOO_MANY_REQUESTS -> AiErrorKind.RATE_LIMITED
            else -> AiErrorKind.UNEXPECTED_RESPONSE
        }
}

/** [response]'s error, with the wait a rate limit asks for in `Retry-After`, when in seconds. */
internal fun errorOf(response: NetResponse): CompletionEvent.Error {
    val kind = kindOf(response)
    val wait =
        response
            .header("retry-after")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { kind == AiErrorKind.RATE_LIMITED && it >= 0 }
            ?.seconds
    return CompletionEvent.Error(kind, wait)
}

/**
 * The kind of an error OpenAI names by its `code` (in an error body, an `error` event or a failed
 * response), or null for a code this client does not know. The codes for an account out of credit
 * or over a spending or usage limit come with a 429, and are [AiErrorKind.BILLING]: no retry mends
 * them.
 */
internal fun kindOfCode(code: String): AiErrorKind? =
    when (code) {
        "insufficient_quota",
        "credit_balance_exhausted",
        "organization_spend_limit_exceeded",
        "project_spend_limit_exceeded",
        "organization_usage_limit_exceeded",
        -> AiErrorKind.BILLING

        "rate_limit_exceeded", "slow_down" -> AiErrorKind.RATE_LIMITED
        "server_is_overloaded" -> AiErrorKind.OVERLOADED
        "server_error" -> AiErrorKind.SERVER
        "model_not_found" -> AiErrorKind.MODEL_NOT_FOUND
        "context_length_exceeded" -> AiErrorKind.REQUEST_TOO_LARGE
        "invalid_api_key" -> AiErrorKind.UNAUTHORIZED
        "invalid_prompt" -> AiErrorKind.INVALID_REQUEST
        else -> null
    }

/** The kind of an error OpenAI names by its `type`, or null for a type this client does not know. */
internal fun kindOfType(type: String): AiErrorKind? =
    when (type) {
        "invalid_request_error" -> AiErrorKind.INVALID_REQUEST
        "authentication_error", "permission_error" -> AiErrorKind.UNAUTHORIZED
        "insufficient_quota" -> AiErrorKind.BILLING
        "rate_limit_error" -> AiErrorKind.RATE_LIMITED
        "server_error" -> AiErrorKind.SERVER
        "service_unavailable_error" -> AiErrorKind.OVERLOADED
        else -> null
    }

private const val BAD_REQUEST = 400
private const val PAYMENT_REQUIRED = 402
private const val NOT_FOUND = 404
private const val CONTENT_TOO_LARGE = 413
private const val TOO_MANY_REQUESTS = 429
private const val UNAVAILABLE = 503
