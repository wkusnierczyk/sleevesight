package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class RateLimitTest {
    @Test
    fun `MusicBrainz requests start at least a second apart, even when sent together`() =
        runTest {
            val times = mutableListOf<Long>()
            withClient(handler = {
                times += testScheduler.currentTime
                respond("{}")
            }) { client ->
                (1..4)
                    .map { async { client.send(NetRequest("https://musicbrainz.org/ws/2/recording/$it")) } }
                    .awaitAll()
            }

            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times.sorted())
        }

    @Test
    fun `a subdomain shares its domain's limit`() =
        runTest {
            val times = mutableListOf<Long>()
            withClient(handler = {
                times += testScheduler.currentTime
                respond("{}")
            }) { client ->
                client.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))
                client.send(NetRequest("https://beta.musicbrainz.org/ws/2/artist/b"))
            }

            assertEquals(listOf(0L, 1_000L), times)
        }

    @Test
    fun `host names match whatever their case or trailing dot`() =
        runTest {
            val times = mutableListOf<Long>()
            val config =
                NetConfig(
                    appVersion = "test",
                    hosts =
                        mapOf(
                            "MusicBrainz.org." to HostPolicy(1_000.milliseconds),
                        ),
                )
            withClient(config, handler = {
                times += testScheduler.currentTime
                respond("{}")
            }) { client ->
                listOf("https://MUSICBRAINZ.ORG/a", "https://musicbrainz.org./b", "https://musicbrainz.org/c")
                    .map { async { client.send(NetRequest(it)) } }
                    .awaitAll()
            }

            assertEquals(listOf(0L, 1_000L, 2_000L), times.sorted())
        }

    @Test
    fun `hosts without a policy are not held back`() =
        runTest {
            val times = mutableListOf<Long>()
            withClient(handler = {
                times += testScheduler.currentTime
                respond("{}")
            }) { client ->
                repeat(3) { client.send(NetRequest("https://en.wikipedia.org/w/api.php")) }
                client.send(NetRequest("https://musicbrainz.org/ws/2/artist/a"))
            }

            assertEquals(listOf(0L, 0L, 0L, 0L), times)
        }

    @Test
    fun `retries wait for the limit too`() =
        runTest {
            val times = mutableListOf<Long>()
            val config = NetConfig(appVersion = "test", retry = RetryPolicy(initialDelay = 100.milliseconds))
            val result =
                withClient(config, handler = {
                    times += testScheduler.currentTime
                    respond("", HttpStatusCode.ServiceUnavailable)
                }) { it.send(NetRequest("https://musicbrainz.org/ws/2/artist/a")) }

            assertEquals(NetResult.Failure(NetError.RateLimited(null)), result)
            assertEquals(listOf(0L, 1_000L, 2_000L), times)
        }

    @Test
    fun `each redirect hop waits for its target's limit`() =
        runTest {
            val times = mutableListOf<Long>()
            withClient(handler = { request ->
                times += testScheduler.currentTime
                if (request.url.host == "musicbrainz.org") {
                    respond(
                        "",
                        HttpStatusCode.MovedPermanently,
                        headersOf(HttpHeaders.Location, "https://beta.musicbrainz.org/x"),
                    )
                } else {
                    respond("{}")
                }
            }) { it.send(NetRequest("https://musicbrainz.org/ws/2/artist/a")) }

            assertEquals(listOf(0L, 1_000L), times)
        }
}
