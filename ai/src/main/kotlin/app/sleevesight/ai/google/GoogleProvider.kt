package app.sleevesight.ai.google

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
import app.sleevesight.net.RetryPolicy
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Google's Gemini API (`generativelanguage.googleapis.com`, with a key from Google AI Studio), as a
 * small client over `:net` (D3): one streaming call, `POST /v1/models/{model}:streamGenerateContent`
 * with `alt=sse`, read as server-sent events; and the model list, `GET /v1/models`. The key goes in
 * `x-goog-api-key`, through `NetSecrets`, never in the URL, so it is never logged.
 *
 * `generateContent` rather than the newer Interactions API, which Google recommends for new
 * projects: the Interactions API stores each exchange on Google's side unless `store=false` is
 * sent (55 days on the paid tier, a day on the free one), while `generateContent` keeps nothing,
 * and Google calls it legacy but fully supported. A move to the Interactions API may come later.
 * The `v1` routes rather than `v1beta`: `v1` is the stable version, and has the thinking control
 * and the model list. Not Vertex AI, which needs a Google Cloud project with OAuth or a service account
 * rather than a key the user pastes.
 *
 * [CompletionRequest.effort] becomes `generationConfig.thinkingConfig.thinkingLevel` (`minimal`,
 * `low`, `medium`, `high`; [thinkingLevel]) for a model that takes it ([takesThinkingLevel]): a
 * model that does not take `minimal` ([takesMinimal]) gets `low`, the least it takes, and any other
 * model (the Gemini 2.5 models, which take a token budget instead, and names this client cannot
 * read, such as the `-latest` aliases, whose version is not in the id) gets none, and reasons as
 * its default says: for those, the effort chosen in the settings has no effect. Thoughts are not
 * asked for (`includeThoughts`), and a thought part that comes all the same is skipped.
 *
 * The stream's text becomes [CompletionEvent.Delta]s, and its end [CompletionEvent.Done], with the
 * usage and the stop reason, or [CompletionEvent.Error] ([ContentStream]). The request is a `POST`,
 * so `:net` never repeats it once it may have been acted on: a paid call is never sent twice.
 *
 * Google answers `429 RESOURCE_EXHAUSTED` both for a rate limit (per minute, or the spend-based
 * limit per ten minutes) and for a quota that resets only after a day, such as the free tier's
 * requests per day, and only the body's `QuotaFailure` says which; so its host's policy
 * ([HOST_POLICY]) has `:net` pass a 429 on, unretried, and the client reads it. A daily or monthly
 * quota is [AiErrorKind.QUOTA_EXHAUSTED] at once ([exhaustsAQuota]): no retry mends it that day,
 * and a paid or higher tier does. A monthly spending cap reached ([reachesSpendCap]) is
 * [AiErrorKind.BILLING] at once: the billing account is blocked until its next cycle. A rate limit
 * is sent once more after its `RetryInfo.retryDelay`, else its `Retry-After`, else
 * [RetryPolicy.delayAfter]'s first wait, when that is within [RetryPolicy.maxDelay], as `:net`
 * retries another host's 429 and as `OpenAiProvider` does; a 429 was refused before it was acted
 * on, so the call is not paid twice. Otherwise it ends as [AiErrorKind.RATE_LIMITED] with the wait.
 * The model list is not sent again: a 429 there is reported as its kind.
 *
 * @param net the process's one `NetClient`, configured with [HOST_POLICY] under [HOST].
 * @param retry the waits for a rate limit: [net]'s own `NetConfig.retry`, so both wait alike. It
 *   has no default, so no caller leaves it different by accident.
 * @param baseUrl the API's origin, for tests.
 * @param clock for a `Retry-After` given as a date.
 */
