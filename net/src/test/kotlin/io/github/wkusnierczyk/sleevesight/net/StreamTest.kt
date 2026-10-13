package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class StreamTest {
    private val post =
        NetRequest(
            "https://api.example.org/v1/messages",
            NetMethod.POST,
            secrets = NetSecrets(headers = mapOf("x-api-key" to "sk-secret-value")),
            body = NetBody("{}", "application/json"),
        )
    private val get = NetRequest("https://example.org/feed")

    private suspend fun NetClient.lines(request: NetRequest): Pair<NetResult<NetResponse>, List<String>> {
        val lines = mutableListOf<String>()
        return stream(request) { lines += it } to lines
    }

    @Test
    fun `lines arrive as the body does, split at LF, CRLF and CR, across chunk boundaries`() =
        runTest {
            val body = ByteChannel()
            val chunks =
                listOf(
                    "event: a\r".encodeToByteArray(),
                    "\ndata: é".encodeToByteArray().let { it.copyOf(it.size - 1) },
                    byteArrayOf("é".encodeToByteArray().last()) + "\n\ndata: x\rdata: last".encodeToByteArray(),
                )
            val seen = mutableListOf<String>()
            withClient(handler = { respond(body, HttpStatusCode.OK) }) { client ->
                val result = async { client.stream(get) { seen += it } }
                body.writeFully(chunks[0])
                body.flush()
                runCurrent()
                assertEquals(listOf("event: a"), seen, "a line arrives before the body ends; a CR ends it")
                body.writeFully(chunks[1])
                body.flush()
                runCurrent()
                assertEquals(listOf("event: a"), seen, "the LF after the CR is no line; half a character waits")
                body.writeFully(chunks[2])
                body.close()
                val response = assertIs<NetResult.Success<NetResponse>>(result.await()).value
                assertEquals(200, response.status)
                assertContentEquals(ByteArray(0), response.bytes)
            }
            assertEquals(listOf("event: a", "data: é", "", "data: x", "data: last"), seen)
        }

    @Test
    fun `a response that is not 2xx is read whole and returned, and no line is passed on`() =
        runTest {
            val (result, lines) =
                withClient(handler = {
                    respond("""{"type":"error"}""", HttpStatusCode.BadRequest, headersOf("X-Kind", "bad"))
                }) { it.lines(post) }
            val response = assertIs<NetResult.Success<NetResponse>>(result).value
            assertEquals(400, response.status)
            assertEquals("""{"type":"error"}""", response.body)
            assertEquals("bad", response.header("x-kind"))
            assertEquals(emptyList(), lines)
        }

    @Test
    fun `a 429 before the body is retried, even for a POST, and the stream then flows`() =
        runTest {
            var calls = 0
            val (result, lines) =
                withClient(handler = {
                    calls++
                    if (calls == 1) respond("", HttpStatusCode.TooManyRequests) else respond("one\ntwo\n")
                }) { it.lines(post) }
            assertIs<NetResult.Success<NetResponse>>(result)
            assertEquals(2, calls)
            assertEquals(listOf("one", "two"), lines)
        }

    @Test
    fun `a POST that fails before the body is sent once, as for send`() =
        runTest {
            var calls = 0
            val (result, _) =
                withClient(handler = {
                    calls++
                    respond("", HttpStatusCode.InternalServerError)
                }) { it.lines(post) }
            assertEquals(NetResult.Failure(NetError.Server(500)), result)
            assertEquals(1, calls)
        }

    @Test
    fun `a connection lost mid-stream is Offline after the lines before it, and never retried, even for GET`() =
        runTest {
            var calls = 0
            val log = RecordingLog()
            val delivered = CompletableDeferred<Unit>()
            val lines = mutableListOf<String>()
            val result =
                withClient(log = log, handler = {
                    calls++
                    val body = ByteChannel()
                    launch {
                        body.writeFully("data: 1\n".encodeToByteArray())
                        body.flush()
                        delivered.await()
                        body.cancel(IOException("connection reset"))
                    }
                    respond(body, HttpStatusCode.OK)
                }) { client ->
                    client.stream(get) {
                        lines += it
                        delivered.complete(Unit)
                    }
                }
            assertIs<NetError.Offline>(assertIs<NetResult.Failure>(result).error)
            assertEquals(listOf("data: 1"), lines)
            assertEquals(1, calls)
            assertEquals(1, log.lines.size)
        }

    @Test
    fun `a stream longer than maxBytes ends with TooLarge, declared or counted, and is not retried`() =
        runTest {
            val small =
                NetRequest("https://example.org/feed", maxBytes = 10)
            val counted =
                withClient(handler = { respond(ByteReadChannel("12345\n67890\nabc\n"), HttpStatusCode.OK) }) {
                    it.lines(small)
                }
            assertEquals(NetResult.Failure(NetError.TooLarge(10)), counted.first)
            val declared =
                withClient(handler = {
                    respond("12345\n67890\nabc\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "16"))
                }) { it.lines(small) }
            assertEquals(NetResult.Failure(NetError.TooLarge(10)), declared.first)
            assertEquals(emptyList(), declared.second)
        }

    @Test
    fun `what the line handler throws reaches the caller as it is, and ends the stream`() =
        runTest {
            class Stop : IllegalStateException("stop")
            for (thrown in listOf(Stop(), IOException("caller's own"), IllegalArgumentException("parse"))) {
                var seen = 0
                val caught =
                    assertFailsWith<Throwable> {
                        withClient(handler = { respond("a\nb\nc\n") }) { client ->
                            client.stream(post) {
                                seen++
                                throw thrown
                            }
                        }
                    }
                assertTrue(caught === thrown, "$thrown came back as $caught")
                assertEquals(1, seen)
            }
        }

    @Test
    fun `an attempt is logged once, when the stream ends, without the key`() =
        runTest {
            val log = RecordingLog()
            withClient(log = log, handler = { respond("data: x\n") }) { it.lines(post) }
            assertEquals(
                listOf("POST https://api.example.org/v1/messages {1 header} -> 200 in 0 ms (attempt 1)"),
                log.lines,
            )
        }

    @Test
    fun `an invalid request is refused before sending, as for send`() =
        runTest {
            var calls = 0
            val bad = NetRequest("https://example.org/a", secrets = NetSecrets(headers = mapOf("x-api-key" to "k\n")))
            val (result, _) =
                withClient(handler = {
                    calls++
                    respond("")
                }) { it.lines(bad) }
            assertEquals(NetResult.Failure(NetError.InvalidRequest("header")), result)
            assertEquals(0, calls)
        }

    @Test
    fun `the default stream, for fakes, splits a whole response the same way`() =
        runTest {
            val fake =
                object : NetClient {
                    override suspend fun send(request: NetRequest): NetResult<NetResponse> =
                        NetResult.Success(
                            NetResponse(200, mapOf("X-A" to listOf("1")), "a\r\nb\rc\n\nd".encodeToByteArray()),
                        )

                    override fun close() = Unit
                }
            val (result, lines) = fake.lines(get)
            assertEquals(listOf("a", "b", "c", "", "d"), lines)
            val response = assertIs<NetResult.Success<NetResponse>>(result).value
            assertEquals("1", response.header("x-a"))
            assertContentEquals(ByteArray(0), response.bytes)
        }

    @Test
    fun `a line handler that stops the stream still leaves its attempt in the log`() =
        runTest {
            // As a provider does on data it cannot read: it throws from the handler to stop.
            class Unreadable : IllegalStateException()
            val log = RecordingLog()
            val events = "event: message_start\ndata: {}\n\nevent: delta\ndata: not json\n\ndata: {}\n\n"
            assertFailsWith<Unreadable> {
                withClient(log = log, handler = { respond(events) }) { client ->
                    client.stream(post) { if (it == "data: not json") throw Unreadable() }
                }
            }
            assertEquals(
                listOf("POST https://api.example.org/v1/messages {1 header} -> stopped by caller in 0 ms (attempt 1)"),
                log.lines,
            )
        }
}
