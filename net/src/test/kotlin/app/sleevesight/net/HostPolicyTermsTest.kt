package app.sleevesight.net

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A host's own terms for clients (#997, LRCLIB's): its contact in the User-Agent, and a random wait of
 * up to its jitter added to a 429's `Retry-After`, never to another failure's backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class HostPolicyTermsTest {
    private val policy = HostPolicy(Duration.ZERO, contact = "https://sleevesight.app", retryJitter = 1.seconds)
    private val config = NetConfig(appVersion = "0.6.0", hosts = mapOf("lrclib.net" to policy))

    // Half the jitter, every time.
    private val half =
        object : Random() {
            override fun nextBits(bitCount: Int): Int = 0

            override fun nextDouble(): Double = 0.5
        }

    private fun TestScope.client(handler: MockRequestHandler) =
        KtorNetClient(config, mockEngine(handler), NetLog.NONE, testScheduler.timeSource, virtualClock(), half)

    @Test
    fun `a host with a contact of its own gets it in the User-Agent, the others the repository`() =
        runTest {
            val agents = mutableListOf<String?>()
            client { request ->
                agents += request.headers[HttpHeaders.UserAgent]
                respond("")
            }.use { net ->
                net.send(NetRequest("https://lrclib.net/api/get"))
                net.send(NetRequest("https://example.org/a"))
            }

            assertEquals<List<String?>>(
                listOf(
                    "Sleevesight/0.6.0 ( https://sleevesight.app )",
                    "Sleevesight/0.6.0 ( https://github.com/wkusnierczyk/sleevesight )",
                ),
                agents,
            )
        }

    @Test
    fun `a 429 is retried after its Retry-After and the host's jitter`() =
        runTest {
            val times = mutableListOf<Long>()
            client { _ ->
                times += testScheduler.currentTime
                if (times.size == 1) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3"))
                } else {
                    respond("ok")
                }
            }.use { net ->
                assertIs<NetResult.Success<NetResponse>>(net.send(NetRequest("https://lrclib.net/api/get")))
            }

            assertEquals(listOf(0L, 3_500L), times)
        }

    @Test
    fun `a server error's backoff takes no jitter`() =
        runTest {
            val times = mutableListOf<Long>()
            client { _ ->
                times += testScheduler.currentTime
                if (times.size == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond("ok")
            }.use { net ->
                assertIs<NetResult.Success<NetResponse>>(net.send(NetRequest("https://lrclib.net/api/get")))
            }

            assertEquals(listOf(0L, 500L), times)
        }
}