class GoogleProvider(
    private val net: NetClient,
    private val retry: RetryPolicy,
    private val baseUrl: String = BASE_URL,
    private val clock: Clock = Clock.System,
) : AiProvider {
    override val id: ProviderId = ProviderId.GOOGLE

    override suspend fun models(key: Secret): ModelList {
        val models = mutableListOf<ModelInfo>()
        var token: String? = null
        var failure: AiErrorKind? = null
        var pages = 0
        do {
            when (val page = modelPage(key, token)) {
                is Page.Failed -> {
                    failure = page.kind
                }

                is Page.Read -> {
                    models += page.models
                    token = page.next
                }
            }
            pages++
        } while (failure == null && token != null && pages < MAX_MODEL_PAGES)
        return failure?.let { ModelList.Failed(it) } ?: ModelList.Listed(models)
    }

    override fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent> =
        channelFlow {
            // The model goes in the path: an id that is not one would change the request's route.
            if (!MODEL_ID.matches(request.model)) {
                send(CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND))
                return@channelFlow
            }
            var waited = false
            do {
                val reader = ContentStream(clock)
                val events = ServerSentEvents()

                // A stream stopped here ends as the reader decided; nothing of the exception is needed.
                @Suppress("SwallowedException")
                val result =
                    try {
                        net.stream(contentRequest(request, key)) { line ->
                            events.line(line)?.let { event -> reader.read(event.data)?.let { send(it) } }
                            if (reader.malformed) throw StopStream()
                        }
                    } catch (e: StopStream) {
                        null
                    }
                // An error status comes before any of the body, so nothing was emitted for it.
                val refused = (result as? NetResult.Success)?.value?.takeUnless { it.isSuccess }
                val wait = refused?.takeIf { !waited }?.let(::rateLimitWait)
                if (wait != null) {
                    delay(wait)
                    waited = true
                } else {
                    reader.end(result)?.let { send(it) }
                }
            } while (wait != null)
        }

    /** The wait before sending again after [response], a rate limit; null if it is not one, or too long. */
    private fun rateLimitWait(response: NetResponse): Duration? =
        if (response.status == TOO_MANY_REQUESTS && kindOf(response) == AiErrorKind.RATE_LIMITED) {
            (waitOf(response, clock.now()) ?: retry.delayAfter(1)).takeIf { it <= retry.maxDelay }
        } else {
            null
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
        token: String?,
    ): Page {
        val query = mapOf("pageSize" to MODEL_PAGE_SIZE.toString()) + listOfNotNull(token?.let { "pageToken" to it })
        val request = NetRequest("$baseUrl/$API_VERSION/models", query = query, secrets = secrets(key))
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
                    else -> Page.Read(page.models.mapNotNull(::modelInfo), page.nextPageToken?.ifEmpty { null })
                }
            }
        }
    }

    private fun contentRequest(
        request: CompletionRequest,
        key: Secret,
    ): NetRequest {
        val level = request.effort?.let { thinkingLevel(request.model, it) }
        val body =
            buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", request.prompt) } }
                    }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", request.maxTokens)
                    level?.let { putJsonObject("thinkingConfig") { put("thinkingLevel", it) } }
                }
            }
        return NetRequest(
            url = "$baseUrl/$API_VERSION/models/${request.model}:streamGenerateContent",
            method = NetMethod.POST,
            query = mapOf("alt" to "sse"),
            headers = mapOf("accept" to "text/event-stream"),
            secrets = secrets(key),
            body = NetBody(body.toString(), "application/json"),
            timeout = STREAM_TIMEOUT,
        )
    }

    /** Thrown from the line handler to end a stream whose events cannot be read. */
    private class StopStream : RuntimeException()

    companion object {
        const val HOST: String = "generativelanguage.googleapis.com"

        const val BASE_URL: String = "https://$HOST"

        /**
         * Google's host policy, which `:wiring`'s list of host policies must hold under [HOST]: no
         * interval of its own (the limits are per project and tier, and it answers 429 when one is
         * reached), and its 429 passed on ([HostPolicy.passOn429]). Google answers
         * `429 RESOURCE_EXHAUSTED` for a per-minute rate limit, which a short wait mends, and also
         * for a daily quota, which no retry mends that day; only the body's `QuotaFailure` says
         * which ([kindOf]), so the client reads it.
         */
        val HOST_POLICY: HostPolicy = HostPolicy(minInterval = Duration.ZERO, passOn429 = true)

        /** The API version this client speaks: the stable one. */
        const val API_VERSION: String = "v1"

        /**
         * A whole stream, reasoning included, may take this long; `:net`'s socket timeout still
         * bounds each silence.
         */
        val STREAM_TIMEOUT: Duration = 5.minutes

        /**
         * The model families that take `thinkingLevel` at `minimal`, by id prefix, from the Gemini
         * API's thinking guide (*Thinking levels*) on 2026-10-21: Gemini 3.6 Flash, 3.5 and 3.1
         * Flash-Lite, and 3 Flash. Gemini 3.8 Flash and Gemini 4 Argon answer it with an error, and
         * so may a family not listed, so those get `low` instead. A new family is added here when
         * the guide lists it.
         */
        val MINIMAL_MODELS: List<String> =
            listOf(
                "gemini-3.6-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-3-flash",
            )

        /**
         * Whether [model] takes `thinkingLevel`: a Gemini model of version 3 or later (`gemini-3…`,
         * `gemini-3.5-…`, `gemini-4-…`). The guide says Gemini 4 requires it and Gemini 3 is meant
         * to use it, while the 2.5 models refuse it and take `thinkingBudget` instead. The model
         * list carries no thinking levels to ask instead ([ModelInfo]). An alias without a version
         * (`gemini-flash-latest`) is not known to take it, so it gets none: the aliases stay in the
         * list, since they are the user's to choose, and a level sent to the wrong model is an error
         * on every card while a level not sent only leaves the model's default.
         */
        fun takesThinkingLevel(model: String): Boolean =
            GEMINI_VERSION
                .matchEntire(model)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { it >= FIRST_LEVELLED } ?: false

        /** Whether [model] takes `thinkingLevel` at `minimal` ([MINIMAL_MODELS]). */
        fun takesMinimal(model: String): Boolean = MINIMAL_MODELS.any { model == it || model.startsWith("$it-") }

        /**
         * [effort] as [model]'s `thinkingLevel`, or null for a model that takes none. A model that
         * does not take `minimal` gets `low`.
         */
        fun thinkingLevel(
            model: String,
            effort: ReasoningEffort,
        ): String? =
            when {
                !takesThinkingLevel(model) -> null
                effort == ReasoningEffort.MINIMAL && !takesMinimal(model) -> "low"
                else -> effort.name.lowercase()
            }

        private const val FIRST_LEVELLED = 3

        /** `gemini-<major>[.<minor>]-…`; the major version is the group. */
        private val GEMINI_VERSION = Regex("gemini-(\\d+)(?:\\.\\d+)?-.+")

        /** A model id as the path takes it: letters, digits, dots, dashes and underscores. */
        private val MODEL_ID = Regex("[A-Za-z0-9._-]+")

        /** The most the API returns on one page. */
        private const val MODEL_PAGE_SIZE = 1000

        /** A bound on pagination, against a server that always says there is more. */
        private const val MAX_MODEL_PAGES = 10

        private const val MODEL_PREFIX = "models/"

        /** The method a model must support to write a card. */
        private const val GENERATE_CONTENT = "generateContent"

        /**
         * Id segments of the models that take `generateContent` but do not answer a text prompt
         * with text, from the Gemini API's models page on 2026-10-21: image generation (`-image`,
         * and Nano Banana's `banana`), speech (`-tts`, `-audio`), the Live API (`-live`), and
         * transcription (`-transcribe`). Chosen for a card, each would fail every request. Also,
         * as a guard, the music and video families (`lyria`, `veo`, `omni`), which today do not
         * take `generateContent`: the method is the first filter ([modelInfo]), the id the second.
         * Whole segments only, never parts of one.
         */
        val NON_TEXT_SEGMENTS: Set<String> =
            setOf("image", "tts", "audio", "live", "transcribe", "banana", "lyria", "veo", "omni")

        /** Whether [id] is a model that writes text from a text prompt, as far as its id tells. */
        fun writesText(id: String): Boolean = id.split('-').none { it in NON_TEXT_SEGMENTS }

        private fun secrets(key: Secret) = NetSecrets(headers = mapOf("x-goog-api-key" to key.reveal()))

        // Only the models that write text through `generateContent`: the list also holds embedding
        // and video models, which do not take it, and image, speech and live models, which take it
        // but answer otherwise ([NON_TEXT_SEGMENTS]).
        private fun modelInfo(model: WireModel): ModelInfo? {
            val id = model.name.removePrefix(MODEL_PREFIX)
            val usable = GENERATE_CONTENT in model.supportedGenerationMethods && MODEL_ID.matches(id)
            return if (usable && writesText(id)) {
                ModelInfo(id = id, displayName = model.displayName?.takeIf { it.isNotBlank() } ?: id, createdAt = null)
            } else {
                null
            }
        }
    }
}

