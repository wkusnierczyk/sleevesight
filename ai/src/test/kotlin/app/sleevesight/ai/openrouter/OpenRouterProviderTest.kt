package app.sleevesight.ai.openrouter

import app.sleevesight.ai.ReplayNetClient
import app.sleevesight.ai.ReplayNetClient.Companion.failure
import app.sleevesight.ai.ReplayNetClient.Companion.ok
import app.sleevesight.ai.withoutDetail
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** OpenRouter's client (#1157), against canned answers only: nothing is sent to OpenRouter. */
class OpenRouterProviderTest {
    private val key = Secret("sk-or-v1-test-key")
    private val request =
        CompletionRequest(model = "anthropic/claude-haiku-5.5", prompt = "Say hello in three words.", maxTokens = 256)

    private val clock =
        object : Clock {
            override fun now(): Instant = NOW
        }

    private fun provider(net: ReplayNetClient) = OpenRouterProvider(net, clock = clock)

    private fun response(
        status: Int,
        body: String,
    ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, emptyMap(), body.encodeToByteArray()))

    // Detekt cannot resolve `toList`, so it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun events(
        request: CompletionRequest = this.request,
        answer: (NetRequest) -> NetResult<NetResponse>,
    ): Pair<List<CompletionEvent>, ReplayNetClient> {
        val net = ReplayNetClient(answer)
        return provider(net).stream(request, key).toList().withoutDetail() to net
    }

    private fun sse(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

    private val reply =
        sse(
            """{"choices":[{"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":" there, friend"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}""",
            "[DONE]",
        )

    @Test
    fun `is OpenRouter's client, and takes no tools`() {
        val provider = provider(ReplayNetClient { ok("") })
        assertEquals(ProviderId.OPENROUTER, provider.id)
        assertFalse(provider.usesTools)
    }

    @Test
    fun `a streamed reply becomes its deltas, then done with the last chunk's usage`() =
        runTest {
            val (events, _) = events { ok(": OPENROUTER PROCESSING\n\n" + reply) }
            assertEquals(
                listOf(
                    CompletionEvent.Delta("Hello"),
                    CompletionEvent.Delta(" there, friend"),
                    CompletionEvent.Done(Usage(12, 5), StopReason.END),
                ),
                events,
            )
        }

    @Test
    fun `the request is a chat completion with the system prompt first and the key only in its secrets`() =
        runTest {
            val (_, net) =
                events(request.copy(system = "Be brief.", effort = ReasoningEffort.MEDIUM, maxTokens = 8192)) {
                    ok(reply)
                }
            val sent = net.requests.single()
            assertEquals("https://openrouter.ai/api/v1/chat/completions", sent.url)
            assertEquals(NetMethod.POST, sent.method)
            assertEquals(mapOf("authorization" to "Bearer sk-or-v1-test-key"), sent.secrets.headers)
            assertFalse("sk-or" in sent.toString() + sent.url + sent.headers + sent.body!!.text)
            // No attribution headers: they would list the app in OpenRouter's rankings.
            assertFalse(sent.headers.keys.any { it.equals("http-referer", true) || it.equals("x-title", true) })
            val body = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals("anthropic/claude-haiku-5.5", body["model"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("Be brief.", messages[0]["content"]!!.jsonPrimitive.content)
            assertEquals(8192, body["max_tokens"]!!.jsonPrimitive.int)
            assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("medium", (body["reasoning"] as JsonObject)["effort"]!!.jsonPrimitive.content)
        }

    @Test
    fun `without an effort, no reasoning is sent and the limit is at most the default`() =
        runTest {
            val (_, net) = events(request.copy(maxTokens = 16384)) { ok(reply) }
            val body =
                Json
                    .parseToJsonElement(
                        net.requests
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertNull(body["reasoning"])
            assertEquals(CompletionRequest.DEFAULT_MAX_TOKENS, body["max_tokens"]!!.jsonPrimitive.int)
            assertEquals(1, body["messages"]!!.jsonArray.size)
        }

    @Test
    fun `reasoning in the stream is not shown`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            """{"choices":[{"delta":{"content":"","reasoning":"Thinking it over"}}]}""",
                            """{"choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}]}""",
                            "[DONE]",
                        ),
                    )
                }
            assertEquals(listOf(CompletionEvent.Delta("Hi"), CompletionEvent.Done(Usage(0, 0), StopReason.END)), events)
        }

    @Test
    fun `length and content_filter are the token limit and a refusal`() =
        runTest {
            for ((reason, stop) in listOf("length" to StopReason.MAX_TOKENS, "content_filter" to StopReason.REFUSAL)) {
                val chunk = """{"choices":[{"delta":{},"finish_reason":"$reason"}]}"""
                val (events, _) = events { ok(sse(chunk, "[DONE]")) }
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Done(Usage(0, 0), stop)), events, reason)
            }
        }

    @Test
    fun `an error mid-reply ends it with its kind, marked generated`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            """{"choices":[{"delta":{"content":"Hel"}}]}""",
                            """{"error":{"code":502,"message":"Provider disconnected"},""" +
                                """"choices":[{"delta":{"content":""},"finish_reason":"error"}]}""",
                        ),
                    )
                }
            assertEquals(
                listOf(CompletionEvent.Delta("Hel"), CompletionEvent.Error(AiErrorKind.SERVER, generated = true)),
                events,
            )
        }

    @Test
    fun `a moderation refusal in the stream ends as a refusal`() =
        runTest {
            val (events, _) = events { ok(sse("""{"error":{"code":403,"message":"flagged"}}""")) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Done(Usage(0, 0), StopReason.REFUSAL)), events)
        }

    @Test
    fun `a 403 answer for moderation ends as a refusal, and never as a key refused`() =
        runTest {
            val flagged =
                """{"error":{"code":403,"message":"Your chosen model requires moderation and """ +
                    """your input was flagged"}}"""
            val (events, _) = events { response(403, flagged) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Done(Usage(0, 0), StopReason.REFUSAL)), events)
        }

    @Test
    fun `a 403 answer about the key is a key refused`() =
        runTest {
            val disabled = """{"error":{"code":403,"message":"This API key is disabled"}}"""
            val (events, _) = events { response(403, disabled) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.UNAUTHORIZED)), events)
        }

    @Test
    fun `a 403 to the model list is a key refused`() =
        runTest {
            val forbidden = """{"error":{"code":403,"message":"Forbidden"}}"""
            val listed = provider(ReplayNetClient { response(403, forbidden) })
            assertEquals(ModelList.Failed(AiErrorKind.UNAUTHORIZED), listed.models(key))
        }

    @Test
    fun `OpenRouter's host policy passes its 403 and 429 on, for their bodies`() {
        assertTrue(OpenRouterProvider.HOST_POLICY.passOn403)
        assertTrue(OpenRouterProvider.HOST_POLICY.passOn429)
    }

    @Test
    fun `a stream that ends without a finish reason broke off`() =
        runTest {
            val (events, _) = events { ok(sse("""{"choices":[{"delta":{"content":"Hel"}}]}""")) }
            assertEquals(
                listOf(
                    CompletionEvent.Delta("Hel"),
                    CompletionEvent.Error(
                        AiErrorKind.INCOMPLETE,
                        breakoff = Breakoff(BreakoffCause.NO_END, Usage(0, 0)),
                        generated = true,
                    ),
                ),
                events,
            )
        }

    @Test
    fun `an unreadable chunk ends the stream as unexpected`() =
        runTest {
            val (events, _) = events { ok(sse("not json", """{"choices":[{"delta":{"content":"late"}}]}""")) }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE)), events)
        }

    @Test
    fun `error answers are told apart by their status and message`() =
        runTest {
            val cases =
                listOf(
                    response(402, """{"error":{"code":402,"message":"Insufficient credits"}}""") to AiErrorKind.BILLING,
                    response(
                        429,
                        """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day."}}""",
                    ) to AiErrorKind.QUOTA_EXHAUSTED,
                    response(429, """{"error":{"code":429,"message":"Too many"}}""") to AiErrorKind.RATE_LIMITED,
                    response(
                        400,
                        """{"error":{"code":400,"message":"x/y is not a valid model ID"}}""",
                    ) to AiErrorKind.MODEL_NOT_FOUND,
                    response(400, """{"error":{"code":400,"message":"Bad"}}""") to AiErrorKind.INVALID_REQUEST,
                    response(404, """{"error":{"code":404,"message":"No endpoints"}}""") to AiErrorKind.MODEL_NOT_FOUND,
                    failure(NetError.Unauthorized(401)) to AiErrorKind.UNAUTHORIZED,
                    failure(NetError.Unauthorized(403)) to AiErrorKind.UNAUTHORIZED,
                )
            for ((answer, kind) in cases) {
                val (events, _) = events { answer }
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events, kind.name)
            }
        }

    @Test
    fun `the models offered are Claude, GPT, Gemini and the free ones, named without the company`() =
        runTest {
            val list =
                """{"data":[
                {"id":"anthropic/claude-haiku-5.5","name":"Anthropic: Claude Haiku 5.5","created":300,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"anthropic/claude-haiku-5.5:batch","name":"Anthropic: Claude Haiku 5.5 (batch)","created":300,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"openai/gpt-6-luna","name":"OpenAI: GPT-6 Luna","created":200,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"openai/o4-mini","name":"OpenAI: o4 Mini","created":100,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"openai/oss-120b","name":"OpenAI: gpt-oss","created":100,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"openai/gpt-5-image","name":"OpenAI: GPT-5 Image","created":400,
                 "architecture":{"input_modalities":["text"],"output_modalities":["image","text"]}},
                {"id":"google/gemini-3.5-flash-lite","name":"Google: Gemini 3.5 Flash Lite","created":250,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"google/gemini-old","name":"Google: Gemini Old","created":50,"expiration_date":"2026-11-01",
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"meta/llama-5","name":"Meta: Llama 5","created":500,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"nvidia/nemotron-3.5-lightning:free","name":"NVIDIA: Nemotron 3.5 Lightning (free)","created":20,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"google/gemma-4-31b-it:free","name":"Google: Gemma 4 31B","created":30,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}}
                ]}"""
            val net = ReplayNetClient { ok(list) }
            val listed = provider(net).models(key) as ModelList.Listed
            assertEquals(
                listOf(
                    "anthropic/claude-haiku-5.5" to "Claude Haiku 5.5",
                    "google/gemini-3.5-flash-lite" to "Gemini 3.5 Flash Lite",
                    "openai/gpt-6-luna" to "GPT-6 Luna",
                    "openai/o4-mini" to "o4 Mini",
                    "google/gemma-4-31b-it:free" to "Google: Gemma 4 31B (free)",
                    "nvidia/nemotron-3.5-lightning:free" to "NVIDIA: Nemotron 3.5 Lightning (free)",
                ),
                listed.models.map { it.id to it.displayName },
            )
            val sent = net.requests.single()
            assertEquals("https://openrouter.ai/api/v1/models", sent.url)
            assertEquals(mapOf("authorization" to "Bearer sk-or-v1-test-key"), sent.secrets.headers)
        }

    @Test
    fun `a model list refused for the key says so`() =
        runTest {
            val listed = provider(ReplayNetClient { failure(NetError.Unauthorized(401)) }).models(key)
            assertEquals(ModelList.Failed(AiErrorKind.UNAUTHORIZED), listed)
        }

    @Test
    fun `the default model is offered`() {
        assertTrue(
            OpenRouterProvider.offers(
                WireModel(
                    "anthropic/claude-haiku-5.5",
                    architecture = WireArchitecture(listOf("text"), listOf("text")),
                ),
            ),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-17T12:00:00Z")
    }
}
