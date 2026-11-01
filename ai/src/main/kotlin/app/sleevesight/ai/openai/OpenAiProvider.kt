package app.sleevesight.ai.openai

import app.sleevesight.ai.ServerSentEvents
import app.sleevesight.ai.decode
import app.sleevesight.ai.maxTokensSent
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.ToolSpec
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
import app.sleevesight.net.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * OpenAI's Responses API, as a small client over `:net` (D3): one streaming call, `POST
 * /v1/responses` with `stream: true`, read as server-sent events; and the model list, `GET
 * /v1/models`. The key goes in `Authorization: Bearer`, through `NetSecrets`, so it is never logged.
 *
 * With [CompletionRequest.tools] (#801), they go in `tools` as functions; a response that calls one
 * ends in [CompletionEvent.ToolUse] (`ResponseStream`), and, for a model that reasons
 * ([takesEffort]), its reasoning comes encrypted (`include`), since nothing is stored; a model that
 * does not reason gets no `include`, which it would refuse, and a 400 naming `include` is sent once
 * more without it, as one naming the effort is. A reasoning item with no encrypted content is not
 * sent back. The next request's `input` is then a list: the prompt as
 * the user's message, and for each exchange the output items as they came and a
 * `function_call_output` for each call; with [CompletionRequest.toolsAllowed] false, `tool_choice`
 * `none`, so the model answers now.
 *
 * The prompt is the request's `input`, as one user message. `store` is `false`: the response is
 * not kept on OpenAI's side for later retrieval, which a one-shot card never does (by default the
 * API keeps it for at least 30 days).
 *
 * [CompletionRequest.effort] becomes `reasoning.effort` (`low`, `medium`, `high`) for a model that
 * takes those levels ([takesEffort]); without it, or for any other model, the model's default
 * applies. The list of models can be wrong for a new model under a known family's name: if OpenAI
 * refuses the request for its effort (a 400 naming `reasoning.effort` or `reasoning` as the
 * parameter at fault), it is sent once more without one, so the model's default applies.
 *
 * The stream's text deltas become [CompletionEvent.Delta]s; reasoning, which the API returns
 * without its text, is skipped. `response.completed` and `response.incomplete` end it with
 * [CompletionEvent.Done], carrying the usage and the stop reason; `response.failed`, an `error`
 * event, an error status or a stream that breaks off end it with [CompletionEvent.Error]. A prompt
 * declined for a usage policy ([POLICY_CODES]), whether in a 400 error response, an `error` event or
 * a `response.failed`, is no error: it ends as a refusal does, [CompletionEvent.Done] with
 * [StopReason.REFUSAL]. The request is a `POST`, so `:net`
 * never repeats it once it may have been acted on: a paid call is never sent twice.
 *
 * OpenAI answers 429 both for too many requests and for an account out of credit, and only the
 * body says which; so its host's policy ([HOST_POLICY]) has `:net` pass a 429 on, unretried, and
 * the client reads it. An account out of credit is [AiErrorKind.BILLING] at once, since no retry
 * mends it. A rate limit is sent once more after its `Retry-After` (or [RetryPolicy.delayAfter]'s
 * first wait without one), when that is within [RetryPolicy.maxDelay], as `:net` retries another
 * host's 429; a 429 was refused before it was acted on, so the call is not paid twice. Otherwise it
 * ends as [AiErrorKind.RATE_LIMITED] with the wait. Nothing holds back the next request after one:
 * each is one track's insight or one tap of *Write*, far apart against any rate limit an account
 * has, and the next track should not wait out a limit the last one met.
 *
 * @param net the process's one `NetClient`, configured with [HOST_POLICY] under [HOST].
 * @param retry the waits for a rate limit: [net]'s own `NetConfig.retry`, so both wait alike. It
 *   has no default, so no caller leaves it different by accident.
 * @param baseUrl the API's origin, for tests.
 * @param clock for a `Retry-After` given as a date, and the models' shutdown dates.
 */
class OpenAiProvider(
    private val net: NetClient,
    private val retry: RetryPolicy,
    private val baseUrl: String = BASE_URL,
    private val clock: Clock = Clock.System,
) : AiProvider {
    override val id: ProviderId = ProviderId.OPENAI

    override val usesTools: Boolean = true

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
                    else -> ModelList.Listed(textModels(list.data, clock.now()))
                }
            }
        }
    }

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        channelFlow {
            // Whether the model takes an effort, until it refuses one; the token limit follows it.
            var takesIt = takesEffort(request.model)
            var effort = request.effort?.takeIf { takesIt }
            var waited = false
            do {
                val reader = ResponseStream(clock)
                val events = ServerSentEvents()

                // A stream stopped here ends as the reader decided; nothing of the exception is needed.
                @Suppress("SwallowedException")
                val result =
                    try {
                        net.stream(
                            responsesRequest(request, effort, maxTokensSent(request, takesIt), takesIt, key),
                        ) { line ->
                            events.line(line)?.let { event -> reader.read(event.data).forEach { send(it) } }
                            if (reader.malformed) throw StopStream()
                        }
                    } catch (e: StopStream) {
                        null
                    }
                // An error status comes before any of the body, so nothing was emitted for it.
                val refused = (result as? NetResult.Success)?.value?.takeUnless { it.isSuccess }
                val wait = refused?.takeIf { !waited }?.let(::rateLimitWait)
                val again =
                    when {
                        refused == null -> {
                            false
                        }

                        effort != null && rejectsEffort(refused) -> {
                            effort = null
                            takesIt = false
                            true
                        }

                        wait != null -> {
                            delay(wait)
                            waited = true
                            true
                        }

                        else -> {
                            false
                        }
                    }
                if (!again) reader.end(result).forEach { send(it) }
            } while (again)
        }

    /** The wait before sending again after [response], a rate limit; null if it is not one, or too long. */
    private fun rateLimitWait(response: NetResponse): Duration? =
        if (response.status == TOO_MANY_REQUESTS && kindOf(response) == AiErrorKind.RATE_LIMITED) {
            (response.retryAfter(clock.now()) ?: retry.delayAfter(1)).takeIf { it <= retry.maxDelay }
        } else {
            null
        }

    private fun responsesRequest(
        request: CompletionRequest,
        effort: ReasoningEffort?,
        maxTokens: Int,
        reasons: Boolean,
        key: Secret,
    ): NetRequest {
        val body =
            buildJsonObject {
                put("model", request.model)
                if (request.exchanges.isEmpty()) put("input", request.prompt) else put("input", inputOf(request))
                put("max_output_tokens", maxTokens)
                put("stream", true)
                put("store", false)
                effort?.let { putJsonObject("reasoning") { put("effort", effortName(it)) } }
                if (request.tools.isNotEmpty()) {
                    putJsonArray("tools") { request.tools.forEach { addTool(it) } }
                    if (!request.toolsAllowed) put("tool_choice", "none")
                    // Nothing is stored (`store`), so a reasoning model's reasoning goes back with the
                    // calls, encrypted; a model that does not reason refuses the field (a 400).
                    if (reasons) putJsonArray("include") { add("reasoning.encrypted_content") }
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

    // [tool] as `tools` lists it: a function, with its name, description and parameters' schema.
    private fun JsonArrayBuilder.addTool(tool: ToolSpec) =
        addJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", decode<JsonObject>(tool.parameters) ?: JsonObject(emptyMap()))
        }

    // The input of a request after tool use (#801): the prompt as the user's message, then each
    // exchange's output items as they came and its calls' outputs.
    private fun inputOf(request: CompletionRequest): JsonArray =
        buildJsonArray {
            addJsonObject {
                put("role", "user")
                put("content", request.prompt)
            }
            request.exchanges.forEach { exchange ->
                // A reasoning item without its encrypted content cannot be sent back unstored.
                (decode<JsonArray>(exchange.turn.replay) ?: JsonArray(emptyList()))
                    .filterNot { it.isUnreplayableReasoning() }
                    .forEach { add(it) }
                exchange.results.forEach { result ->
                    addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.callId)
                        put("output", result.content)
                    }
                }
            }
        }

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

        /**
         * Whether [model] takes `reasoning.effort` at `low`, `medium` and `high`; a fine-tuned
         * model (`ft:<model>:...`) as its base model does.
         */
        fun takesEffort(model: String): Boolean {
            val base = baseOf(model)
            return inFamily(base, EFFORT_MODELS) && base.split('-').none { it in NO_EFFORT_PARTS }
        }

        /** A fine-tuned model's base model (`ft:<model>:<org>:...`), or [id] itself. */
        private fun baseOf(id: String): String = id.removePrefix("ft:").substringBefore(':')

        /** Whether [id] is one of [families], or a snapshot or variant of one (`<family>-...`). */
        private fun inFamily(
            id: String,
            families: List<String>,
        ): Boolean = families.any { id == it || id.startsWith("$it-") }

        /**
         * [effort] as `reasoning.effort` names it. OpenAI's scale has no `minimal` (only GPT-5
         * takes it), so a request with it (never one from the settings, which offer only OpenAI's
         * own levels) is sent as `low`, the least every current reasoning model takes.
         */
        fun effortName(effort: ReasoningEffort): String =
            when (effort) {
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
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
         * The text models whose pages, on 2026-10-21, do not list streaming from the Responses API,
         * which this client needs: o1-mini is not on the Responses API at all, and GPT-3.5 Turbo,
         * o1-pro, o3-pro and GPT-5.5 Pro list no streaming (the Pro models are meant for background
         * requests). GPT-4, GPT-4 Turbo and GPT-4o list both, and stay offered.
         */
        private val NO_STREAM_MODELS =
            listOf("gpt-3.5-turbo", "o1-mini", "o1-preview", "o1-pro", "o3-pro", "gpt-5.5-pro")

        /**
         * Whether [id] is a text model the Responses API streams text from: a GPT or o-series model,
         * or `chat-latest` (fine-tuned ones, `ft:<model>:...`, as their base model), none of
         * [NOT_TEXT_PARTS], and none of [NO_STREAM_MODELS]. The model list holds every model the key
         * may use, embeddings and speech models among them.
         */
        fun isTextModel(id: String): Boolean {
            val base = baseOf(id)
            val family = FAMILIES.any { base.startsWith(it) } || base == "chat-latest" || O_SERIES.matches(base)
            return family && base.split('-').none { it in NOT_TEXT_PARTS } && !inFamily(base, NO_STREAM_MODELS)
        }

        private val FAMILIES = listOf("gpt-", "chatgpt-")

        private val O_SERIES = Regex("o[0-9].*")

        /**
         * A whole stream, reasoning included, may take this long; `:net`'s socket timeout still
         * bounds each silence.
         */
        val STREAM_TIMEOUT: Duration = 5.minutes

        private fun secrets(key: Secret) = NetSecrets(headers = mapOf("authorization" to "Bearer ${key.reveal()}"))

        /**
         * The text models of the list: none whose shutdown date has passed by [now], those with
         * none announced first, and newest first within each (the list itself is in no stated
         * order). A shutdown date is a calendar date (`2026-10-23`), compared as written. The list
         * names no model, so each is named from [ModelNames], else by its id.
         */
        private fun textModels(
            models: List<WireModel>,
            now: Instant,
        ): List<ModelInfo> {
            val today = now.toString().take(ISO_DATE_LENGTH)
            return models
                .filter { isTextModel(it.id) }
                .filterNot { model -> model.shutdownDate?.take(ISO_DATE_LENGTH)?.let { it < today } == true }
                .sortedWith(
                    compareBy<WireModel> { it.shutdownDate != null }
                        .thenByDescending { it.created }
                        .thenBy { it.id },
                ).map { ModelInfo(it.id, ModelNames.of(it.id) ?: it.id, it.created?.let(Instant::fromEpochSeconds)) }
        }

        private const val ISO_DATE_LENGTH = 10
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
 * by the body's `error.code`, else its `error.type`, else the status. `invalid_request_error`, the
 * type of most of them, says less than a 404 or 413 does, so for it the status decides.
 *
 * A 400 naming `stream` as the parameter at fault ([STREAM_PARAM]) is [AiErrorKind.MODEL_REFUSED]:
 * the model is listed for the key, but the account may not stream it, which this client needs.
 */
internal fun kindOf(response: NetResponse): AiErrorKind {
    val error = decode<WireErrorBody>(response.body)?.error
    if (response.status == BAD_REQUEST && error?.param == STREAM_PARAM) return AiErrorKind.MODEL_REFUSED
    return error?.code?.let(::kindOfCode)
        ?: error?.type?.takeIf { it != INVALID_REQUEST_TYPE }?.let(::kindOfType)
        ?: when (response.status) {
            BAD_REQUEST -> AiErrorKind.INVALID_REQUEST
            PAYMENT_REQUIRED -> AiErrorKind.BILLING
            NOT_FOUND -> AiErrorKind.MODEL_NOT_FOUND
            CONTENT_TOO_LARGE -> AiErrorKind.REQUEST_TOO_LARGE
            TOO_MANY_REQUESTS -> AiErrorKind.RATE_LIMITED
            else -> AiErrorKind.UNEXPECTED_RESPONSE
        }
}

/** [response]'s error, with the wait a rate limit asks for in `Retry-After`, from [now]. */
internal fun errorOf(
    response: NetResponse,
    now: Instant,
): CompletionEvent.Error {
    val kind = kindOf(response)
    return CompletionEvent.Error(kind, response.retryAfter(now).takeIf { kind == AiErrorKind.RATE_LIMITED })
}

/**
 * The codes of a prompt declined for a usage policy, from the Responses reference on 2026-10-21, in
 * an error response, an `error` event or a failed response: the model, or OpenAI for it, declined,
 * as a refusal does, so the reply ends as one.
 */
internal val POLICY_CODES: Set<String> = setOf("bio_policy", "misalignment_policy_violation", "invalid_prompt")

/** Whether [response] declines the prompt for a usage policy. */
internal fun declinesPrompt(response: NetResponse): Boolean =
    response.status == BAD_REQUEST && decode<WireErrorBody>(response.body)?.error?.code in POLICY_CODES

/** Whether [response] refuses the request for its reasoning effort. */
internal fun rejectsEffort(response: NetResponse): Boolean =
    response.status == BAD_REQUEST &&
        decode<WireErrorBody>(response.body)?.error?.param in EFFORT_PARAMS

private val EFFORT_PARAMS = setOf("reasoning.effort", "reasoning", "include")

// Whether this output item is reasoning with no encrypted content, which a request with nothing
// stored cannot refer to (#801).
private fun JsonElement.isUnreplayableReasoning(): Boolean {
    val item = this as? JsonObject ?: return false
    val type = (item["type"] as? JsonPrimitive)?.content
    return type == "reasoning" && item["encrypted_content"] !is JsonPrimitive
}

/**
 * The parameter a 400 names when the account may not stream the model: "Your organization must be
 * verified to stream this model" (`unsupported_value`), as developers have reported it for o3 and
 * the GPT-5 models from 2025 on, on OpenAI's forum; OpenAI's model list does not say which models
 * need the verification. Verifying the organization, or another model, mends it.
 */
private const val STREAM_PARAM = "stream"

private const val INVALID_REQUEST_TYPE = "invalid_request_error"

/**
 * The kind of an error OpenAI names by its `code` (in an error body, an `error` event or a failed
 * response), or null for a code this client does not know. The codes for an account out of credit
 * or over a spending or usage limit come with a 429, and are [AiErrorKind.BILLING]: no retry mends
 * them. The codes of a prompt declined for a usage policy ([POLICY_CODES]) are not here: they
 * are read first, as a refusal.
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
        else -> null
    }

/** The kind of an error OpenAI names by its `type`, or null for a type this client does not know. */
internal fun kindOfType(type: String): AiErrorKind? =
    when (type) {
        INVALID_REQUEST_TYPE -> AiErrorKind.INVALID_REQUEST
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