/** The kind of a request that got no usable answer from `:net`. 503 is Google's `UNAVAILABLE`: overloaded or down. */
internal fun kindOf(error: NetError): AiErrorKind =
    when (error) {
        is NetError.Offline -> AiErrorKind.OFFLINE
        is NetError.RateLimited -> AiErrorKind.RATE_LIMITED
        is NetError.Unauthorized -> AiErrorKind.UNAUTHORIZED
        is NetError.Server -> if (error.status == UNAVAILABLE_STATUS) AiErrorKind.OVERLOADED else AiErrorKind.SERVER
        is NetError.InvalidRequest -> AiErrorKind.INVALID_REQUEST
        is NetError.Parse, is NetError.Protocol, is NetError.TooLarge -> AiErrorKind.UNEXPECTED_RESPONSE
    }

/** The kind of an error response that `:net` passes on as a response (400, 402, 404, 413 and the like). */
internal fun kindOf(response: NetResponse): AiErrorKind =
    decode<WireErrorBody>(response.body)?.error?.let(::kindOf)
        ?: when (response.status) {
            BAD_REQUEST -> AiErrorKind.INVALID_REQUEST
            PAYMENT_REQUIRED -> AiErrorKind.BILLING
            NOT_FOUND -> AiErrorKind.MODEL_NOT_FOUND
            CONTENT_TOO_LARGE -> AiErrorKind.REQUEST_TOO_LARGE
            TOO_MANY_REQUESTS -> AiErrorKind.RATE_LIMITED
            else -> AiErrorKind.UNEXPECTED_RESPONSE
        }

