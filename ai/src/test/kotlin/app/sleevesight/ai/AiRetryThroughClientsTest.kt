package app.sleevesight.ai

import app.sleevesight.ai.ReplayNetClient.Companion.failure
import app.sleevesight.ai.ReplayNetClient.Companion.ok
import app.sleevesight.ai.ReplayNetClient.Companion.recorded
import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.google.GoogleProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.pipeline.AiRetry
import app.sleevesight.core.pipeline.ReplyFailures
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The AI calls' one retry (#1164), through the real clients: they send nothing again themselves, so
 * *Don’t retry* sends one request, and the total counts from the first refusal; an overloaded
 * service's `Retry-After` is kept and waited out; a reply cut off by the network gone is not asked
 * again.
 */
class AiRetryThroughClientsTest {
    private val request = CompletionRequest("gemini-3.5-flash", "prompt")
    private val key = Secret("key")

    /** Each client, with a 429 that asks for 3 s, and a whole reply. */
    private class Client(
        val make: (NetClient) -> AiProvider,
        val limited: NetResult<NetResponse>,
        val reply: String,
    )

    private val clients =
        listOf(
            Client(
                { AnthropicProvider(it) },
                response(429, """{"type":"error","error":{"type":"rate_limit_error","message":"m"}}""", "retry-after"),
                recorded("anthropic/messages-stream.sse"),
            ),
            Client(
                { OpenAiProvider(it) },
                response(
                    429,
                    """{"error":{"message":"m","type":"requests","param":null,"code":"rate_limit_exceeded"}}""",
                ),
                recorded("openai/responses-stream.sse"),
            ),
            Client(
                { GoogleProvider(it) },
                response(
                    429,
                    """{"error":{"code":429,"message":"m","status":"RESOURCE_EXHAUSTED","details":[""" +
                        """{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":""" +
                        """"GenerateRequestsPerMinutePerProjectPerModel"}]}]}}""",
                ),
                recorded("google/stream-generate-content.sse"),
            ),
        )

    @Test
    fun `don't retry sends exactly one request, the clients retrying nothing themselves`() =
        runTest {
            for (client in clients) {
                val net = ReplayNetClient { client.limited }
                val events = retrying(client.make(net), Duration.ZERO).stream(request, key).toList()
                assertEquals(AiErrorKind.RATE_LIMITED, (events.single() as CompletionEvent.Error).kind)
                assertEquals(1, net.requests.size, client.reply.take(20))
            }
        }

    @Test
    fun `a rate limit is asked again once, after the wait it asks for, and answered`() =
        runTest {
            for (client in clients) {
                val sent = mutableListOf<Long>()
                val net =
                    ReplayNetClient {
                        sent += currentTime
                        if (sent.size == 1) client.limited else ok(client.reply)
                    }
                val events = retrying(client.make(net), 30.seconds).stream(request, key).toList()
                assertIs<CompletionEvent.Done>(events.last())
                assertEquals(listOf(0L, 3_000L), sent.map { it - sent.first() }, client.reply.take(20))
            }
        }

    @Test
    fun `an overloaded service's Retry-After past the total ends the tries at once`() =
        runTest {
            for (make in listOf<(NetClient) -> AiProvider>({ GoogleProvider(it) }, { OpenAiProvider(it) })) {
                val net = ReplayNetClient { failure(NetError.Server(503, 60.seconds)) }
                val events = retrying(make(net), 30.seconds).stream(request, key).toList()
                val error = events.single() as CompletionEvent.Error
                assertEquals(AiErrorKind.OVERLOADED, error.kind)
                assertEquals(60.seconds, error.retryAfter)
                assertEquals(1, net.requests.size)
            }
        }

    @Test
    fun `an overloaded service's Retry-After within the total is waited out`() =
        runTest {
            val sent = mutableListOf<Long>()
            val net =
                ReplayNetClient {
                    sent += currentTime
                    if (sent.size ==
                        1
                    ) {
                        failure(NetError.Server(503, 10.seconds))
                    } else {
                        ok(recorded("google/stream-generate-content.sse"))
                    }
                }
            retrying(GoogleProvider(net), 30.seconds).stream(request, key).toList()
            assertEquals(listOf(0L, 10_000L), sent.map { it - sent.first() })
        }

