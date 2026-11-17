package app.sleevesight.ai.openrouter

import app.sleevesight.ai.ServerSentEvents
import app.sleevesight.ai.WAITED
import app.sleevesight.ai.decode
import app.sleevesight.ai.detailOf
import app.sleevesight.ai.kindOfOffline
import app.sleevesight.ai.maxTokensSent
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * OpenRouter (#1157), as a small client over `:net` (D3): one streaming call, `POST
 * /api/v1/chat/completions` with `stream: true`, OpenAI's chat-completions shape, read as
 * server-sent events ([ChatStream]); and the model list, `GET /api/v1/models`. OpenRouter passes
 * each request on to the company whose model it names (`anthropic/…`, `openai/…`, `google/…`). The
 * key, from the sign-in or pasted, goes in `Authorization: Bearer`, through `NetSecrets`, so it is
 * never logged. No `HTTP-Referer` or `X-Title`: they would list the app in OpenRouter's rankings.
 *
 * No tools ([usesTools] false): *AI web search*'s *Up to 3 times* is asked as *Once*, as with
 * Google; the models OpenRouter reaches differ in how they take tools, and the free ones often take
 * none.
 *
 * [CompletionRequest.system], when given, is a system message before the prompt's user message.
 * [CompletionRequest.effort] becomes OpenRouter's `reasoning.effort` (`low`, `medium`, `high`),
 * which it maps onto each model's own control and drops for a model that does not reason; the
 * reasoning it streams back is skipped. With an effort the token limit is as asked, else at most
 * [CompletionRequest.DEFAULT_MAX_TOKENS] (`maxTokensSent`).
 *
 * Errors ([kindOf]): 402 is an account out of credit, or a key past its spending limit
 * ([AiErrorKind.BILLING]); a 429 for a free model's daily allowance ([FREE_DAILY]) is
 * [AiErrorKind.QUOTA_EXHAUSTED], any other a rate limit, with the wait its `Retry-After` asks; 401
 * and 403 (`:net`'s `NetError.Unauthorized`) a key refused, such as one deleted on OpenRouter; a 400
 * or 404 naming the model, [AiErrorKind.MODEL_NOT_FOUND]. The request is a `POST`, so `:net` never
 * repeats it once it may have been acted on: a paid call is never sent twice.
 *
 * @param net the process's one `NetClient`, configured with [HOST_POLICY] under [HOST].
 * @param baseUrl the API's origin, for tests.
 * @param clock for a `Retry-After` given as a date, and the models' expiry dates.
 */
class OpenRouterProvider(
    private val net: NetClient,
    private val baseUrl: String = BASE_URL,
    private val clock: Clock = Clock.System,
) : AiProvider {
    override val id: ProviderId = ProviderId.OPENROUTER

    override suspend fun models(key: Secret): ModelList {
        val request = NetRequest("$baseUrl/api/v1/models", secrets = secrets(key))
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
                    else -> ModelList.Listed(offered(list.data, clock.now()))
                }
            }
        }
    }

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        channelFlow {
            val reader = ChatStream(clock)
            val events = ServerSentEvents()

            // A stream stopped here ends as the reader decided; nothing of the exception is needed.
            @Suppress("SwallowedException")
            val result =
                try {
                    net.stream(chatRequest(request, key)) { line ->
                        events.line(line)?.let { event -> reader.read(event.data)?.let { send(it) } }
                        if (reader.malformed) throw StopStream()
                    }
                } catch (e: StopStream) {
                    null
                }
            reader.end(result)?.let { send(it) }
        }

    private fun chatRequest(
        request: CompletionRequest,
        key: Secret,
    ): NetRequest {
        val effort = request.effort
        val body =
            buildJsonObject {
                put("model", request.model)
                putJsonArray("messages") {
                    request.system?.let { system ->
                        addJsonObject {
                            put("role", "system")
                            put("content", system)
                        }
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", request.prompt)
                    }
                }
                put("max_tokens", maxTokensSent(request, effort != null))
                put("stream", true)
                effort?.let { putJsonObject("reasoning") { put("effort", effortName(it)) } }
            }
        return NetRequest(
            url = "$baseUrl/api/v1/chat/completions",
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
        const val HOST: String = "openrouter.ai"

        const val BASE_URL: String = "https://$HOST"

        /**
         * OpenRouter's host policy, which `:wiring`'s list of host policies must hold under [HOST]:
         * no interval of its own (its limits are per key and model, and it answers 429 when one is
         * reached), and its 429 passed on, so the body can say whether a free model's daily
         * allowance is used up ([HostPolicy.passOn429]).
         */
        val HOST_POLICY: HostPolicy = HostPolicy(minInterval = Duration.ZERO, passOn429 = true)

        /**
         * A whole stream, reasoning included, may take this long; `:net`'s socket timeout still
         * bounds each silence.
         */
        val STREAM_TIMEOUT: Duration = 5.minutes

        /**
         * The companies whose models are offered, by id prefix: those the prompts are written and
         * checked on (Claude, GPT and the o-series, Gemini), as the pick for #1157 has it, with the
         * name OpenRouter gives each company in its models' names, which the settings leave out.
         */
        private val FAMILIES: Map<String, String> =
            mapOf(
                "anthropic/claude" to "Anthropic: ",
                "openai/gpt-" to "OpenAI: ",
                "openai/o" to "OpenAI: ",
                "google/gemini" to "Google: ",
            )

        /** The suffix of a free model's id: no credit needed, a daily allowance instead. */
        const val FREE_SUFFIX: String = ":free"

        /** What a free model's name ends with in the settings. */
        private const val FREE_MARK = " (free)"

        /** What a 429's message names when a free model's daily allowance is used up. */
        internal const val FREE_DAILY: String = "free-models-per-day"

        /**
         * Whether [model] is offered: a text model (text in and out) of one of [FAMILIES] with no
         * variant suffix (`:batch` and the like, which this client cannot stream), or any free text
         * model ([FREE_SUFFIX]).
         */
        internal fun offers(model: WireModel): Boolean {
            val arch = model.architecture
            val text = arch?.outputModalities?.let { it == listOf("text") } ?: true
            val reads = arch?.inputModalities?.contains("text") ?: true
            val free = model.id.endsWith(FREE_SUFFIX)
            val family = ':' !in model.id && FAMILIES.keys.any { model.id.startsWith(it) } && !openAiOther(model.id)
            return text && reads && (free || family)
        }

        // `openai/o…` is the o-series only (`o3`, `o4-mini`), not another OpenAI model starting `o`.
        private fun openAiOther(id: String): Boolean = id.startsWith("openai/o") && !O_SERIES.matches(id)

        private val O_SERIES = Regex("openai/o[0-9].*")

        /**
         * The models offered ([offers]), none past its expiry date by [now]: Claude, GPT and Gemini
         * first, then the free ones, newest first within each. Each is named as OpenRouter names it,
         * without the company for the three ([FAMILIES]), and a free one marked [FREE_MARK].
         */
        internal fun offered(
            models: List<WireModel>,
            now: Instant,
        ): List<ModelInfo> {
            val today = now.toString().take(ISO_DATE_LENGTH)
            return models
                .filter(::offers)
                .filterNot { model -> model.expirationDate?.take(ISO_DATE_LENGTH)?.let { it < today } == true }
                .sortedWith(
                    compareBy<WireModel> { it.id.endsWith(FREE_SUFFIX) }
                        .thenByDescending { it.created }
                        .thenBy { it.id },
                ).map { ModelInfo(it.id, nameOf(it), it.created?.let(Instant::fromEpochSeconds)) }
        }

        private fun nameOf(model: WireModel): String {
            val listed = model.name?.trim()?.ifEmpty { null } ?: model.id
            val company = FAMILIES.entries.firstOrNull { model.id.startsWith(it.key) }?.value
            val name = company?.let { listed.removePrefix(it) } ?: listed
            return if (model.id.endsWith(FREE_SUFFIX) && !name.endsWith(FREE_MARK)) "$name$FREE_MARK" else name
        }

        /** [effort] as OpenRouter's `reasoning.effort` names it; `minimal`, never offered, as `low`. */
        fun effortName(effort: ReasoningEffort): String =
            when (effort) {
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
                ReasoningEffort.MEDIUM -> "medium"
                ReasoningEffort.HIGH -> "high"
            }

        private fun secrets(key: Secret) = NetSecrets(headers = mapOf("authorization" to "Bearer ${key.reveal()}"))

        private const val ISO_DATE_LENGTH = 10
    }
}

/** The kind of a request that got no usable answer from `:net`. */
internal fun kindOf(error: NetError): AiErrorKind =
    when (error) {
        is NetError.Offline -> kindOfOffline(error)
        is NetError.RateLimited -> AiErrorKind.RATE_LIMITED
        is NetError.Unauthorized -> AiErrorKind.UNAUTHORIZED
        is NetError.Server -> if (error.status == UNAVAILABLE) AiErrorKind.OVERLOADED else AiErrorKind.SERVER
        is NetError.InvalidRequest -> AiErrorKind.INVALID_REQUEST
        is NetError.Parse, is NetError.Protocol, is NetError.TooLarge -> AiErrorKind.UNEXPECTED_RESPONSE
    }

/** The kind of an error response `:net` passes on (400, 402, 404, 429 and the like), by its status and body. */
internal fun kindOf(response: NetResponse): AiErrorKind =
    kindOf(response.status, decode<WireErrorBody>(response.body)?.error?.message)

/**
 * The kind of an error with [status] (an error response's, or the code of an error in the stream),
 * and its [message], which tells a free model's daily allowance from a rate limit, and a model
 * OpenRouter does not know from another request it refused.
 */
internal fun kindOf(
    status: Int?,
    message: String?,
): AiErrorKind =
    when (status) {
        BAD_REQUEST -> if (namesModel(message)) AiErrorKind.MODEL_NOT_FOUND else AiErrorKind.INVALID_REQUEST
        UNAUTHORIZED, FORBIDDEN -> AiErrorKind.UNAUTHORIZED
        PAYMENT_REQUIRED -> AiErrorKind.BILLING
        NOT_FOUND -> AiErrorKind.MODEL_NOT_FOUND
        REQUEST_TIMEOUT -> AiErrorKind.TIMED_OUT
        CONTENT_TOO_LARGE -> AiErrorKind.REQUEST_TOO_LARGE
        TOO_MANY_REQUESTS ->
            if (message?.contains(OpenRouterProvider.FREE_DAILY) == true) {
                AiErrorKind.QUOTA_EXHAUSTED
            } else {
                AiErrorKind.RATE_LIMITED
            }
        UNAVAILABLE -> AiErrorKind.OVERLOADED
        null -> AiErrorKind.UNEXPECTED_RESPONSE
        else -> if (status >= SERVER_ERRORS) AiErrorKind.SERVER else AiErrorKind.UNEXPECTED_RESPONSE
    }

// "… is not a valid model ID", as OpenRouter words a 400 for a model it does not know.
private fun namesModel(message: String?): Boolean = message?.contains("valid model", ignoreCase = true) == true

/** [response]'s error, with the wait a rate limit asks for in `Retry-After`, from [now]. */
internal fun errorOf(
    response: NetResponse,
    now: Instant,
): CompletionEvent.Error {
    val kind = kindOf(response)
    return CompletionEvent.Error(kind, response.retryAfter(now).takeIf { kind in WAITED }, detail = detailOf(response))
}

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val PAYMENT_REQUIRED = 402
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val REQUEST_TIMEOUT = 408
private const val CONTENT_TOO_LARGE = 413
private const val TOO_MANY_REQUESTS = 429
private const val UNAVAILABLE = 503
private const val SERVER_ERRORS = 500
