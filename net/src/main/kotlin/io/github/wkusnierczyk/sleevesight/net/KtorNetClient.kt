package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [NetClient] over Ktor. [engine] is OkHttp in the app and the CLI, `MockEngine` in tests;
 * [timeSource] is virtual in tests.
 */
internal class KtorNetClient(
    private val config: NetConfig,
    engine: HttpClientEngine,
    private val log: NetLog,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : NetClient {
    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(UserAgent) { agent = config.userAgent }
            install(HttpTimeout) {
                connectTimeoutMillis = config.timeouts.connect.inWholeMilliseconds
                socketTimeoutMillis = config.timeouts.socket.inWholeMilliseconds
                requestTimeoutMillis = config.timeouts.request.inWholeMilliseconds
            }
        }

    /** One limiter per configured host or domain, made up front so the map needs no lock. */
    private val limiters: Map<String, RateLimiter> =
        config.hosts.mapValues { (_, policy) -> RateLimiter(policy.minInterval, timeSource) }

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        val limiter = config.policyFor(Url(request.url).host)?.let { (domain, _) -> limiters.getValue(domain) }
        var attempt = 1
        while (true) {
            limiter?.acquire()
            val started = timeSource.markNow()
            val outcome = tryOnce(request)
            val wait = retryWait(request, outcome, attempt)
            log.log(logLine(request, outcome, started.elapsedNow(), attempt, wait))
            if (wait == null) return outcome.result
            delay(wait)
            attempt++
        }
    }

    override fun close() = http.close()

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

    // Detekt 1.23.8 cannot resolve the suspending calls inside (from libraries built with a newer
    // Kotlin than its own), so it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun tryOnce(request: NetRequest): Attempt =
        try {
            val response =
                http.request(request.url) {
                    method = if (request.method == NetMethod.POST) HttpMethod.Post else HttpMethod.Get
                    (request.query + request.secrets.query).forEach { (name, value) -> parameter(name, value) }
                    (request.headers + request.secrets.headers).forEach { (name, value) -> header(name, value) }
                    request.body?.let { setBody(TextContent(it.text, ContentType.parse(it.contentType))) }
                }
            val status = response.status
            val retryAfter =
                response.headers[HttpHeaders.RetryAfter]
                    ?.trim()
                    ?.toLongOrNull()
                    ?.seconds
            when {
                status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> {
                    Attempt.Failed(NetError.Unauthorized(status.value))
                }

                status == HttpStatusCode.TooManyRequests -> {
                    Attempt.Failed(NetError.RateLimited(retryAfter), retryAfter)
                }

                status.value >= HttpStatusCode.InternalServerError.value -> {
                    Attempt.Failed(NetError.Server(status.value), retryAfter)
                }

                else -> {
                    val headers = response.headers.entries().associate { it.key to it.value }
                    Attempt.Answered(NetResponse(status.value, headers, response.bodyAsText()))
                }
            }
        } catch (e: IOException) {
            // Timeouts, refused connections, DNS failures and dropped connections are all
            // IOExceptions in Ktor 3. Only the type is kept: a message can carry the URL.
            Attempt.Failed(NetError.Offline(e::class.simpleName ?: "IOException"))
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
                is NetError.Unauthorized, is NetError.Parse -> false
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
}
