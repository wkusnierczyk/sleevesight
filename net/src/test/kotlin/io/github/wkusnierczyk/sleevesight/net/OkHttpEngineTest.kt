package io.github.wkusnierczyk.sleevesight.net

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What the real OkHttp engine sends, against servers on the loopback interface (no internet): nothing
 * beyond what `:net`'s retry policy and rate limits decide.
 */
class OkHttpEngineTest {
    private val server = MockWebServer()
    private val other = MockWebServer()
    private val once = NetConfig(appVersion = "test", retry = RetryPolicy(maxAttempts = 1))

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

    private fun client(config: NetConfig = once) = KtorNetClient(config, okHttpEngine(), NetLog.NONE)

    @Test
    fun `a 503 with Retry-After 0 is not repeated by the engine`() =
        runTest {
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(503)
                        .addHeader("Retry-After", "0")
                        .build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(200).build())

            val get = client().use { it.send(NetRequest(server.url("/a").toString())) }
            val post =
                client().use {
                    it.send(
                        NetRequest(
                            server.url("/b").toString(),
                            NetMethod.POST,
                            body = NetBody("{}", "application/json"),
                        ),
                    )
                }

            assertEquals(NetResult.Failure(NetError.Server(503)), get)
            assertEquals(NetResult.Failure(NetError.Server(503)), post)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a POST whose connection drops is not resent`() =
        runTest {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
            server.enqueue(MockResponse.Builder().code(200).build())

            val result =
                client(NetConfig(appVersion = "test")).use {
                    it.send(
                        NetRequest(
                            server.url("/paid").toString(),
                            NetMethod.POST,
                            body = NetBody("{}", "application/json"),
                        ),
                    )
                }

            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a redirect to another server is followed by net, without the key`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", other.url("/landed").toString())
                    .build(),
            )
            other.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("ok")
                    .build(),
            )

            val result =
                client().use {
                    it.send(
                        NetRequest(
                            server.url("/start").toString(),
                            secrets = NetSecrets(headers = mapOf("x-api-key" to "sk-ant-loopback-SECRET")),
                        ),
                    )
                }

            assertEquals("ok", assertIs<NetResult.Success<NetResponse>>(result).value.body)
            assertEquals("sk-ant-loopback-SECRET", server.takeRequest().headers["x-api-key"])
            assertNull(other.takeRequest().headers["x-api-key"])
            assertEquals(1, server.requestCount)
        }
}
