package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A cancelled caller is cancelled: no `Offline`, nothing logged for it, and no slot of the limit lost. */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {
    private val musicBrainz = "https://musicbrainz.org/ws/2/artist/"

    @Test
    fun `a caller cancelled while waiting for the limit gives up its turn`() =
        runTest {
            val log = RecordingLog()
            val times = mutableListOf<Long>()
            mockClient(log = log, handler = { request ->
                times += testScheduler.currentTime
                respond(request.url.encodedPath)
            }).use { client ->
                launch { client.send(NetRequest("${musicBrainz}first")) }
                val waiting = launch { client.send(NetRequest("${musicBrainz}cancelled")) }
                advanceTimeBy(500)
                waiting.cancel()
                runCurrent()
                advanceTimeBy(100)
                val next = launch { client.send(NetRequest("${musicBrainz}next")) }
                next.join()

                assertTrue(waiting.isCancelled)
                assertEquals(listOf(0L, 1_000L), times)
                assertEquals(2, log.lines.size)
                assertFalse(log.lines.any { "cancelled" in it }, log.lines.toString())
            }
        }

    @Test
    fun `a caller cancelled during the backoff is not retried`() =
        runTest {
            val log = RecordingLog()
            var calls = 0
            mockClient(log = log, handler = {
                calls++
                respond("", HttpStatusCode.InternalServerError)
            }).use { client ->
                val job = launch { client.send(NetRequest("https://example.org/a")) }
                advanceTimeBy(250)
                job.cancel()
                job.join()

                assertTrue(job.isCancelled)
                assertEquals(1, calls)
                assertEquals(1, log.lines.size)
                assertTrue("retrying" in log.lines.single(), log.lines.single())
            }
        }

    @Test
    fun `a caller cancelled during the call gets no Offline, and the next caller keeps its schedule`() =
        runTest {
            val log = RecordingLog()
            val times = mutableListOf<Long>()
            mockClient(log = log, handler = { request ->
                times += testScheduler.currentTime
                if (request.url.encodedPath.endsWith("hangs")) awaitCancellation()
                respond("{}")
            }).use { client ->
                val hanging = launch { client.send(NetRequest("${musicBrainz}hangs")) }
                advanceTimeBy(500)
                hanging.cancel()
                hanging.join()
                advanceTimeBy(100)
                launch { client.send(NetRequest("${musicBrainz}next")) }.join()

                assertTrue(hanging.isCancelled)
                assertEquals(listOf(0L, 1_000L), times)
                assertEquals(1, log.lines.size)
                assertFalse(log.lines.any { "hangs" in it || "Offline" in it }, log.lines.toString())
            }
        }
}
