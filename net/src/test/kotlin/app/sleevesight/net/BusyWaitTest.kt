package app.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A rate-limited answer whose `Retry-After` is zero waits the backoff (#1120), and a host's allowance
 * for waiting out a busy host (`HostPolicy.busyWait`) retries it while each wait, and the turn at the
 * host after it, fit in what is left, shared by the requests drawing on it; with none, a single try.
 * MusicBrainz says "retry at once" with each refusal; three attempts a second apart all met one.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class BusyWaitTest {
    private val busyNow = headersOf(HttpHeaders.RetryAfter, "0")

    /**
     * A host whose 503 is its limit, with no interval or jitter of its own, so each wait shows as it is;
     * each request has an allowance of its own of [wait], read when it is asked for.
     */
    private fun TestScope.busyHost(wait: () -> Duration): NetConfig =
        drawingOn { TestBusyWait(wait(), testScheduler.timeSource) }

    /** The same host, its requests drawing on the allowance [allowance] gives. */
    private fun drawingOn(allowance: suspend () -> BusyWait): NetConfig =
        NetConfig(
            appVersion = "test",
            hosts = mapOf("busy.example" to HostPolicy(Duration.ZERO, rateLimitedOn503 = true, busyWait = allowance)),
        )

    /** The times of the attempts at [url], each refused as busy until [refusals] have been. */
    @Suppress("RedundantSuspendModifier") // Detekt misses `send` inside `use`; the compiler needs it.
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
            val allowance: suspend () -> BusyWait = { TestBusyWait(15.seconds, testScheduler.timeSource) }
            val musicBrainz = NetConfig.DEFAULT_HOSTS.getValue(NetConfig.MUSICBRAINZ).copy(busyWait = allowance)
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

    @Test
    fun `a retry whose turn at the host would come past the allowance is not sent`() =
        runTest {
            val busy = TestBusyWait(2.seconds, testScheduler.timeSource)
            val musicBrainz = NetConfig.DEFAULT_HOSTS.getValue(NetConfig.MUSICBRAINZ).copy(busyWait = { busy })
            val config = NetConfig(appVersion = "test", hosts = mapOf(NetConfig.MUSICBRAINZ to musicBrainz))
            val refused = mutableListOf<Long>()
            val others = mutableListOf<Long>()
            val engine =
                mockEngine { request ->
                    if (request.url.encodedPath.endsWith("/busy")) {
                        refused += testScheduler.currentTime
                        respond("", HttpStatusCode.ServiceUnavailable, busyNow)
                    } else {
                        others += testScheduler.currentTime
                        respond("ok")
                    }
                }
            val client = KtorNetClient(config, engine, NetLog.NONE, testScheduler.timeSource, virtualClock(), Random(0))
            val result =
                client.use {
                    val busyOne = async { it.send(NetRequest("https://musicbrainz.org/ws/2/busy")) }
                    // Three more, queued at the host's one a second before the busy one's retry.
                    val other = { n: Int -> NetRequest("https://musicbrainz.org/ws/2/other/$n") }
                    val queued = (1..3).map { n -> async { it.send(other(n)) } }
                    queued.awaitAll()
                    busyOne.await()
                }

            // Its turn would have come at 4 s, past the 2 s allowed: only the first try was sent.
            assertEquals(listOf(0L), refused)
            assertEquals(listOf(1_000L, 2_000L, 3_000L), others)
            assertEquals(NetResult.Failure(NetError.RateLimited(Duration.ZERO)), result)
            assertTrue(busy.left == Duration.ZERO, "${busy.left}")
        }

    @Test
    fun `requests drawing on one allowance wait no longer in all than it allows`() =
        runTest {
            val shared = TestBusyWait(5_200.milliseconds, testScheduler.timeSource)
            val config = drawingOn { shared }
            // ½ s, 1 s and 2 s waited out: 3.5 s of the 5.2.
            val (first, answered) = attempts(config, refusals = 3)
            assertEquals(listOf(0L, 500L, 1_500L, 3_500L), first)
            assertEquals(200, (answered as NetResult.Success).value.status)

            // What is left, 1.7 s: ½ s, then 1 s, and no more.
            val (second, refused) = attempts(config)
            assertEquals(listOf(0L, 500L, 1_500L), second)
            assertEquals(NetResult.Failure(NetError.RateLimited(Duration.ZERO)), refused)
        }
}

/** An allowance of [total], its waits counted one after another, on the test's [time]. */
private class TestBusyWait(
    private val total: Duration,
    private val time: TimeSource,
) : BusyWait {
    private var spent = Duration.ZERO
    private var since: TimeMark? = null

    override val left: Duration
        get() = (total - spent - (since?.elapsedNow() ?: Duration.ZERO)).coerceAtLeast(Duration.ZERO)

    override suspend fun <T> waiting(block: suspend () -> T): T {
        val started = time.markNow()
        since = started
        try {
            return block()
        } finally {
            spent += started.elapsedNow()
            since = null
        }
    }
}
