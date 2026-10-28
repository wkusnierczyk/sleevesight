package app.sleevesight.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the real OkHttp engine sends, against servers on the loopback interface (no internet): nothing
 * beyond what `:net`'s retry policy and rate limits decide.
 */
class OkHttpEngineTest {
    private val server = MockWebServer()
    private val other = MockWebServer()
    private val once = NetConfig(appVersion = "test", retry = RetryPolicy(maxAttempts = 1))
    private val twice = NetConfig(appVersion = "test", retry = RetryPolicy(maxAttempts = 2))

    @BeforeTest
    fun start() {
        server.start()
        other.start()
    }

    @AfterTest
    fun stop() {
        server.close()
        other.close()
    }

    private fun ok(body: String = "") =
        MockResponse
            .Builder()
            .code(200)
            .body(body)
            .build()

    private fun dropped() = MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()

    @Test
    fun `a 503's Retry-After reaches net, not OkHttp, whatever its form`() =
        runTest {
            for ((value, retried) in listOf("0" to true, "00" to true, "000" to true, "2147483648" to false)) {
                val start = server.requestCount
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(503)
                        .addHeader("Retry-After", value)
                        .build(),
                )
                server.enqueue(ok("after"))
                val log = RecordingLog()

                val result =
                    KtorNetClient(
                        twice,
                        okHttpEngine(),
                        log,
                    ).use { it.send(NetRequest(server.url("/a").toString())) }

                // Real time on a real engine: the duration is the one part not fixed.
                val start503 =
                    Regex.escape(
                        "GET http://${server.hostName}:${server.port}/a {0 headers} -> Server(status=503) in ",
                    )
                val first = Regex("^$start503\\d+ ms \\(attempt 1\\)" + if (retried) ", retrying in 0s$" else "$")
                assertTrue(first.matches(log.lines.first()), "$value: ${log.lines.first()}")
                assertEquals(if (retried) 2 else 1, log.lines.size, value)
                if (retried) {
                    assertEquals("after", assertIs<NetResult.Success<NetResponse>>(result).value.body, value)
                    assertEquals(2, server.requestCount - start, value)
                } else {
                    assertEquals(NetResult.Failure(NetError.Server(503)), result, value)
                    assertEquals(1, server.requestCount - start, value)
                }
            }
        }

    @Test
    fun `a dropped pooled connection is not retried by the engine`() =
        runTest {
            server.enqueue(ok("one"))
            server.enqueue(dropped())
            server.enqueue(ok("three"))

            val result =
                KtorNetClient(once, okHttpEngine(), NetLog.NONE).use { client ->
                    client.send(NetRequest(server.url("/one").toString()))
                    client.send(NetRequest(server.url("/two").toString()))
                }

            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a POST body is one-shot, so even OkHttp's connection retry cannot resend it`() =
        runTest {
            server.enqueue(ok("one"))
            server.enqueue(dropped())
            server.enqueue(ok("three"))

            val result =
                KtorNetClient(once, okHttpEngine { retryOnConnectionFailure(true) }, NetLog.NONE).use { client ->
                    client.send(NetRequest(server.url("/one").toString()))
                    client.send(
                        NetRequest(
                            server.url("/paid").toString(),
                            NetMethod.POST,
                            body = NetBody("{}", "application/json"),
                        ),
                    )
                }

            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `without the guard, OkHttp would resend a bodiless GET on a dropped pooled connection`() =
        runTest {
            server.enqueue(ok("one"))
            server.enqueue(dropped())
            server.enqueue(ok("three"))

            val result =
                KtorNetClient(once, okHttpEngine { retryOnConnectionFailure(true) }, NetLog.NONE).use { client ->
                    client.send(NetRequest(server.url("/one").toString()))
                    client.send(NetRequest(server.url("/two").toString()))
                }

            assertEquals("three", assertIs<NetResult.Success<NetResponse>>(result).value.body)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `the guarded client speaks HTTP 1_1 only, and neither redirects nor retries on its own`() {
        val client = OkHttpClient.Builder().apply { applyGuards() }.build()

        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `a 407 from the origin is a Protocol error, not retried`() =
        runTest {
            repeat(3) { server.enqueue(MockResponse.Builder().code(407).build()) }

            val result =
                KtorNetClient(NetConfig(appVersion = "test"), okHttpEngine(), NetLog.NONE).use {
                    it.send(NetRequest(server.url("/a").toString()))
                }

            assertEquals(NetResult.Failure(NetError.Protocol("ProtocolException")), result)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a redirect to another server is followed by net only for a request without credentials`() =
        runTest {
            val redirect =
                MockResponse
                    .Builder()
                    .code(
                        302,
                    ).addHeader("Location", other.url("/landed").toString())
                    .build()
            server.enqueue(redirect)
            server.enqueue(redirect)
            other.enqueue(ok("ok"))

            KtorNetClient(once, okHttpEngine(), NetLog.NONE).use { client ->
                val plain = client.send(NetRequest(server.url("/start").toString(), headers = mapOf("Accept" to "x/y")))
                val keyed =
                    client.send(
                        NetRequest(
                            server.url("/start").toString(),
                            secrets =
                                NetSecrets(
                                    headers =
                                        mapOf(
                                            "x-api-key" to "sk-ant-loopback-SECRET",
                                        ),
                                ),
                        ),
                    )

                assertEquals("ok", assertIs<NetResult.Success<NetResponse>>(plain).value.body)
                assertEquals(302, assertIs<NetResult.Success<NetResponse>>(keyed).value.status)
                assertEquals(2, server.requestCount)
                assertEquals(1, other.requestCount)
            }
        }

    @Test
    fun `a chunked stream arrives line by line through the real engine, sent once`() =
        runTest {
            val events = "event: a\ndata: {\"x\":1}\n\nevent: b\r\ndata: é\r\n\n"
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .chunkedBody(events, 5)
                    .build(),
            )
            val lines = mutableListOf<String>()
            val post = NetRequest(server.url("/s").toString(), NetMethod.POST, body = NetBody("{}", "application/json"))

            val result =
                KtorNetClient(
                    twice,
                    okHttpEngine(),
                    NetLog.NONE,
                ).use { it.stream(post) { line -> lines += line } }

            assertIs<NetResult.Success<NetResponse>>(result)
            assertEquals(listOf("event: a", "data: {\"x\":1}", "", "event: b", "data: é", ""), lines)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a stream whose body is cut off is Offline and not repeated, even for a GET`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .chunkedBody("data: 1\n".repeat(1000), 64)
                    .onResponseBody(SocketEffect.ShutdownConnection)
                    .build(),
            )
            server.enqueue(ok("data: again\n"))

            val result =
                KtorNetClient(twice, okHttpEngine(), NetLog.NONE).use {
                    it.stream(NetRequest(server.url("/s").toString())) { }
                }

            val lost = assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertFalse(lost.timedOut, lost.reason)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a request timeout bounds a stream, and after its first line it ends Offline, sent once, logged once`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("first\n" + "x".repeat(60))
                    .throttleBody(6, 1, TimeUnit.SECONDS)
                    .build(),
            )
            val log = RecordingLog()
            val lines = mutableListOf<String>()
            val slow = NetRequest(server.url("/s").toString(), timeout = 1500.milliseconds)

            val result = KtorNetClient(twice, okHttpEngine(), log).use { it.stream(slow) { line -> lines += line } }

            val late = assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertEquals("HttpRequestTimeoutException", late.reason)
            assertTrue(late.timedOut)
            assertEquals(listOf("first"), lines)
            assertEquals(1, server.requestCount)
            assertEquals(1, log.lines.size, log.lines.joinToString("\n"))
        }

    @Test
    fun `a caller that cancels mid-stream stops it, with no retry`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("first\n" + "x".repeat(60))
                    .throttleBody(6, 1, TimeUnit.SECONDS)
                    .build(),
            )
            server.enqueue(ok("again\n"))
            val firstLine = CompletableDeferred<Unit>()
            val lines = mutableListOf<String>()

            KtorNetClient(twice, okHttpEngine(), NetLog.NONE).use { client ->
                val job =
                    launch {
                        client.stream(NetRequest(server.url("/s").toString())) { line ->
                            lines += line
                            firstLine.complete(Unit)
                        }
                    }
                firstLine.await()
                job.cancelAndJoin()
            }

            assertEquals(listOf("first"), lines)
            assertEquals(1, server.requestCount)
        }
}
