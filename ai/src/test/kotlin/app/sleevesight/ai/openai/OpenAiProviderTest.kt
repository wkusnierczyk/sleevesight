package app.sleevesight.ai.openai

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OpenAiProviderTest {
    private val key = Secret("sk-proj-test-key")
    private val request =
        CompletionRequest(model = "gpt-6-luna", prompt = "Say hello in three words.", maxTokens = 256)

    // Detekt 1.23.8 cannot resolve `toList` (from a library built with a newer Kotlin than its own), so
    // it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun events(
        request: CompletionRequest = this.request,
        answer: (NetRequest) -> NetResult<NetResponse>,
    ): Pair<List<CompletionEvent>, ReplayNetClient> {
        val net = ReplayNetClient(answer)
        return OpenAiProvider(net).stream(request, key).toList() to net
    }

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" }

    private fun text(events: List<CompletionEvent>) =
        events.filterIsInstance<CompletionEvent.Delta>().joinToString("") {
            it.text
        }

    private fun body(net: ReplayNetClient): JsonObject =
        Json
            .parseToJsonElement(
                net.requests
                    .single()
                    .body!!
                    .text,
            ).jsonObject

    private val created = """{"type":"response.created","response":{"status":"in_progress","usage":null}}"""

    private fun delta(text: String) = """{"type":"response.output_text.delta","item_id":"m","delta":"$text"}"""

    private fun completed(usage: String = """{"input_tokens":10,"output_tokens":7}""") =
        """{"type":"response.completed","response":{"status":"completed","usage":$usage}}"""

    private fun response(
        status: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, headers, body.encodeToByteArray()))

    private fun errorBody(
        type: String?,
        code: String?,
    ): String {
        fun field(value: String?) = value?.let { "\"$it\"" } ?: "null"
        return """{"error":{"message":"m","type":${field(type)},"param":null,"code":${field(code)}}}"""
    }

    private fun errorEvent(code: String) = """{"type":"error","code":"$code","message":"m","param":null}"""

    @Test
    fun `a stream from the documentation's events becomes its text deltas and Done with the usage`() =
        runTest {
            val (events, _) = events { ok(recorded("openai/responses-stream.sse")) }

            assertEquals("Hello there, friend!", text(events))
            assertEquals(
                CompletionEvent.Done(Usage(inputTokens = 14, outputTokens = 23), StopReason.END),
                events.last(),
            )
            assertEquals(1, events.count { it !is CompletionEvent.Delta })
        }

    @Test
    fun `the request is one streaming POST, unstored, the key only in the secrets`() =
        runTest {
            val (_, net) = events { ok(recorded("openai/responses-stream.sse")) }

            val sent = net.requests.single()
            assertEquals("https://api.openai.com/v1/responses", sent.url)
            assertEquals(NetMethod.POST, sent.method)
            assertEquals(mapOf("authorization" to "Bearer sk-proj-test-key"), sent.secrets.headers)
            assertEquals(mapOf("accept" to "text/event-stream"), sent.headers)
            assertEquals(OpenAiProvider.STREAM_TIMEOUT, sent.timeout)
            assertEquals(
                Json.parseToJsonElement(
                    """{"model":"gpt-6-luna","input":"Say hello in three words.","max_output_tokens":256,""" +
                        """"stream":true,"store":false}""",
                ),
                body(net),
            )
            assertEquals("application/json", sent.body!!.contentType)
            assertFalse("sk-proj" in sent.toString() + sent.url + sent.headers + sent.body!!.text)
        }

    @Test
    fun `an effort goes in reasoning, by its name, and none leaves the model's default`() =
        runTest {
            val names =
                mapOf(ReasoningEffort.LOW to "low", ReasoningEffort.MEDIUM to "medium", ReasoningEffort.HIGH to "high")
            for ((effort, name) in names) {
                val (_, net) = events(request.copy(effort = effort)) { ok(recorded("openai/responses-stream.sse")) }
                assertEquals(Json.parseToJsonElement("""{"effort":"$name"}"""), body(net)["reasoning"], name)
            }
            val (_, net) = events { ok(recorded("openai/responses-stream.sse")) }
            assertEquals(null, body(net)["reasoning"])
        }

    @Test
    fun `a model not known to take low, medium and high gets no effort`() =
        runTest {
            val older = request.copy(model = "gpt-4.1", effort = ReasoningEffort.LOW)
            val (_, net) = events(older) { ok(recorded("openai/responses-stream.sse")) }
            assertEquals(null, body(net)["reasoning"])

            for (model in listOf(
                "gpt-6-luna",
                "gpt-6-astra",
                "gpt-6.1-sol",
                "gpt-5.6-terra",
                "gpt-5.4-nano",
                "gpt-5-nano-2025-08-07",
                "gpt-5.3-codex",
                "o4-mini",
                "o3",
            )) {
                assertTrue(OpenAiProvider.takesEffort(model), model)
            }
            for (model in listOf(
                "gpt-4.1",
                "gpt-4o-mini",
                "gpt-5-pro",
                "gpt-5.5-pro",
                "gpt-5-chat-latest",
                "gpt-5-search-api",
                "gpt-60",
                "chat-latest",
            )) {
                assertFalse(OpenAiProvider.takesEffort(model), model)
            }
        }

    @Test
    fun `reasoning and other events are skipped, and an incomplete response ends by its reason`() =
        runTest {
            val reasons =
                listOf(
                    "max_output_tokens" to StopReason.MAX_TOKENS,
                    "content_filter" to StopReason.REFUSAL,
                    "max_messages" to StopReason.OTHER,
                )
            for ((wire, reason) in reasons) {
                val (events, _) =
                    events {
                        ok(
                            sse(
                                created,
                                """{"type":"response.output_item.added","item":{"type":"reasoning"}}""",
                                """{"type":"response.reasoning_summary_text.delta","delta":"hmm"}""",
                                """{"type":"response.reasoning_text.delta","delta":"hmm"}""",
                                delta("Hi"),
                                """{"type":"some.future_event","what":1}""",
                                """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                                    """"incomplete_details":{"reason":"$wire"},""" +
                                    """"usage":{"input_tokens":15,"output_tokens":7}}}""",
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

    @Test
    fun `a refusal is not the reply's text, and ends the reply as refused`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            created,
                            """{"type":"response.refusal.delta","item_id":"m","delta":"I can't help"}""",
                            """{"type":"response.refusal.done","item_id":"m","refusal":"I can't help"}""",
                            completed(),
                        ),
                    )
                }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Done(Usage(10, 7), StopReason.REFUSAL)), events)
        }

    @Test
    fun `a completed response without usage counts none`() =
        runTest {
            val (events, _) = events { ok(sse(created, delta("Hi"), completed("null"))) }
            assertEquals(CompletionEvent.Done(Usage(0, 0), StopReason.END), events.last())
        }

    @Test
    fun `a failed response or an error event ends the stream with its kind`() =
        runTest {
            val cases =
                listOf(
                    """{"type":"response.failed","response":{"status":"failed",""" +
                        """"error":{"code":"rate_limit_exceeded","message":"m"}}}""" to AiErrorKind.RATE_LIMITED,
                    """{"type":"response.failed","response":{"status":"failed",""" +
                        """"error":{"code":"server_error","message":"m"}}}""" to AiErrorKind.SERVER,
                    """{"type":"response.failed","response":{"status":"failed","error":null}}""" to AiErrorKind.SERVER,
                    errorEvent("server_is_overloaded") to AiErrorKind.OVERLOADED,
                    errorEvent("insufficient_quota") to AiErrorKind.BILLING,
                    errorEvent("ERR_SOMETHING") to AiErrorKind.SERVER,
                )
            for ((event, kind) in cases) {
                val (events, _) = events { ok(sse(created, delta("Half"), event, completed())) }
                assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(kind)), events, event)
            }
        }

    @Test
    fun `a stream that ends before its response does is incomplete`() =
        runTest {
            val (events, _) = events { ok(sse(created, delta("Half"))) }
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.INCOMPLETE)), events)
        }

    @Test
    fun `data that is not JSON ends the stream as an unexpected response`() =
        runTest {
            val (events, _) = events { ok(sse("not json", completed())) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE)), events)
        }

    @Test
    fun `transport failures map to their kinds`() =
        runTest {
            val cases =
                listOf(
                    NetError.Unauthorized(401) to CompletionEvent.Error(AiErrorKind.UNAUTHORIZED),
                    NetError.Unauthorized(403) to CompletionEvent.Error(AiErrorKind.UNAUTHORIZED),
                    NetError.RateLimited(30.seconds) to CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 30.seconds),
                    NetError.Server(503) to CompletionEvent.Error(AiErrorKind.OVERLOADED),
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
    fun `error responses map by their code, else their type, else the status`() =
        runTest {
            val cases =
                listOf(
                    response(400, errorBody("invalid_request_error", null)) to AiErrorKind.INVALID_REQUEST,
                    response(404, errorBody("invalid_request_error", "model_not_found")) to AiErrorKind.MODEL_NOT_FOUND,
                    response(400, errorBody("invalid_request_error", "context_length_exceeded")) to
                        AiErrorKind.REQUEST_TOO_LARGE,
                    response(429, errorBody("insufficient_quota", "insufficient_quota")) to AiErrorKind.BILLING,
                    response(429, errorBody("insufficient_quota", "credit_balance_exhausted")) to AiErrorKind.BILLING,
                    response(429, errorBody("insufficient_quota", "project_spend_limit_exceeded")) to
                        AiErrorKind.BILLING,
                    response(429, errorBody("rate_limit_error", "slow_down")) to AiErrorKind.RATE_LIMITED,
                    response(429, "") to AiErrorKind.RATE_LIMITED,
                    response(404, "<html>gateway</html>") to AiErrorKind.MODEL_NOT_FOUND,
                    response(418, "") to AiErrorKind.UNEXPECTED_RESPONSE,
                )
            for ((answer, kind) in cases) {
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events { answer }.first, kind.name)
            }
        }

    @Test
    fun `a rate limit passed on as a response keeps its Retry-After, and an account out of credit none`() =
        runTest {
            val wait = mapOf("Retry-After" to listOf("20"))
            val limited = response(429, errorBody("requests", "rate_limit_exceeded"), wait)
            assertEquals(
                listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 20.seconds)),
                events { limited }.first,
            )
            val broke = response(429, errorBody("insufficient_quota", "insufficient_quota"), wait)
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)), events { broke }.first)
        }

    @Test
    fun `its host's policy has a 429 passed on, so the body can say why`() {
        assertTrue(OpenAiProvider.HOST_POLICY.passOn429)
        assertEquals("https://${OpenAiProvider.HOST}", OpenAiProvider.BASE_URL)
    }

    @Test
    fun `the connection lost after the response began leaves an incomplete reply`() =
        runTest {
            val net =
                object : NetClient {
                    override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

                    override suspend fun stream(
                        request: NetRequest,
                        onLine: suspend (String) -> Unit,
                    ): NetResult<NetResponse> {
                        sse(created, delta("Half")).lines().forEach { onLine(it) }
                        return NetResult.Failure(NetError.Offline("IOException"))
                    }

                    override fun close() = Unit
                }
            val events = OpenAiProvider(net).stream(request, key).toList()
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.INCOMPLETE)), events)
        }

    @Test
    fun `a collector that stops early cancels the stream`() =
        runTest {
            val first =
                OpenAiProvider(
                    ReplayNetClient {
                        ok(recorded("openai/responses-stream.sse"))
                    },
                ).stream(request, key).first()
            assertEquals(CompletionEvent.Delta("Hello"), first)
        }

    @Test
    fun `the model list keeps the text models, newest first`() =
        runTest {
            val net = ReplayNetClient { ok(recorded("openai/models.json")) }
            val listed = assertIs<ModelList.Listed>(OpenAiProvider(net).models(key)).models

            assertEquals(
                listOf(
                    "gpt-6.1-sol",
                    "gpt-6-astra",
                    "gpt-6-luna",
                    "ft:gpt-4.1-mini:example-org::abc123",
                    "gpt-5-nano",
                    "gpt-4.1",
                    "o4-mini",
                ),
                listed.map { it.id },
            )
            assertEquals(ModelInfo("gpt-6-luna", "gpt-6-luna", Instant.fromEpochSeconds(1789000000)), listed[2])
            val sent = net.requests.single()
            assertEquals("https://api.openai.com/v1/models", sent.url)
            assertEquals(NetMethod.GET, sent.method)
            assertEquals(mapOf("authorization" to "Bearer sk-proj-test-key"), sent.secrets.headers)
        }

    @Test
    fun `text models are told from the others by their ids`() {
        val text = listOf("gpt-6-luna", "gpt-5.3-codex", "o3", "chatgpt-4o-latest", "chat-latest", "ft:o4-mini:org::x")
        for (id in text) assertTrue(OpenAiProvider.isTextModel(id), id)
        for (id in listOf(
            "text-embedding-3-small",
            "whisper-1",
            "tts-1",
            "dall-e-3",
            "gpt-image-2.5-flare",
            "gpt-realtime-2.1-mini",
            "gpt-4o-mini-transcribe",
            "gpt-live-1",
            "omni-moderation-latest",
            "o3-deep-research",
            "gpt-5-search-api",
            "gpt-3.5-turbo-instruct",
            "davinci-002",
        )) {
            assertFalse(OpenAiProvider.isTextModel(id), id)
        }
    }

    @Test
    fun `a model list that fails says why`() =
        runTest {
            val provider = { answer: NetResult<NetResponse> -> OpenAiProvider(ReplayNetClient { answer }) }
            assertEquals(
                ModelList.Failed(AiErrorKind.UNAUTHORIZED),
                provider(failure(NetError.Unauthorized(401))).models(key),
            )
            assertEquals(ModelList.Failed(AiErrorKind.UNEXPECTED_RESPONSE), provider(ok("{}")).models(key))
            assertEquals(
                ModelList.Failed(AiErrorKind.BILLING),
                provider(response(429, errorBody("insufficient_quota", "insufficient_quota"))).models(key),
            )
        }
}