/**
 * The kind of an error Google names, or null for one this client does not know. A key that is not
 * valid comes as `400 INVALID_ARGUMENT` with the reason `API_KEY_INVALID`, so the reason is read
 * first. `FAILED_PRECONDITION` is, as the error reference gives it, a project with no billing in a
 * country without the free tier, which billing mends; the API also sends it for a country where it
 * is not offered at all ("User location is not supported"), which billing does not, so that one,
 * told apart by its message (no reason in `details` names it), is [AiErrorKind.UNAVAILABLE_HERE].
 * `RESOURCE_EXHAUSTED` is a spent prepaid balance at 402, and a rate limit otherwise.
 */
internal fun kindOf(error: WireError): AiErrorKind? =
    when {
        error.details.any { it.reason?.startsWith(API_KEY_REASON) == true } -> AiErrorKind.UNAUTHORIZED
        error.status == "FAILED_PRECONDITION" && error.message?.contains(LOCATION, ignoreCase = true) == true -> {
            AiErrorKind.UNAVAILABLE_HERE
        }
        error.status == "RESOURCE_EXHAUSTED" && error.code == PAYMENT_REQUIRED -> AiErrorKind.BILLING
        error.status == "RESOURCE_EXHAUSTED" && reachesSpendCap(error) -> AiErrorKind.BILLING
        error.status == "RESOURCE_EXHAUSTED" && exhaustsAQuota(error) -> AiErrorKind.QUOTA_EXHAUSTED
        else -> error.status?.let(::kindOfStatus)
    }