    @Test
    fun `a timeout after the request was sent is not asked again, one to connect is`() =
        runTest {
            for ((reason, sent) in listOf("SocketTimeoutException" to 1, "ConnectTimeoutException" to 2)) {
                var calls = 0
                val net =
                    ReplayNetClient {
                        calls++
                        if (calls ==
                            1
                        ) {
                            failure(NetError.Offline(reason))
                        } else {
                            ok(recorded("google/stream-generate-content.sse"))
                        }
                    }
                retrying(GoogleProvider(net), 30.seconds).stream(request, key).toList()
                assertEquals(sent, net.requests.size, reason)
            }
        }

    @Test
    fun `a connection that failed before the request was written is asked again, one after it was sent is not`() =
        runTest {
            for (client in clients) {
                for ((unsent, sent) in listOf(true to 2, false to 1)) {
                    var calls = 0
                    val net =
                        ReplayNetClient {
                            calls++
                            if (calls == 1) {
                                failure(NetError.Offline("SocketException (ECONNRESET)", unsent))
                            } else {
                                ok(client.reply)
                            }
                        }
                    val events = retrying(client.make(net), 30.seconds).stream(request, key).toList()
                    val label = "${client.reply.take(20)} unsent=$unsent"
                    assertEquals(sent, net.requests.size, label)
                    if (unsent) {
                        assertIs<CompletionEvent.Done>(events.last(), label)
                    } else {
                        assertEquals(
                            AiErrorKind.CONNECTION_FAILED,
                            (events.single() as CompletionEvent.Error).kind,
                            label,
                        )
                    }
                }
            }
        }

