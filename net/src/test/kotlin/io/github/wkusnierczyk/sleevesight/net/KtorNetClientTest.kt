package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class KtorNetClientTest {
    private val get = NetRequest("https://example.org/a")
    private val post = NetRequest("https://example.org/a", NetMethod.POST, body = NetBody("{}", "application/json"))

    @Test
    fun `the User-Agent names the app, its version and the repository as contact`() =
        runTest {
            var agent: String? = null
            withClient(NetConfig(appVersion = "0.5.0"), handler = { request ->
                agent = request.headers[HttpHeaders.UserAgent]
                respond("")
            }) { it.send(get) }

            assertEquals("Sleevesight/0.5.0 ( https://github.com/wkusnierczyk/sleevesight )", agent)
        }

    @Test
    fun `a success returns the status, headers and body`() =
        runTest {
            val response =
                withClient(handler = { respond("hello", HttpStatusCode.OK, headersOf("X-Thing", "1")) }) {
                    assertIs<NetResult.Success<NetResponse>>(it.send(get)).value
                }

            assertEquals(200, response.status)
            assertEquals("hello", response.body)
            assertEquals("1", response.header("x-thing"))
        }

    @Test
    fun `a binary body arrives as bytes`() =
        runTest {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x00, 0xff.toByte())
            val response =
                withClient(handler = { respond(png) }) { assertIs<NetResult.Success<NetResponse>>(it.send(get)).value }

            assertContentEquals(png, response.bytes)
        }

    @Test
    fun `a POST sends its body and media type`() =
        runTest {
            var seen = ""
            withClient(handler = { request ->
                seen = "${request.body.contentType} ${request.body.toByteArray().decodeToString()}"
                respond("")
            }) {
                it.send(
                    NetRequest(
                        "https://example.org/a",
                        NetMethod.POST,
                        body = NetBody("""{"a":1}""", "application/json"),
                    ),
                )
            }

            assertEquals("""application/json {"a":1}""", seen)
        }

    @Test
    fun `a 404 is a response for the caller to read, not an error`() =
        runTest {
            val response =
                withClient(handler = { respond("", HttpStatusCode.NotFound) }) {
                    assertIs<NetResult.Success<NetResponse>>(it.send(get)).value
                }

            assertEquals(404, response.status)
            assertFalse(response.isSuccess)
        }

    @Test
    fun `401 and 403 are Unauthorized and never retried`() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                var calls = 0
                val result =
                    withClient(handler = {
                        calls++
                        respond("", status)
                    }) { it.send(get) }

                assertEquals(NetResult.Failure(NetError.Unauthorized(status.value)), result)
                assertEquals(1, calls)
            }
        }

    @Test
    fun `a 5xx is retried for GET, then reported as Server`() =
        runTest {
            var calls = 0
            val result =
                withClient(handler = {
                    calls++
                    respond("", HttpStatusCode.BadGateway)
                }) { it.send(get) }

            assertEquals(NetResult.Failure(NetError.Server(502)), result)
            assertEquals(3, calls)
        }

    @Test
    fun `a 503 from a host with a rate limit is RateLimited`() =
        runTest {
            val result =
                withClient(handler = { respond("", HttpStatusCode.ServiceUnavailable) }) {
                    it.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))
                }

            assertEquals(NetResult.Failure(NetError.RateLimited(null)), result)
        }

    @Test
    fun `a retry that succeeds returns the success`() =
        runTest {
            var calls = 0
            val result =
                withClient(handler = {
                    calls++
                    if (calls == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok")
                }) { it.send(get) }

            assertEquals("ok", assertIs<NetResult.Success<NetResponse>>(result).value.body)
            assertEquals(2, calls)
        }

    @Test
    fun `a POST is not retried after a 5xx or a lost connection, so a paid call is not sent twice`() =
        runTest {
            var calls = 0
            val server =
                withClient(handler = {
                    calls++
                    respond("", HttpStatusCode.InternalServerError)
                }) { it.send(post) }
            assertEquals(NetResult.Failure(NetError.Server(500)), server)
            assertEquals(1, calls)

            calls = 0
            val offline =
                withClient(handler = {
                    calls++
                    throw IOException("connection reset")
                }) { it.send(post) }
            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(offline).error)
            assertEquals(1, calls)
        }

    @Test
    fun `a 429 is retried after its Retry-After, for any method`() =
        runTest {
            val times = retryTimes("3", post)

            assertEquals(listOf(0L, 3_000L), times)
        }

    @Test
    fun `a Retry-After date is waited for`() =
        runTest {
            val times = retryTimes(GMTDate(EPOCH_MILLIS + 4_000).toHttpDate(), get)

            assertEquals(listOf(0L, 4_000L), times)
        }

    @Test
    fun `a Retry-After date in the past, or negative seconds, is no wait`() =
        runTest {
            assertEquals(listOf(0L, 0L), retryTimes(GMTDate(EPOCH_MILLIS - 60_000).toHttpDate(), get))
            assertEquals(listOf(0L, 0L), retryTimes("-5", get))
        }

    @Test
    fun `an unreadable Retry-After falls back to the backoff`() =
        runTest {
            assertEquals(listOf(0L, 500L), retryTimes("soon", get))
        }

    @Test
    fun `a Retry-After longer than the retries allow ends them with RateLimited, in either form`() =
        runTest {
            for (value in listOf("3600", GMTDate(EPOCH_MILLIS + 3_600_000).toHttpDate())) {
                var calls = 0
                val result =
                    withClient(handler = {
                        calls++
                        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, value))
                    }) { it.send(get) }

                assertEquals(NetResult.Failure(NetError.RateLimited(3600.seconds)), result, value)
                assertEquals(1, calls)
            }
        }

    @Test
    fun `no response is Offline, after the retries`() =
        runTest {
            var calls = 0
            val result =
                withClient(handler = {
                    calls++
                    throw IOException("unknown host")
                }) { it.send(get) }

            assertEquals(NetResult.Failure(NetError.Offline("IOException")), result)
            assertEquals(3, calls)
        }

    @Test
    fun `retries back off exponentially`() =
        runTest {
            val times = mutableListOf<Long>()
            withClient(handler = {
                times += testScheduler.currentTime
                respond("", HttpStatusCode.InternalServerError)
            }) { it.send(get) }

            assertEquals(listOf(0L, 500L, 1_500L), times)
        }

    @Test
    fun `a request's own timeout replaces the default`() =
        runTest {
            val config = NetConfig(appVersion = "test", retry = RetryPolicy(maxAttempts = 1))
            val request = NetRequest("https://example.org/a", timeout = 1.seconds)
            val result =
                withClient(config, handler = {
                    delay(5_000)
                    respond("late")
                }) { it.send(request) }

            assertEquals(NetResult.Failure(NetError.Offline("HttpRequestTimeoutException")), result)
        }

    @Test
    fun `fetch passes the parsed value through, and a parser failure is a Parse error`() =
        runTest {
            withClient(handler = { respond("42") }) { client ->
                assertEquals(NetResult.Success(42), client.fetch(get) { it.body.toInt() })
                assertEquals(
                    NetResult.Failure(NetError.Parse("NumberFormatException")),
                    client.fetch(get) { (it.body + "x").toInt() },
                )
            }
        }

    @Test
    fun `query, headers and body reach the server`() =
        runTest {
            var seen = ""
            withClient(handler = { request ->
                seen = "${request.method.value} ${request.url} ${request.headers["Accept"]}"
                respond("")
            }) {
                it.send(
                    NetRequest(
                        "https://example.org/a",
                        query = mapOf("q" to "a b"),
                        headers =
                            mapOf(
                                "Accept" to "x/y",
                            ),
                    ),
                )
            }

            assertEquals("GET https://example.org/a?q=a+b x/y", seen)
        }

    @Test
    fun `a redirected POST is returned, not followed`() =
        runTest {
            var calls = 0
            val result =
                withClient(handler = {
                    calls++
                    respond(
                        "",
                        HttpStatusCode.TemporaryRedirect,
                        headersOf(HttpHeaders.Location, "https://example.org/b"),
                    )
                }) { it.send(post) }

            assertEquals(307, assertIs<NetResult.Success<NetResponse>>(result).value.status)
            assertEquals(1, calls)
        }

    @Test
    fun `at most five redirects are followed`() =
        runTest {
            var calls = 0
            val result =
                withClient(handler = {
                    calls++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/again"))
                }) { it.send(get) }

            assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status)
            assertEquals(6, calls)
        }

    @Test
    fun `closing the client closes its engine`() =
        runTest {
            val engine = mockEngine { respond("") }
            val client = KtorNetClient(NetConfig(appVersion = "test"), engine, NetLog.NONE, testScheduler.timeSource)

            client.close()
            engine.coroutineContext.job.join()

            assertFalse(engine.coroutineContext.job.isActive)
        }

    /** The virtual times of a request answered 429 with [retryAfter], then 200. */
    private suspend fun kotlinx.coroutines.test.TestScope.retryTimes(
        retryAfter: String,
        request: NetRequest,
    ): List<Long> {
        val times = mutableListOf<Long>()
        withClient(handler = {
            times += testScheduler.currentTime
            if (times.size == 1) {
                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, retryAfter))
            } else {
                respond("ok")
            }
        }) { assertIs<NetResult.Success<NetResponse>>(it.send(request)) }
        return times
    }
}
