package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
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
        var current = request
        var redirects = 0
        while (true) {
            val result =
                when (val validated = validate(current)) {
                    is Validated.Invalid -> invalid(current, validated.field)
                    is Validated.Valid -> sendWithRetries(current, validated.url)
                }
            val next = redirectFrom(current, result)?.takeIf { redirects < MAX_REDIRECTS } ?: return result
            current = next
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
    ): NetResult<NetResponse> {
        val error = NetError.InvalidRequest(field)
        log.log("$request -> $error")
        return NetResult.Failure(error)
    }

    private suspend fun sendWithRetries(
        request: NetRequest,
        url: Url,
    ): NetResult<NetResponse> {
        val domain = config.domainFor(url.host)
        val limiter = domain?.let { limiters.getValue(it) }
        var attempt = 1
        while (true) {
            limiter?.acquire()
            val started = timeSource.markNow()
            val outcome = tryOnce(request, url, rateLimited = domain != null)
            val wait = retryWait(request, outcome, attempt)
            log.log(logLine(request, outcome, started.elapsedNow(), attempt, wait))
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
        rateLimited: Boolean,
    ): Attempt =
        try {
            val response =
                http.request {
                    url(url)
                    method = if (request.method == NetMethod.POST) HttpMethod.Post else HttpMethod.Get
                    (request.query + request.secrets.query).forEach { (name, value) -> parameter(name, value) }
                    (request.headers + request.secrets.headers).forEach { (name, value) -> header(name, value) }
                    request.body?.let { body ->
                        // Validated before, so the media type parses.
                        setBody(
                            OneShotContent(
                                body.text.encodeToByteArray(),
                                checkNotNull(parseContentType(body.contentType)),
                            ),
                        )
                    }
                    request.timeout?.let { timeout { requestTimeoutMillis = it.inWholeMilliseconds } }
                }
            classify(response, rateLimited)
        } catch (e: IOException) {
            // A cancelled call can surface as an IOException; it is a cancellation, not Offline.
            currentCoroutineContext().ensureActive()
            // Timeouts, refused connections, DNS failures and dropped connections are all
            // IOExceptions in Ktor 3. Only the type is kept: a message can carry the URL.
            Attempt.Failed(NetError.Offline(e::class.simpleName ?: "IOException"))
        } catch (e: IllegalArgumentException) {
            // A check in Ktor or OkHttp that validation did not foresee. Its message can quote a
            // header value, so only the kind is kept.
            Attempt.Failed(NetError.InvalidRequest("request (${e::class.simpleName})"))
        }

    // Detekt 1.23.8 cannot resolve `bodyAsBytes` (from a library built with a newer Kotlin than its
    // own), so it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun classify(
        response: HttpResponse,
        rateLimited: Boolean,
    ): Attempt {
        val status = response.status
        val retryAfter = { parseRetryAfter(response.headers[HttpHeaders.RetryAfter], clock.now()) }
        return when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> {
                Attempt.Failed(NetError.Unauthorized(status.value))
            }

            status == HttpStatusCode.TooManyRequests ||
                (status == HttpStatusCode.ServiceUnavailable && rateLimited) -> {
                retryAfter().let { Attempt.Failed(NetError.RateLimited(it), it) }
            }

            status.value >= HttpStatusCode.InternalServerError.value -> {
                Attempt.Failed(NetError.Server(status.value), retryAfter())
            }

            else -> {
                val headers = response.headers.entries().associate { it.key to it.value }
                Attempt.Answered(NetResponse(status.value, headers, response.bodyAsBytes()))
            }
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
                is NetError.Unauthorized, is NetError.Parse, is NetError.InvalidRequest -> false
            }
        val wait = outcome.retryAfter ?: config.retry.delayAfter(attempt)
        return wait.takeIf { retryable && it <= config.retry.maxDelay }
    }

    private fun logLine(
        request: NetRequest,
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
        return "$request -> $result in ${elapsed.inWholeMilliseconds} ms (attempt $attempt)$retry"
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