    @Test
    fun `an Anthropic reply that failed before any of it was generated is asked again, one after is not`() =
        runTest {
            // `message_start`'s output count is preliminary, there for every reply; a thinking delta, a
            // block that begins with content, or a tool call's start is some of the reply generated,
            // and billed, though not passed on; whether the stream is then cut or the service sends
            // an overload error.
            val start = """{"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":1}}}"""
            val overloaded = """{"type":"error","error":{"type":"overloaded_error","message":"m"}}"""
            val thinking =
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""
            val thought =
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hm"}}"""
            val emptyText = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
            val text = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hal"}}"""
            val thoughtAtStart =
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"Hm"}}"""
            val tool =
                """{"type":"content_block_start","index":0,""" +
                    """"content_block":{"type":"tool_use","id":"t","name":"web_search","input":{}}}"""
            val cases =
                listOf(
                    listOf(start) to 2,
                    listOf(start, overloaded) to 2,
                    listOf(start, thinking) to 2,
                    listOf(start, emptyText) to 2,
                    listOf(start, thinking, thought) to 1,
                    listOf(start, thinking, thought, overloaded) to 1,
                    listOf(start, tool, overloaded) to 1,
                    listOf(start, text) to 1,
                    listOf(start, thoughtAtStart) to 1,
                )
            for ((first, sent) in cases) {
                val label = first.drop(1).joinToString(" ").ifEmpty { "message_start" }
                var calls = 0
                val net =
                    object : NetClient {
                        override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

                        override suspend fun stream(
                            request: NetRequest,
                            onLine: suspend (String) -> Unit,
                        ): NetResult<NetResponse> {
                            calls++
                            if (calls == 1) {
                                // Then the stream ends, with no end of the reply, or the error ends it.
                                first.forEach {
                                    onLine("data: $it")
                                    onLine("")
                                }
                            } else {
                                recorded("anthropic/messages-stream.sse").lines().forEach { onLine(it) }
                            }
                            return NetResult.Success(NetResponse(200, emptyMap(), ByteArray(0)))
                        }

                        override fun close() = Unit
                    }
                val events = retrying(AnthropicProvider(net), 30.seconds).stream(request, key).toList()
                assertEquals(sent, calls, label)
                if (sent == 1) {
                    val error = events.last() as CompletionEvent.Error
                    val kind = if (first.last() == overloaded) AiErrorKind.OVERLOADED else AiErrorKind.INCOMPLETE
                    assertEquals(kind, error.kind, label)
                    assertEquals(true, error.generated, label)
                } else {
                    assertIs<CompletionEvent.Done>(events.last(), label)
                }
            }
        }

    @Test
    fun `an OpenAI or Google reply that failed before any of it was generated is asked again, one after is not`() =
        runTest {
            val created = """{"type":"response.created","response":{"status":"in_progress"}}"""
            val reasoning =
                """{"type":"response.output_item.added","output_index":0,""" +
                    """"item":{"id":"rs","type":"reasoning","summary":[]}}"""
            val summary = """{"type":"response.reasoning_summary_text.delta","delta":"Hm"}"""
            val call =
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call",""" +
                    """"call_id":"c","name":"web_search","arguments":"{}"}}"""
            val failed = """{"error":{"code":503,"message":"m","status":"UNAVAILABLE"}}"""
            val counted = """{"usageMetadata":{"promptTokenCount":3}}"""
            val thought = """{"candidates":[{"content":{"parts":[{"text":"Hm","thought":true}],"role":"model"}}]}"""
            val cases =
                listOf(
                    Triple({ net: NetClient -> OpenAiProvider(net) }, listOf(created), 2),
                    Triple({ net: NetClient -> OpenAiProvider(net) }, listOf(created, reasoning), 1),
                    Triple({ net: NetClient -> OpenAiProvider(net) }, listOf(created, summary), 1),
                    Triple({ net: NetClient -> OpenAiProvider(net) }, listOf(created, call), 1),
                    Triple({ net: NetClient -> GoogleProvider(net) }, listOf(failed), 2),
                    Triple({ net: NetClient -> GoogleProvider(net) }, listOf(counted), 2),
                    Triple({ net: NetClient -> GoogleProvider(net) }, listOf(thought), 1),
                    Triple({ net: NetClient -> GoogleProvider(net) }, listOf(thought, failed), 1),
                )
            for ((make, first, sent) in cases) {
                val reply =
                    if (first.first() ==
                        created
                    ) {
                        "openai/responses-stream.sse"
                    } else {
                        "google/stream-generate-content.sse"
                    }
                val net = FirstCut(first, recorded(reply))
                val events = retrying(make(net), 30.seconds).stream(request, key).toList()
                val label = first.joinToString(" ")
                assertEquals(sent, net.calls, label)
                if (sent == 1) {
                    assertEquals(true, (events.last() as CompletionEvent.Error).generated, label)
                } else {
                    assertIs<CompletionEvent.Done>(events.last(), label)
                }
            }
        }

    /** A stream whose first try sends [first] (each event's data) and then ends; the rest, [reply]. */
    private class FirstCut(
        private val first: List<String>,
        private val reply: String,
    ) : NetClient {
        var calls = 0

        override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

        override suspend fun stream(
            request: NetRequest,
            onLine: suspend (String) -> Unit,
        ): NetResult<NetResponse> {
            calls++
            if (calls == 1) {
                first.forEach {
                    onLine("data: $it")
                    onLine("")
                }
            } else {
                reply.lines().forEach { onLine(it) }
            }
            return NetResult.Success(NetResponse(200, emptyMap(), ByteArray(0)))
        }

        override fun close() = Unit
    }

    @Test
    fun `a reply cut off by the network gone, before any text, is not asked again and says the connection was lost`() =
        runTest {
            var calls = 0
            val net =
                object : NetClient {
                    override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

                    override suspend fun stream(
                        request: NetRequest,
                        onLine: suspend (String) -> Unit,
                    ): NetResult<NetResponse> {
                        calls++
                        onLine("""data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}""")
                        onLine("")
                        return NetResult.Failure(NetError.Offline("SocketException (ENETDOWN)"))
                    }

                    override fun close() = Unit
                }
            val events = retrying(AnthropicProvider(net), 30.seconds).stream(request, key).toList()
            val error = events.single() as CompletionEvent.Error
            assertEquals(BreakoffCause.NO_NETWORK, error.breakoff?.cause)
            assertEquals(ReplyFailures.CONNECTION_LOST, ReplyFailures.of(error))
            assertEquals(1, calls)
        }

    private fun TestScope.retrying(
        provider: AiProvider,
        total: Duration,
    ) = AiRetry(provider, { total }, testScheduler.timeSource, NO_JITTER)

    private companion object {
        // No jitter: a random that always draws zero.
        val NO_JITTER =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
            }

        /** An error answer with [status] and [body], asking for 3 s under [header]. */
        fun response(
            status: Int,
            body: String,
            header: String = "Retry-After",
        ): NetResult<NetResponse> =
            NetResult.Success(NetResponse(status, mapOf(header to listOf("3")), body.encodeToByteArray()))
    }
}
