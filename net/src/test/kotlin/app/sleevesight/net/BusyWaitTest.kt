package app.sleevesight.net

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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A rate-limited answer whose `Retry-After` is zero waits the backoff (#1120), and a host's *Wait when
 * busy* (`HostPolicy.waitWhenBusy`) retries it until that long has passed, or not at all at zero.
 * MusicBrainz says "retry at once" with each refusal; three attempts a second apart all met one.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class BusyWaitTest {
    private val busyNow = headersOf(HttpHeaders.RetryAfter, "0")

    /** A host whose 503 is its limit, with no interval or jitter of its own, so each wait shows as it is. */
    private fun busyHost(wait: () -> Duration): NetConfig =
        NetConfig(
            appVersion = "test",
            hosts = mapOf("busy.example" to HostPolicy(Duration.ZERO, rateLimitedOn503 = true, waitWhenBusy = wait)),
        )

    /** The times of the attempts at [url], each refused as busy until [refusals] have been. */
    private suspend fun TestScope.attempts(
        config: NetConfig,
        url: String = "https://busy.example/a",
        refusals: Int = Int.MAX_VALUE,
        random: Random = Random(0),
    ): Pair<List<Long>, NetResult<NetResponse>> {
        val times = mutableListOf<Long>()
        val engine =
            mockEngine {
                times += testScheduler.currentTime
                if (times.size <= refusals) respond("", HttpStatusCode.ServiceUnavailable, busyNow) else respond("ok")
            }
        val result =
            KtorNetClient(config, engine, NetLog.NONE, testScheduler.timeSource, virtualClock(), random).use {
                it.send(NetRequest(url))
            }
        // From the first attempt, whenever the test's virtual time stood when it began.
        return times.map { it - times.first() } to result
    }

    @Test
    fun `a host busy three times with a Retry-After of zero is retried with growing waits and answers`() =
        runTest {
            val (times, result) = attempts(busyHost { 15.seconds }, refusals = 3)

            assertEquals(200, (result as NetResult.Success).value.status)
            // ½ s, then 1 s, then 2 s: the backoff, not at once.
            assertEquals(listOf(0L, 500L, 1_500L, 3_500L), times)
        }

    @Test
    fun `MusicBrainz busy three times is retried with growing waits, a second apart at least, and answers`() =
        runTest {
            val musicBrainz =
                NetConfig.DEFAULT_HOSTS
                    .getValue(
                        NetConfig.MUSICBRAINZ,
                    ).copy(waitWhenBusy = { 15.seconds })
            val config = NetConfig(appVersion = "test", hosts = mapOf(NetConfig.MUSICBRAINZ to musicBrainz))
            val (times, result) =
                attempts(config, "https://musicbrainz.org/ws/2/recording?query=x", refusals = 3, random = Random(1))

            assertEquals(200, (result as NetResult.Success).value.status)
            assertEquals(4, times.size)
            val gaps = times.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it >= 1_000L }, "$gaps")
            assertTrue(gaps.zipWithNext().all { (a, b) -> b > a }, "$gaps")
            assertTrue(times.last() <= 15_000L, "$times")
        }

    @Test
    fun `a host that stays busy is asked again until the next try would start past the wait`() =
        runTest {
            val (fifteen, refused) = attempts(busyHost { 15.seconds })
            // The next wait, 8 s, would start a sixth try at 15.5 s.
            assertEquals(listOf(0L, 500L, 1_500L, 3_500L, 7_500L), fifteen)
            assertEquals(NetResult.Failure(NetError.RateLimited(Duration.ZERO)), refused)

            // Past the retries' cap of 10 s a wait stays at it, and the attempts are not counted.
            val (thirty, _) = attempts(busyHost { 30.seconds })
            assertEquals(listOf(0L, 500L, 1_500L, 3_500L, 7_500L, 15_500L, 25_500L), thirty)
        }

    @Test
    fun `Don't wait makes one try, and the wait is read for each request`() =
        runTest {
            var wait = Duration.ZERO
            val config = busyHost { wait }
            val (once, result) = attempts(config, refusals = 3)
            assertEquals(listOf(0L), once)
            assertEquals(NetResult.Failure(NetError.RateLimited(Duration.ZERO)), result)

            wait = 15.seconds
            val (again, answered) = attempts(config, refusals = 3)
            assertEquals(4, again.size)
            assertEquals(200, (answered as NetResult.Success).value.status)
        }

    @Test
    fun `without a wait when busy, a Retry-After of zero takes the backoff and the attempts as before`() =
        runTest {
            val config =
                NetConfig(
                    appVersion = "test",
                    hosts = mapOf("busy.example" to HostPolicy(Duration.ZERO, rateLimitedOn503 = true)),
                )
            val (times, result) = attempts(config)

            assertEquals(listOf(0L, 500L, 1_500L), times)
            assertEquals(NetResult.Failure(NetError.RateLimited(Duration.ZERO)), result)
        }

    @Test
    fun `a failure other than busy keeps the attempts, wait when busy or not`() =
        runTest {
            val times = mutableListOf<Long>()
            val result =
                withClient(busyHost { 1000.seconds }, handler = {
                    times += testScheduler.currentTime
                    respond("", HttpStatusCode.BadGateway)
                }) { it.send(NetRequest("https://busy.example/a")) }

            assertEquals(NetResult.Failure(NetError.Server(502)), result)
            assertEquals(listOf(0L, 500L, 1_500L), times)
        }

    @Test
    fun `a positive Retry-After is waited as the server asks`() =
        runTest {
            val times = mutableListOf<Long>()
            var calls = 0
            withClient(busyHost { 15.seconds }, handler = {
                times += testScheduler.currentTime
                calls++
                if (calls == 1) {
                    respond("", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "3"))
                } else {
                    respond("ok")
                }
            }) { it.send(NetRequest("https://busy.example/a")) }

            assertEquals(listOf(0L, 3_000L), times)
        }
}
