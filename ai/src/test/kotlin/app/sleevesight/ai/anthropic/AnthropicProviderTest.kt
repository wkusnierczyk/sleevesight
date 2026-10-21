package app.sleevesight.ai.anthropic

import app.sleevesight.ai.ReplayNetClient
import app.sleevesight.ai.ReplayNetClient.Companion.failure
import app.sleevesight.ai.ReplayNetClient.Companion.ok
import app.sleevesight.ai.ReplayNetClient.Companion.recorded
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AnthropicProviderTest {
    private val key = Secret("sk-ant-test-key")
    private val request =
        CompletionRequest(model = "claude-haiku-5-5", prompt = "Say hello in three words.", maxTokens = 256)

    // Detekt 1.23.8 cannot resolve `toList` (from a library built with a newer Kotlin than its own), so
    // it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun events(
        answer: (NetRequest) -> NetResult<NetResponse>,
    ): Pair<List<CompletionEvent>, ReplayNetClient> {
        val net = ReplayNetClient(answer)
        return AnthropicProvider(net).stream(request, key).toList() to net
    }

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" }

    private fun text(events: List<CompletionEvent>) =
        events.filterIsInstance<CompletionEvent.Delta>().joinToString("") {
            it.text
        }

    @Test
    fun `a recorded stream becomes its text deltas and Done with the usage`() =
        runTest {
            val (events, _) = events { ok(recorded("anthropic/messages-stream.sse")) }

            assertEquals(
                "Hello, friend! Welcome!\n\nWait, that's not quite right. Here's a proper three-word greeting:" +
                    "\n\nHello there, friend.",
                text(events),
            )
            assertEquals(
                CompletionEvent.Done(Usage(inputTokens = 16, outputTokens = 48), StopReason.END),
                events.last(),
            )
            assertEquals(1, events.count { it !is CompletionEvent.Delta })
        }

    @Test
    fun `the request is one streaming POST, the key only in the secrets`() =
        runTest {
            val (_, net) = events { ok(recorded("anthropic/messages-stream.sse")) }

            val sent = net.requests.single()
            assertEquals("https://api.anthropic.com/v1/messages", sent.url)
            assertEquals(NetMethod.POST, sent.method)
            assertEquals(mapOf("x-api-key" to "sk-ant-test-key"), sent.secrets.headers)
            assertEquals(mapOf("anthropic-version" to "2023-06-01", "accept" to "text/event-stream"), sent.headers)
            assertEquals(AnthropicProvider.STREAM_TIMEOUT, sent.timeout)
            val body = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals(
                Json.parseToJsonElement(
                    """{"model":"claude-haiku-5-5","max_tokens":256,"stream":true,""" +
                        """"messages":[{"role":"user","content":"Say hello in three words."}]}""",
                ),
                body,
            )
            assertEquals("application/json", sent.body!!.contentType)
            assertFalse("sk-ant" in sent.toString() + sent.url + sent.headers + sent.body!!.text)
        }

    @Test
    fun `an effort goes in output_config, and none leaves the model's default`() =
        runTest {
            val net = ReplayNetClient { ok(recorded("anthropic/messages-stream.sse")) }
            AnthropicProvider(net).stream(request.copy(effort = ReasoningEffort.LOW), key).toList()

            val body =
                Json
                    .parseToJsonElement(
                        net.requests
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals(Json.parseToJsonElement("""{"effort":"low"}"""), body["output_config"])
            assertEquals(null, body["thinking"])
        }

    @Test
    fun `each level is sent as Anthropic names it, minimal as low`() =
        runTest {
            val sent =
                ReasoningEffort.entries.associateWith { effort ->
                    val net = ReplayNetClient { ok(recorded("anthropic/messages-stream.sse")) }
                    AnthropicProvider(net).stream(request.copy(effort = effort), key).toList()
                    Json
                        .parseToJsonElement(
                            net.requests
                                .single()
                                .body!!
                                .text,
                        ).jsonObject["output_config"]
                }
            assertEquals(
                mapOf(
                    ReasoningEffort.MINIMAL to "low",
                    ReasoningEffort.LOW to "low",
                    ReasoningEffort.MEDIUM to "medium",
                    ReasoningEffort.HIGH to "high",
                ).mapValues { Json.parseToJsonElement("""{"effort":"${it.value}"}""") },
                sent,
            )
        }

    @Test
    fun `a model not known to take an effort gets none`() =
        runTest {
            val net = ReplayNetClient { ok(recorded("anthropic/messages-stream.sse")) }
            val older = request.copy(model = "claude-haiku-4-5", effort = ReasoningEffort.LOW)
            AnthropicProvider(net).stream(older, key).toList()

            val body =
                Json
                    .parseToJsonElement(
                        net.requests
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals(null, body["output_config"])
            for (model in listOf(
                "claude-haiku-5-5",
                "claude-opus-5-5",
                "claude-sonnet-4-6",
                "claude-opus-4-5-20251101",
            )) {
                assertEquals(true, AnthropicProvider.takesEffort(model), model)
            }
            for (model in listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-50")) {
                assertEquals(false, AnthropicProvider.takesEffort(model), model)
            }
        }

    @Test
    fun `reasoning and other content are skipped, and the stop reasons map`() =
        runTest {
            val reasons =
                listOf(
                    "max_tokens" to StopReason.MAX_TOKENS,
                    "refusal" to StopReason.REFUSAL,
                    "pause_turn" to StopReason.OTHER,
                )
            for ((wire, reason) in reasons) {
                val (events, _) =
                    events {
                        ok(
                            sse(
                                """{"type":"message_start","message":{"usage":{"input_tokens":10,""" +
                                    """"cache_read_input_tokens":5}}}""",
                                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
                                delta(0, """{"type":"thinking_delta","thinking":"hmm"}"""),
                                delta(0, """{"type":"signature_delta","signature":"x"}"""),
                                delta(1, """{"type":"text_delta","text":"Hi"}"""),
                                """{"type":"some_future_event","what":1}""",
                                """{"type":"message_delta","delta":{"stop_reason":"$wire"},""" +
                                    """"usage":{"output_tokens":7}}""",
                                """{"type":"message_stop"}""",
                            ),
                        )
                    }
                assertEquals(
                    listOf(CompletionEvent.Delta("Hi"), CompletionEvent.Done(Usage(15, 7), reason)),
                    events,
                    wire,
                )
            }
        }

    private fun delta(
        index: Int,
        delta: String,
    ) = """{"type":"content_block_delta","index":$index,"delta":$delta}"""

    @Test
    fun `an error event mid-stream ends it with its kind`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Half"}}""",
                            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
                            """{"type":"message_stop"}""",
                        ),
                    )
                }
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.OVERLOADED)), events)
        }

    @Test
    fun `a stream that ends before message_stop is incomplete`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Half"}}""",
                        ),
                    )
                }
            assertEquals(CompletionEvent.Error(AiErrorKind.INCOMPLETE), events.last())
        }

    @Test
    fun `data that is not JSON ends the stream as an unexpected response`() =
        runTest {
            val (events, _) = events { ok(sse("not json", """{"type":"message_stop"}""")) }
            assertEquals(listOf(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE)), events)
        }

    @Test
    fun `transport failures map to their kinds`() =
        runTest {
            val cases =
                listOf(
                    NetError.Unauthorized(401) to CompletionEvent.Error(AiErrorKind.UNAUTHORIZED),
                    NetError.RateLimited(30.seconds) to CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 30.seconds),
                    NetError.Server(529) to CompletionEvent.Error(AiErrorKind.OVERLOADED),
                    NetError.Server(500) to CompletionEvent.Error(AiErrorKind.SERVER),
                    NetError.Offline("IOException") to CompletionEvent.Error(AiErrorKind.OFFLINE),
                    NetError.InvalidRequest("header") to CompletionEvent.Error(AiErrorKind.INVALID_REQUEST),
                    NetError.Protocol("ProtocolException") to CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE),
                )
            for ((error, expected) in cases) {
                assertEquals(listOf<CompletionEvent>(expected), events { failure(error) }.first, error.toString())
            }
        }

    @Test
    fun `error responses map by their error type, else by status`() =
        runTest {
            fun body(type: String) = """{"type":"error","error":{"type":"$type","message":"m"},"request_id":"req_x"}"""
            val cases =
                listOf(
                    ok(body("invalid_request_error"), 400) to AiErrorKind.INVALID_REQUEST,
                    ok(body("not_found_error"), 404) to AiErrorKind.MODEL_NOT_FOUND,
                    ok(body("billing_error"), 402) to AiErrorKind.BILLING,
                    ok(body("request_too_large"), 413) to AiErrorKind.REQUEST_TOO_LARGE,
                    ok("<html>gateway</html>", 404) to AiErrorKind.MODEL_NOT_FOUND,
                    ok("", 418) to AiErrorKind.UNEXPECTED_RESPONSE,
                )
            for ((response, kind) in cases) {
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events { response }.first, kind.name)
            }
        }

    @Test
    fun `the connection lost after the message began leaves an incomplete reply`() =
        runTest {
            val net =
                object : app.sleevesight.net.NetClient {
                    override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

                    override suspend fun stream(
                        request: NetRequest,
                        onLine: suspend (String) -> Unit,
                    ): NetResult<NetResponse> {
                        sse(
                            """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""",
                            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Half"}}""",
                        ).lines().forEach { onLine(it) }
                        return NetResult.Failure(NetError.Offline("IOException"))
                    }

                    override fun close() = Unit
                }
            val events = AnthropicProvider(net).stream(request, key).toList()
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.INCOMPLETE)), events)
        }

    @Test
    fun `a collector that stops early cancels the stream`() =
        runTest {
            val first =
                AnthropicProvider(
                    ReplayNetClient {
                        ok(recorded("anthropic/messages-stream.sse"))
                    },
                ).stream(request, key).first()
            assertEquals(CompletionEvent.Delta("Hello, fri"), first)
        }

    @Test
    fun `the model list follows its pages`() =
        runTest {
            val net =
                ReplayNetClient { request ->
                    ok(
                        recorded(
                            if ("after_id" in
                                request.query
                            ) {
                                "anthropic/models-page-2.json"
                            } else {
                                "anthropic/models-page-1.json"
                            },
                        ),
                    )
                }
            val listed = assertIs<ModelList.Listed>(AnthropicProvider(net).models(key)).models

            assertEquals(14, listed.size)
            assertEquals(
                ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", Instant.parse("2026-10-07T18:00:00Z")),
                listed.first(),
            )
            assertEquals("claude-sonnet-4-5-20250929", listed.last().id)
            assertEquals(listOf(null, "claude-sonnet-4-6"), net.requests.map { it.query["after_id"] })
            net.requests.forEach {
                assertEquals("https://api.anthropic.com/v1/models", it.url)
                assertEquals(NetMethod.GET, it.method)
                assertEquals(mapOf("x-api-key" to "sk-ant-test-key"), it.secrets.headers)
            }
        }

    @Test
    fun `a model list that fails says why`() =
        runTest {
            val provider = { answer: NetResult<NetResponse> -> AnthropicProvider(ReplayNetClient { answer }) }
            assertEquals(
                ModelList.Failed(AiErrorKind.UNAUTHORIZED),
                provider(failure(NetError.Unauthorized(401))).models(key),
            )
            assertEquals(ModelList.Failed(AiErrorKind.UNEXPECTED_RESPONSE), provider(ok("{}")).models(key))
            assertEquals(
                ModelList.Failed(AiErrorKind.INVALID_REQUEST),
                provider(ok("""{"type":"error","error":{"type":"invalid_request_error"}}""", 400)).models(key),
            )
        }
}
