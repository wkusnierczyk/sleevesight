package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class RateLimitTest {
    @Test
    fun `MusicBrainz requests start at least a second apart, even when sent together`() =
        runTest {
            val times = mutableListOf<Long>()
            val client =
                mockClient {
                    times += testScheduler.currentTime
                    respond("{}")
                }

            (1..4)
                .map { async { client.send(NetRequest("https://musicbrainz.org/ws/2/recording/$it")) } }
                .awaitAll()

            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times.sorted())
        }

    @Test
    fun `a subdomain shares its domain's limit`() =
        runTest {
            val times = mutableListOf<Long>()
            val client =
                mockClient {
                    times += testScheduler.currentTime
                    respond("{}")
                }

            client.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))
            client.send(NetRequest("https://beta.musicbrainz.org/ws/2/artist/b"))

            assertEquals(listOf(0L, 1_000L), times)
        }

    @Test
    fun `hosts without a policy are not held back`() =
        runTest {
            val times = mutableListOf<Long>()
            val client =
                mockClient {
                    times += testScheduler.currentTime
                    respond("{}")
                }

            repeat(3) { client.send(NetRequest("https://en.wikipedia.org/w/api.php")) }
            client.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))

            assertEquals(listOf(0L, 0L, 0L, 0L), times)
        }

    @Test
    fun `retries wait for the limit too`() =
        runTest {
            val times = mutableListOf<Long>()
            val config =
                NetConfig(
                    appVersion = "test",
                    retry = RetryPolicy(initialDelay = 100.milliseconds),
                )
            val client =
                mockClient(config) {
                    times += testScheduler.currentTime
                    respond("", HttpStatusCode.ServiceUnavailable)
                }

            client.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))

            assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 1_000L }, times.toString())
        }
}
