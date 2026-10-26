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
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.net.RetryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // currentTime, advanceTimeBy, runCurrent
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
        return provider(net).stream(request, key).toList() to net
    }

    /** A provider whose clock stands at [NOW]. */
    private fun provider(net: NetClient) = AnthropicProvider(net, RetryPolicy(), clock = clock)

    private val clock =
        object : Clock {
            override fun now(): Instant = NOW
        }

    private fun response(
        status: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, headers, body.encodeToByteArray()))

    /** An error of [type], with [details] when given, as an error body or an `error` event has it. */
    private fun wireError(
        type: String,
        message: String,
        details: String? = null,
    ): String = """{"type":"$type","message":"$message"""" + (details?.let { ""","details":$it""" } ?: "") + "}"

    private fun errorBody(error: String) = """{"type":"error","error":$error,"request_id":"req_x"}"""

    /** A 429 `rate_limit_error`, as Anthropic sends one for a rate limit. */
    private val rateLimit = errorBody(wireError("rate_limit_error", "Number of request tokens has exceeded a limit"))

    /** The error of the tier's spend cap reached, as Anthropic's rate limits page gives it. */
    private val spendCapError =
        wireError(
            "rate_limit_error",
            "You have reached your API usage limits: your organization has crossed its monthly API usage " +
                "threshold, set based on your organization's API tier. " +
                "You will regain access on 2026-09-01 at 00:00 UTC.",
            """{"error_code":"enforced_spend_limit_reached"}""",
        )

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
            provider(net).stream(request.copy(effort = ReasoningEffort.LOW), key).toList()

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
                    provider(net).stream(request.copy(effort = effort), key).toList()
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
            provider(net).stream(older, key).toList()

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
    fun `a spend limit reached is billing, though its type is that of a malformed request`() =
        runTest {
            // The bodies as Anthropic's rate limits page gives them: 400 invalid_request_error, the
            // message naming the limit set on the organization, or on the workspace.
            fun body(message: String) =
                """{"type":"error","error":{"type":"invalid_request_error","message":"$message"},""" +
                    """"request_id":"req_011CSHoEeqs5C35K2UUqR7Fy"}"""
            val cases =
                listOf(
                    "You have reached your specified API usage limits. " +
                        "You will regain access on 2026-11-01 at 00:00 UTC.",
                    "You have reached your specified workspace API usage limits. " +
                        "You will regain access on 2026-11-01 at 00:00 UTC.",
                )
            for (message in cases) {
                assertEquals(
                    listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)),
                    events { ok(body(message), 400) }.first,
                    message,
                )
            }
            val malformed = body("max_tokens: Field required")
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.INVALID_REQUEST)),
                events { ok(malformed, 400) }.first,
            )
            val limited = provider(ReplayNetClient { ok(body(cases[1]), 400) })
            assertEquals(ModelList.Failed(AiErrorKind.BILLING), limited.models(key))
            // An `error` event mid-stream is read the same way.
            val event = """{"type":"error","error":{"type":"invalid_request_error","message":"${cases[1]}"}}"""
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)),
                events { ok(sse(event)) }.first,
            )
        }

    @Test
    fun `the tier's spend cap reached is billing at once, though its type is a rate limit's`() =
        runTest {
            // The documented response carries no `retry-after`.
            val capped = response(429, errorBody(spendCapError))
            val started = currentTime
            val (events, net) = events { capped }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)), events)
            assertEquals(1, net.requests.size)
            assertEquals(0, currentTime - started)
            assertEquals(ModelList.Failed(AiErrorKind.BILLING), provider(ReplayNetClient { capped }).models(key))
            // An `error` event mid-stream is read the same way.
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)),
                events { ok(sse("""{"type":"error","error":$spendCapError}""")) }.first,
            )
            // Another error code on a rate limit's type stays a rate limit.
            val other = errorBody(wireError("rate_limit_error", "m", """{"error_code":"something_else"}"""))
            val wait = mapOf("retry-after" to listOf("60"))
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 60.seconds)),
                events { response(429, other, wait) }.first,
            )
        }

    @Test
    fun `a rate limit passed on as a response keeps its retry-after`() =
        runTest {
            val wait = mapOf("retry-after" to listOf("20"))
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 20.seconds)),
                events { response(429, rateLimit, wait) }.first,
            )
            // A 429 whose body says nothing is a rate limit too.
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 20.seconds)),
                events { response(429, "", wait) }.first,
            )
            assertEquals(
                ModelList.Failed(AiErrorKind.RATE_LIMITED),
                provider(ReplayNetClient { response(429, rateLimit) }).models(key),
            )
        }

    @Test
    fun `a rate limit within the cap is sent once more after its retry-after, a date included`() =
        runTest {
            for ((retryAfter, wait) in listOf("3" to 3.seconds, "Wed, 21 Oct 2026 12:00:07 GMT" to 7.seconds)) {
                val limited = response(429, rateLimit, mapOf("retry-after" to listOf(retryAfter)))
                var calls = 0
                val started = currentTime
                val (events, net) =
                    events {
                        calls++
                        if (calls == 1) limited else ok(recorded("anthropic/messages-stream.sse"))
                    }
                assertIs<CompletionEvent.Done>(events.last(), retryAfter)
                assertEquals(2, net.requests.size)
                assertEquals(net.requests[0].body!!.text, net.requests[1].body!!.text)
                assertEquals(wait.inWholeMilliseconds, currentTime - started, retryAfter)
            }
        }

    @Test
    fun `a rate limit is sent again once only, and not at all past the cap or on another status`() =
        runTest {
            val (again, twice) = events { response(429, rateLimit) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED)), again)
            assertEquals(2, twice.requests.size)

            val (late, once) = events { response(429, rateLimit, mapOf("retry-after" to listOf("60"))) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 60.seconds)), late)
            assertEquals(1, once.requests.size)

            // A rate limit's type on another status: not a refusal before it was acted on.
            val (other, alone) = events { response(400, rateLimit) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED)), other)
            assertEquals(1, alone.requests.size)
        }

    @Test
    fun `a cancel while a rate limit is waited out sends nothing more`() =
        runTest {
            val net = ReplayNetClient { response(429, rateLimit, mapOf("retry-after" to listOf("5"))) }
            val collecting = launch { provider(net).stream(request, key).toList() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, net.requests.size)
            // Still waiting out the `retry-after`, not ended.
            assertTrue(collecting.isActive)
            collecting.cancel()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(collecting.isCompleted)
            assertEquals(1, net.requests.size)
        }

    @Test
    fun `its host's policy has a 429 passed on, so the body can say why`() {
        assertTrue(AnthropicProvider.HOST_POLICY.passOn429)
        assertEquals("https://${AnthropicProvider.HOST}", AnthropicProvider.BASE_URL)
    }

    @Test
    fun `the connection lost after the message began leaves an incomplete reply`() =
        runTest {
            val net =
                object : NetClient {
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
            val events = provider(net).stream(request, key).toList()
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.INCOMPLETE)), events)
        }

    @Test
    fun `a collector that stops early cancels the stream`() =
        runTest {
            val first =
                provider(
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
            val listed = assertIs<ModelList.Listed>(provider(net).models(key)).models

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
    fun `a deprecated model is listed but not offered for a new choice, and a retired one not listed`() =
        runTest {
            fun model(
                id: String,
                lifecycle: String?,
            ) = """{"type":"model","id":"$id","display_name":"$id","created_at":"2026-01-01T00:00:00Z"""" +
                (lifecycle?.let { ""","lifecycle":"$it"""" } ?: "") + "}"
            val page =
                """{"data":[${model("a", "active")},${model("d", "deprecated")},${model("r", "retired")},""" +
                    """${model("n", null)}],"has_more":false,"last_id":"n"}"""
            val listed = assertIs<ModelList.Listed>(provider(ReplayNetClient { ok(page) }).models(key))

            assertEquals(listOf("a" to true, "d" to false, "n" to true), listed.models.map { it.id to it.offered })
            assertTrue(listed.has("d"))
            assertFalse(listed.has("r"))
        }

    @Test
    fun `a model the service no longer serves is a model not found`() =
        runTest {
            val gone =
                errorBody("""{"type":"not_found_error","message":"model: claude-3-sonnet-20240229"}""")
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)),
                events { ok(gone, 404) }.first,
            )
        }

    @Test
    fun `a model list that fails says why`() =
        runTest {
            val provider = { answer: NetResult<NetResponse> -> provider(ReplayNetClient { answer }) }
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-21T12:00:00Z")
    }
}
