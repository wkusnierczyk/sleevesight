package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val client =
                mockClient(NetConfig(appVersion = "0.5.0")) { request ->
                    agent = request.headers[HttpHeaders.UserAgent]
                    respond("")
                }

            client.send(get)

            assertEquals("Sleevesight/0.5.0 ( https://github.com/wkusnierczyk/sleevesight )", agent)
        }

    @Test
    fun `a success returns the status, headers and body`() =
        runTest {
            val client =
                mockClient { respond("hello", HttpStatusCode.OK, headersOf("X-Thing", "1")) }

            val response = assertIs<NetResult.Success<NetResponse>>(client.send(get)).value

            assertEquals(200, response.status)
            assertEquals("hello", response.body)
            assertEquals("1", response.header("x-thing"))
        }

    @Test
    fun `a 404 is a response for the caller to read, not an error`() =
        runTest {
            val client = mockClient { respond("", HttpStatusCode.NotFound) }

            val response = assertIs<NetResult.Success<NetResponse>>(client.send(get)).value

            assertEquals(404, response.status)
            assertEquals(false, response.isSuccess)
        }

    @Test
    fun `401 and 403 are Unauthorized and never retried`() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                var calls = 0
                val client =
                    mockClient {
                        calls++
                        respond("", status)
                    }

                assertEquals(NetResult.Failure(NetError.Unauthorized(status.value)), client.send(get))
                assertEquals(1, calls)
            }
        }

    @Test
    fun `a 5xx is retried for GET, then reported as Server`() =
        runTest {
            var calls = 0
            val client =
                mockClient {
                    calls++
                    respond("", HttpStatusCode.BadGateway)
                }

            assertEquals(NetResult.Failure(NetError.Server(502)), client.send(get))
            assertEquals(3, calls)
        }

    @Test
    fun `a retry that succeeds returns the success`() =
        runTest {
            var calls = 0
            val client =
                mockClient {
                    calls++
                    if (calls == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok")
                }

            assertEquals("ok", assertIs<NetResult.Success<NetResponse>>(client.send(get)).value.body)
            assertEquals(2, calls)
        }

    @Test
    fun `a POST is not retried after a 5xx or a lost connection, so a paid call is not sent twice`() =
        runTest {
            var calls = 0
            val server =
                mockClient {
                    calls++
                    respond("", HttpStatusCode.InternalServerError)
                }
            assertEquals(NetResult.Failure(NetError.Server(500)), server.send(post))
            assertEquals(1, calls)

            calls = 0
            val offline =
                mockClient {
                    calls++
                    throw IOException("connection reset")
                }
            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(offline.send(post)).error)
            assertEquals(1, calls)
        }

    @Test
    fun `a 429 is retried after its Retry-After, for any method`() =
        runTest {
            val times = mutableListOf<Long>()
            val client =
                mockClient {
                    times += testScheduler.currentTime
                    if (times.size == 1) {
                        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
                    } else {
                        respond("ok")
                    }
                }

            assertIs<NetResult.Success<NetResponse>>(client.send(post))
            assertEquals(3_000L, times[1] - times[0])
        }

    @Test
    fun `a Retry-After longer than the retries allow ends them with RateLimited`() =
        runTest {
            var calls = 0
            val client =
                mockClient {
                    calls++
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600"))
                }

            assertEquals(NetResult.Failure(NetError.RateLimited(3600.seconds)), client.send(get))
            assertEquals(1, calls)
        }

    @Test
    fun `no response is Offline, after the retries`() =
        runTest {
            var calls = 0
            val client =
                mockClient {
                    calls++
                    throw IOException("unknown host")
                }

            assertEquals(NetResult.Failure(NetError.Offline("IOException")), client.send(get))
            assertEquals(3, calls)
        }

    @Test
    fun `retries back off exponentially`() =
        runTest {
            val times = mutableListOf<Long>()
            val client =
                mockClient {
                    times += testScheduler.currentTime
                    respond("", HttpStatusCode.InternalServerError)
                }

            client.send(get)

            assertEquals(listOf(0L, 500L, 1_500L), times)
        }

    @Test
    fun `a parser failure is a Parse error`() =
        runTest {
            val client = mockClient { respond("not a number") }

            val result = client.fetch(get) { it.body.toInt() }

            assertIs<NetError.Parse>(assertIs<NetResult.Failure>(result).error)
        }

    @Test
    fun `fetch passes the parsed value through`() =
        runTest {
            val client = mockClient { respond("42") }

            assertEquals(NetResult.Success(42), client.fetch(get) { it.body.toInt() })
        }

    @Test
    fun `query, headers and body reach the server`() =
        runTest {
            var seen = ""
            val client =
                mockClient { request ->
                    seen = "${request.method.value} ${request.url} ${request.headers["Accept"]}"
                    respond("")
                }

            client.send(
                NetRequest(
                    "https://example.org/a",
                    query = mapOf("q" to "a b"),
                    headers =
                        mapOf(
                            "Accept" to "x/y",
                        ),
                ),
            )

            assertEquals("GET https://example.org/a?q=a+b x/y", seen)
        }
}