/**
 * Whether [error]'s `QuotaFailure` names a quota that resets only after a day or a month (`quotaId`
 * `…PerDay…`, `…PerMonth…`), such as the free tier's requests per day, which reset at midnight
 * Pacific time: no retry mends it, and a paid tier or a higher one does, so it is
 * [AiErrorKind.QUOTA_EXHAUSTED], at once. A quota per minute, or a 429 that names none (the
 * spend-based limit per ten minutes among them), is a rate limit.
 */
internal fun exhaustsAQuota(error: WireError): Boolean =
    error.details.any { detail ->
        detail.violations.any { violation ->
            val id = violation.quotaId.orEmpty()
            LONG_QUOTAS.any { id.contains(it, ignoreCase = true) }
        }
    }

/**
 * Whether [error] says a monthly spending cap is reached, the billing account's or the project's:
 * "Your billing account has exceeded its monthly spending cap", as reported on Google's developer
 * forum in May 2026, with no `details`. Matched on "monthly spending cap" or "monthly spend cap"
 * only, so the spend-based rate limit per ten minutes, which a wait mends, stays a rate limit.
 */
internal fun reachesSpendCap(error: WireError): Boolean =
    SPEND_CAPS.any { error.message?.contains(it, ignoreCase = true) == true }

/**
 * The wait [response] asks for: its `RetryInfo.retryDelay` (`"38s"`, `"1.5s"`), else its
 * `Retry-After`, from [now]; null when it gives neither.
 */
internal fun waitOf(
    response: NetResponse,
    now: Instant,
): Duration? =
    decode<WireErrorBody>(response.body)
        ?.error
        ?.details
        ?.firstNotNullOfOrNull { it.retryDelay?.let(::protobufDuration) }
        ?: response.retryAfter(now)

/** A protobuf `Duration` in its JSON form (`"38s"`, `"0.5s"`), or null if [text] is not one. */
internal fun protobufDuration(text: String): Duration? =
    text
        .takeIf { it.endsWith("s") }
        ?.removeSuffix("s")
        ?.toDoubleOrNull()
        ?.takeIf { it >= 0 }
        ?.seconds

/** [response]'s error, with the wait a rate limit asks for, from [now]. */
internal fun errorOf(
    response: NetResponse,
    now: Instant,
): CompletionEvent.Error {
    val kind = kindOf(response)
    return CompletionEvent.Error(kind, waitOf(response, now).takeIf { kind == AiErrorKind.RATE_LIMITED })
}

private fun kindOfStatus(status: String): AiErrorKind? =
    when (status) {
        "INVALID_ARGUMENT", "OUT_OF_RANGE" -> AiErrorKind.INVALID_REQUEST
        "FAILED_PRECONDITION" -> AiErrorKind.BILLING
        "UNAUTHENTICATED", "PERMISSION_DENIED" -> AiErrorKind.UNAUTHORIZED
        "NOT_FOUND" -> AiErrorKind.MODEL_NOT_FOUND
        "RESOURCE_EXHAUSTED" -> AiErrorKind.RATE_LIMITED
        "INTERNAL", "DEADLINE_EXCEEDED", "UNKNOWN" -> AiErrorKind.SERVER
        "UNAVAILABLE" -> AiErrorKind.OVERLOADED
        else -> null
    }

private const val API_KEY_REASON = "API_KEY_"
private const val LOCATION = "location is not supported"
private const val BAD_REQUEST = 400
private const val PAYMENT_REQUIRED = 402
private const val NOT_FOUND = 404
private const val CONTENT_TOO_LARGE = 413
private const val TOO_MANY_REQUESTS = 429

/** The words of a message saying a monthly spending cap is reached. */
private val SPEND_CAPS = listOf("monthly spending cap", "monthly spend cap")

/** The `quotaId` parts of the quotas that reset after a day or a month. */
private val LONG_QUOTAS = listOf("PerDay", "PerMonth")
private const val UNAVAILABLE_STATUS = 503
