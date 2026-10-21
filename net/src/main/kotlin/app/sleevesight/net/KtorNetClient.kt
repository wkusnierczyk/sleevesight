package app.sleevesight.net

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
import io.ktor.http.isSuccess
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
        // Decided from the caller's request alone, and carried through the chain.
        val staysInOrigin = staysInOrigin(request)
        var current = request
        var redirects = 0
        while (true) {
            // Decided on every hop, before logging or sending; once true, it stays so (NetSecrets.hideInLog).
            val hidden = hiddenInLog(current)
            val sent =
                when (val validated = validate(current)) {
                    is Validated.Invalid -> return invalid(current, validated.field, hidden)
                    is Validated.Valid -> effectiveUrl(validated.url, current)
                }
            val result = sendWithRetries(current, sent, hidden)
            current =
                redirectFrom(current, sent, result, staysInOrigin)?.takeIf { redirects < MAX_REDIRECTS }
                    ?: return result
            redirects++
        }
    }

    override suspend fun stream(
        request: NetRequest,
        onLine: suspend (String) -> Unit,
    ): NetResult<NetResponse> {
        val hidden = hiddenInLog(request)
        val sent =
            when (val validated = validate(request)) {
                is Validated.Invalid -> return invalid(request, validated.field, hidden)
                is Validated.Valid -> effectiveUrl(validated.url, request)
            }
        return try {
            sendWithRetries(request, sent, hidden, onLine)
        } catch (e: ConsumerFailure) {
            throw e.failure
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
        hidden: Boolean,
    ): NetResult<NetResponse> {
        val error = NetError.InvalidRequest(field)
        val url = parseHttpUrl(request.url)?.takeIf { field != "url" }?.let { effectiveUrl(it, request) }
        log.log("${Redaction.describe(request, url, hidden)} -> $error")
        return NetResult.Failure(error)
    }

    private suspend fun sendWithRetries(
        request: NetRequest,
        url: Url,
        hidden: Boolean,
        onLine: (suspend (String) -> Unit)? = null,
    ): NetResult<NetResponse> {
        val domain = config.domainFor(url.host)
        val limiter = domain?.let { limiters.getValue(it) }
        val policy = domain?.let { config.hosts.getValue(it) }
        val description = Redaction.describe(request, url, hidden)
        var attempt = 1
        while (true) {
            limiter?.acquire()
            val started = timeSource.markNow()
            val outcome =
                try {
                    tryOnce(request, url, policy, onLine)
                } catch (e: ConsumerFailure) {
                    // The caller's line handler threw: the attempt ends here and is still logged.
                    val elapsed = started.elapsedNow().inWholeMilliseconds
                    log.log("$description -> stopped by caller in $elapsed ms (attempt $attempt)")
                    throw e
                }
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

        /** [final]: the failure came after a streamed body began, so the attempt is not repeated. */
        data class Failed(
            val error: NetError,
            val retryAfter: Duration? = null,
            val final: Boolean = false,
        ) : Attempt {
            override val result = NetResult.Failure(error)
        }
    }

    // Exceptions are reduced to fixed kinds on purpose: their messages can quote the URL or a header.
    @Suppress("SwallowedException")
    private suspend fun tryOnce(
        request: NetRequest,
        url: Url,
        policy: HostPolicy?,
        onLine: (suspend (String) -> Unit)?,
    ): Attempt {
        // Set once a streamed 2xx body begins: a failure after it is final, wherever it is caught.
        // A request timeout mid-stream, for one, leaves `execute` as Ktor's timeout exception.
        val stream = StreamProgress()
        return try {
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
                }.execute { response -> classify(request, response, policy, onLine, stream) }
        } catch (e: EngineProtocolException) {
            Attempt.Failed(NetError.Protocol(e.reason), final = stream.began)
        } catch (e: IOException) {
            // Defensive: no known path brings a cancellation here as an IOException, but if one does,
            // it is a cancellation, not Offline.
            currentCoroutineContext().ensureActive()
            // Timeouts, refused connections, DNS failures and dropped connections are all
            // IOExceptions in Ktor 3. Only the type is kept: a message can carry the URL.
            Attempt.Failed(NetError.Offline(e::class.simpleName ?: "IOException"), final = stream.began)
        } catch (e: IllegalArgumentException) {
            // A check in Ktor or OkHttp that validation did not foresee. Its message can quote a
            // header value, so it is reported by a fixed label.
            Attempt.Failed(NetError.InvalidRequest("request"), final = stream.began)
        }
    }

    /** Whether an attempt's streamed body has begun. */
    private class StreamProgress {
        var began: Boolean = false
    }

    private suspend fun classify(
        request: NetRequest,
        response: HttpResponse,
        policy: HostPolicy?,
        onLine: (suspend (String) -> Unit)?,
        stream: StreamProgress,
    ): Attempt {
        val status = response.status
        val retryAfter = {
            // The private header is the interceptor's, and only on a 503 (see okHttpEngine).
            val moved =
                if (status ==
                    HttpStatusCode.ServiceUnavailable
                ) {
                    response.headers[ORIGINAL_RETRY_AFTER]
                } else {
                    null
                }
            parseRetryAfter(moved ?: response.headers[HttpHeaders.RetryAfter], clock.now())
        }
        val busy =
            status == HttpStatusCode.ServiceUnavailable && policy?.rateLimitedOn503 == true && request.method.idempotent
        // A host whose 429 may mean something no retry mends has it read as a response.
        val tooMany = status == HttpStatusCode.TooManyRequests && policy?.passOn429 != true
        return when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> {
                Attempt.Failed(NetError.Unauthorized(status.value))
            }

            tooMany || busy -> {
                retryAfter().let { Attempt.Failed(NetError.RateLimited(it), it) }
            }

            status.value >= HttpStatusCode.InternalServerError.value -> {
                Attempt.Failed(NetError.Server(status.value), retryAfter())
            }

            else -> {
                read(response, request.maxBytes, onLine, stream)
            }
        }
    }

    // Detekt 1.23.8 cannot resolve `readBuffer` (from a library built with a newer Kotlin than its
    // own), so it takes the modifier for redundant; the compiler requires it.

    /**
     * The body, read up to [maxBytes] and no further; for a stream ([onLine]) a 2xx body is passed on
     * line by line instead, and once lines have reached the caller a failure is final.
     */
    @Suppress("RedundantSuspendModifier")
    private suspend fun read(
        response: HttpResponse,
        maxBytes: Long,
        onLine: (suspend (String) -> Unit)?,
        stream: StreamProgress,
    ): Attempt {
        if (onLine != null && response.status.isSuccess()) {
            stream.began = true
            return readLines(response, maxBytes, onLine)?.let { Attempt.Failed(it, final = true) }
                ?: Attempt.Answered(NetResponse(response.status.value, headersOf(response), ByteArray(0)))
        }
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
            Attempt.Answered(NetResponse(response.status.value, headersOf(response), bytes))
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
        if (outcome !is Attempt.Failed || outcome.final || attempt >= config.retry.maxAttempts) return null
        val retryable =
            when (outcome.error) {
                is NetError.RateLimited -> true
                is NetError.Server, is NetError.Offline -> request.method.idempotent
                is NetError.Unauthorized, is NetError.Parse, is NetError.InvalidRequest, is NetError.TooLarge,
                is NetError.Protocol,
                -> false
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

/** [response]'s headers, without the one the engine uses to carry a 503's `Retry-After` past OkHttp. */
private fun headersOf(response: HttpResponse): Map<String, List<String>> =
    response.headers
        .entries()
        .filterNot { it.key.equals(ORIGINAL_RETRY_AFTER, ignoreCase = true) }
        .associate { it.key to it.value }
