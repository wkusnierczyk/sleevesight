package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [NetClient] over Ktor. [engine] is OkHttp in the app and the CLI, `MockEngine` in tests, and is
 * closed with the client; [timeSource] and [clock] are virtual in tests.
 */
internal class KtorNetClient(
    private val config: NetConfig,
    private val engine: HttpClientEngine,
    private val log: NetLog,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val clock: Clock = Clock.System,
) : NetClient {
    private val http =
        HttpClient(engine) {
            expectSuccess = false
            // Ktor's redirects forward key headers other than Authorization to any host, and skip
            // the rate limits; `send` follows redirects itself.
            followRedirects = false
            install(UserAgent) { agent = config.userAgent }
            install(HttpTimeout) {
                connectTimeoutMillis = config.timeouts.connect.inWholeMilliseconds
                socketTimeoutMillis = config.timeouts.socket.inWholeMilliseconds
                requestTimeoutMillis = config.timeouts.request.inWholeMilliseconds
            }
        }

    /** One limiter per configured domain, made up front so the map needs no lock. */
    private val limiters: Map<String, RateLimiter> =
        config.hosts.mapValues { (_, policy) -> RateLimiter(policy.minInterval, timeSource) }

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        // Collected before the first send, so a redirect can neither log nor carry it elsewhere.
        val material = credentialMaterial(request)
        var current = request
        var redirects = 0
        while (true) {
            val sent =
                when (val validated = validate(current)) {
                    is Validated.Invalid -> return invalid(current, validated.field, material)
                    is Validated.Valid -> effectiveUrl(validated.url, current)
                }
            val result = sendWithRetries(current, sent, material)
            current =
                redirectFrom(current, sent, result, material)?.takeIf { redirects < MAX_REDIRECTS } ?: return result
            redirects++
        }
    }

    /** `HttpClient(engine)` leaves the engine to its owner, which is this client. */
    override fun close() {
        http.close()
        engine.close()
    }

    private fun invalid(
        request: NetRequest,
        field: String,
        material: Set<String>,
    ): NetResult<NetResponse> {
        val error = NetError.InvalidRequest(field)
        log.log("${Redaction.describe(request, null, material)} -> $error")
        return NetResult.Failure(error)
    }

    private suspend fun sendWithRetries(
        request: NetRequest,
        url: Url,
        material: Set<String>,
    ): NetResult<NetResponse> {
        val domain = config.domainFor(url.host)
        val limiter = domain?.let { limiters.getValue(it) }
        val policy = domain?.let { config.hosts.getValue(it) }
        val description = Redaction.describe(request, url, material)
        var attempt = 1
        while (true) {
            limiter?.acquire()
            val started = timeSource.markNow()
            val outcome = tryOnce(request, url, policy)
            val wait = retryWait(request, outcome, attempt)
            log.log(logLine(description, outcome, started.elapsedNow(), attempt, wait))
            if (wait == null) return outcome.result
            delay(wait)
            attempt++
        }
    }

    /** One attempt's outcome, with what the retry decision needs. */
    private sealed interface Attempt {
        val result: NetResult<NetResponse>

        data class Answered(
            val response: NetResponse,
        ) : Attempt {
            override val result = NetResult.Success(response)
        }

        data class Failed(
            val error: NetError,
            val retryAfter: Duration? = null,
        ) : Attempt {
            override val result = NetResult.Failure(error)
        }
    }

    private suspend fun tryOnce(
        request: NetRequest,
        url: Url,
        policy: HostPolicy?,
    ): Attempt =
        try {
            http
                .prepareRequest {
                    url(url)
                    method = if (request.method == NetMethod.POST) HttpMethod.Post else HttpMethod.Get
                    (request.headers + request.secrets.headers).forEach { (name, value) -> header(name, value) }
                    request.body?.let { body ->
                        // Validated before, so the media type parses.
                        val type = checkNotNull(parseContentType(body.contentType))
                        setBody(OneShotContent(body.text.encodeToByteArray(), type))
                    }
                    request.timeout?.let { timeout { requestTimeoutMillis = it.inWholeMilliseconds } }
                }.execute { response -> classify(request, response, policy) }
        } catch (e: IOException) {
            // Defensive: no known path brings a cancellation here as an IOException, but if one does,
            // it is a cancellation, not Offline.
            currentCoroutineContext().ensureActive()
            // Timeouts, refused connections, DNS failures and dropped connections are all
            // IOExceptions in Ktor 3. Only the type is kept: a message can carry the URL.
            Attempt.Failed(NetError.Offline(e::class.simpleName ?: "IOException"))
        } catch (e: IllegalArgumentException) {
            // A check in Ktor or OkHttp that validation did not foresee. Its message can quote a
            // header value, so only the kind is kept.
            Attempt.Failed(NetError.InvalidRequest("request (${e::class.simpleName})"))
        }

    private suspend fun classify(
        request: NetRequest,
        response: HttpResponse,
        policy: HostPolicy?,
    ): Attempt {
        val status = response.status
        val retryAfter = {
            val value = response.headers[ORIGINAL_RETRY_AFTER] ?: response.headers[HttpHeaders.RetryAfter]
            parseRetryAfter(value, clock.now())
        }
        val busy =
            status == HttpStatusCode.ServiceUnavailable && policy?.rateLimitedOn503 == true && request.method.idempotent
        return when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> {
                Attempt.Failed(NetError.Unauthorized(status.value))
            }

            status == HttpStatusCode.TooManyRequests || busy -> {
                retryAfter().let { Attempt.Failed(NetError.RateLimited(it), it) }
            }

            status.value >= HttpStatusCode.InternalServerError.value -> {
                Attempt.Failed(NetError.Server(status.value), retryAfter())
            }

            else -> {
                read(response, request.maxBytes)
            }
        }
    }

    // Detekt 1.23.8 cannot resolve `readBuffer` (from a library built with a newer Kotlin than its
    // own), so it takes the modifier for redundant; the compiler requires it.

    /** The body, read up to [maxBytes] and no further. */
    @Suppress("RedundantSuspendModifier")
    private suspend fun read(
        response: HttpResponse,
        maxBytes: Long,
    ): Attempt {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val bytes =
            if (declared != null && declared > maxBytes) {
                null
            } else {
                response
                    .bodyAsChannel()
                    .readBuffer(maxBytes + 1)
                    .readByteArray()
                    .takeIf { it.size <= maxBytes }
            }
        return if (bytes == null) {
            Attempt.Failed(NetError.TooLarge(maxBytes))
        } else {
            val headers = response.headers.entries().associate { it.key to it.value }
            Attempt.Answered(NetResponse(response.status.value, headers, bytes))
        }
    }

    /**
     * The wait before the next attempt, or null to stop. 429 is retried for any method, since the
     * server refused the request without acting on it; 5xx and no response only for idempotent ones,
     * so a paid call is never sent twice.
     */
    private fun retryWait(
        request: NetRequest,
        outcome: Attempt,
        attempt: Int,
    ): Duration? {
        if (outcome !is Attempt.Failed || attempt >= config.retry.maxAttempts) return null
        val retryable =
            when (outcome.error) {
                is NetError.RateLimited -> true
                is NetError.Server, is NetError.Offline -> request.method.idempotent
                is NetError.Unauthorized, is NetError.Parse, is NetError.InvalidRequest, is NetError.TooLarge -> false
            }
        val wait = outcome.retryAfter ?: config.retry.delayAfter(attempt)
        return wait.takeIf { retryable && it <= config.retry.maxDelay }
    }

    private fun logLine(
        description: String,
        outcome: Attempt,
        elapsed: Duration,
        attempt: Int,
        wait: Duration?,
    ): String {
        val result =
            when (outcome) {
                is Attempt.Answered -> outcome.response.status.toString()
                is Attempt.Failed -> outcome.error.toString()
            }
        val retry = wait?.let { ", retrying in $it" } ?: ""
        return "$description -> $result in ${elapsed.inWholeMilliseconds} ms (attempt $attempt)$retry"
    }

    /**
     * A body sent as a stream, which OkHttp marks one-shot: it cannot replay it for a follow-up of
     * its own. Each attempt makes a new one.
     */
    private class OneShotContent(
        private val bytes: ByteArray,
        override val contentType: ContentType,
    ) : OutgoingContent.ReadChannelContent() {
        override val contentLength: Long get() = bytes.size.toLong()

        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
