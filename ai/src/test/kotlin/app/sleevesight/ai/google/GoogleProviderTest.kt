package app.sleevesight.ai.google

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
import kotlinx.serialization.json.JsonObject
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
class GoogleProviderTest {
    private val key = Secret("AIza-test-key")
    private val request =
        CompletionRequest(model = "gemini-3.5-flash-lite", prompt = "Say hello in three words.", maxTokens = 256)
    private val stream = "google/stream-generate-content.sse"

    private val clock =
        object : Clock {
            override fun now(): Instant = NOW
        }

    /** A provider whose clock stands at [NOW]. */
    private fun provider(net: NetClient) = GoogleProvider(net, RetryPolicy(), clock = clock)

    /** A response with [status], [body] and [headers]. */
    private fun response(
        status: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
    ): NetResult<NetResponse> = NetResult.Success(NetResponse(status, headers, body.encodeToByteArray()))

    /** Google's `429 RESOURCE_EXHAUSTED`, for the quota [quotaId], with a `RetryInfo` of [delay] if given. */
    private fun exhausted(
        quotaId: String?,
        delay: String? = null,
    ): String {
        val quota =
            quotaId?.let {
                """{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":""" +
                    """"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"$it"}]}"""
            }
        val retry = delay?.let { """{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$it"}""" }
        val details = listOfNotNull(quota, retry).joinToString(",")
        return """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED",""" +
            """"details":[$details]}}"""
    }

    // Detekt 1.23.8 cannot resolve `toList` (from a library built with a newer Kotlin than its own), so
    // it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    private suspend fun events(
        request: CompletionRequest = this.request,
        answer: (NetRequest) -> NetResult<NetResponse>,
    ): Pair<List<CompletionEvent>, ReplayNetClient> {
        val net = ReplayNetClient(answer)
        return provider(net).stream(request, key).toList() to net
    }

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\r\n\r\n" }

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

    private fun chunk(
        text: String,
        finish: String? = null,
    ): String {
        val reason = finish?.let { ""","finishReason":"$it"""" }.orEmpty()
        return """{"candidates":[{"content":{"parts":[{"text":"$text"}],"role":"model"}$reason}]}"""
    }

    @Test
    fun `a recorded stream becomes its text deltas and Done with the usage`() =
        runTest {
            val (events, _) = events { ok(recorded(stream)) }

            assertEquals(RECORDED_TEXT, text(events))
            // Output counts the thinking tokens: 94 of the reply and 1132 of thought. The last chunk
            // has the finish reason, an empty text part and the thought signature.
            assertEquals(
                CompletionEvent.Done(Usage(inputTokens = 1339, outputTokens = 1226), StopReason.END),
                events.last(),
            )
            assertEquals(5, events.count { it is CompletionEvent.Delta })
            assertEquals(1, events.count { it !is CompletionEvent.Delta })
        }

    @Test
    fun `the request is one streaming POST to the model, the key only in the secrets`() =
        runTest {
            val (_, net) = events { ok(recorded(stream)) }

            val sent = net.requests.single()
            assertEquals(
                "https://generativelanguage.googleapis.com/v1/models/gemini-3.5-flash-lite:streamGenerateContent",
                sent.url,
            )
            assertEquals(mapOf("alt" to "sse"), sent.query)
            assertEquals(NetMethod.POST, sent.method)
            assertEquals(mapOf("x-goog-api-key" to "AIza-test-key"), sent.secrets.headers)
            assertEquals(emptyMap(), sent.secrets.query)
            assertEquals(mapOf("accept" to "text/event-stream"), sent.headers)
            assertEquals(GoogleProvider.STREAM_TIMEOUT, sent.timeout)
            assertEquals(
                Json.parseToJsonElement(
                    """{"contents":[{"role":"user","parts":[{"text":"Say hello in three words."}]}],""" +
                        """"generationConfig":{"maxOutputTokens":256}}""",
                ),
                body(net),
            )
            assertEquals("application/json", sent.body!!.contentType)
            assertFalse("AIza" in sent.toString() + sent.url + sent.query + sent.headers + sent.body!!.text)
        }

    @Test
    fun `an effort goes in thinkingConfig, and none leaves the model's default`() =
        runTest {
            for (effort in ReasoningEffort.entries) {
                val (_, net) = events(request.copy(effort = effort)) { ok(recorded(stream)) }
                val config = body(net)["generationConfig"]!!.jsonObject
                assertEquals(
                    Json.parseToJsonElement("""{"thinkingLevel":"${effort.name.lowercase()}"}"""),
                    config["thinkingConfig"],
                    effort.name,
                )
            }
        }

    @Test
    fun `a model that does not take minimal gets low, and a model with no levels gets none`() =
        runTest {
            val (_, newer) =
                events(request.copy(model = "gemini-3.8-flash", effort = ReasoningEffort.MINIMAL)) {
                    ok(recorded(stream))
                }
            assertEquals(
                Json.parseToJsonElement("""{"thinkingLevel":"low"}"""),
                body(newer)["generationConfig"]!!.jsonObject["thinkingConfig"],
            )
            val (_, older) =
                events(request.copy(model = "gemini-2.5-flash", effort = ReasoningEffort.LOW)) {
                    ok(recorded(stream))
                }
            assertEquals(null, body(older)["generationConfig"]!!.jsonObject["thinkingConfig"])

            val levelled =
                listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-argon", "gemini-3-flash-preview")
            for (model in levelled) {
                assertEquals(true, GoogleProvider.takesThinkingLevel(model), model)
            }
            val unlevelled =
                listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemma-3-27b-it")
            for (model in unlevelled) {
                assertEquals(false, GoogleProvider.takesThinkingLevel(model), model)
            }
            for (model in listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3-flash-preview")) {
                assertEquals(true, GoogleProvider.takesMinimal(model), model)
            }
            for (model in listOf("gemini-3.8-flash", "gemini-4-argon", "gemini-3.1-pro-preview", "gemini-3-flashy")) {
                assertEquals(false, GoogleProvider.takesMinimal(model), model)
            }
        }

    @Test
    fun `a model id that would change the route is not sent`() =
        runTest {
            for (model in listOf("../files", "gemini-3.5-flash-lite?alt=json", "models/gemini-3.8-flash", "")) {
                val (events, net) = events(request.copy(model = model)) { ok(recorded(stream)) }
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)), events, model)
                assertEquals(emptyList(), net.requests, model)
            }
        }

    @Test
    fun `thought parts are skipped, text parts join, and the stop reasons map`() =
        runTest {
            val reasons =
                listOf(
                    "STOP" to StopReason.END,
                    "MAX_TOKENS" to StopReason.MAX_TOKENS,
                    "CONTINUATION" to StopReason.MAX_TOKENS,
                    "SAFETY" to StopReason.REFUSAL,
                    "RECITATION" to StopReason.REFUSAL,
                    "PROHIBITED_CONTENT" to StopReason.REFUSAL,
                    "ESCALATION" to StopReason.REFUSAL,
                    "PUP_LIMITED_DISABLED" to StopReason.REFUSAL,
                    "MALFORMED_RESPONSE" to StopReason.OTHER,
                    "SOME_FUTURE_REASON" to StopReason.OTHER,
                )
            for ((wire, reason) in reasons) {
                val (events, _) =
                    events {
                        ok(
                            sse(
                                """{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},""" +
                                    """{"text":"Hi"},{"text":" you"}]}}],"usageMetadata":{"promptTokenCount":10}}""",
                                """{"candidates":[{"content":{"parts":[{"text":""}]}}],"someFutureField":1}""",
                                """{"candidates":[{"content":{"parts":[{"text":"!"}]},"finishReason":"$wire"}],""" +
                                    """"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,""" +
                                    """"thoughtsTokenCount":4}}""",
                            ),
                        )
                    }
                assertEquals(
                    listOf(
                        CompletionEvent.Delta("Hi you"),
                        CompletionEvent.Delta("!"),
                        CompletionEvent.Done(Usage(10, 7), reason),
                    ),
                    events,
                    wire,
                )
            }
        }

    @Test
    fun `usage after the finish reason still counts`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            chunk("Hi", finish = "STOP"),
                            """{"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}""",
                        ),
                    )
                }
            assertEquals(listOf(CompletionEvent.Delta("Hi"), CompletionEvent.Done(Usage(3, 1), StopReason.END)), events)
        }

    @Test
    fun `a blocked prompt is a refusal`() =
        runTest {
            val (events, _) =
                events {
                    ok(sse("""{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":9}}"""))
                }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Done(Usage(9, 0), StopReason.REFUSAL)), events)
        }

    @Test
    fun `an error in place of a chunk ends the stream with its kind`() =
        runTest {
            val (events, _) =
                events {
                    ok(
                        sse(
                            chunk("Half"),
                            """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""",
                            chunk("more", finish = "STOP"),
                        ),
                    )
                }
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.OVERLOADED)), events)
        }

    @Test
    fun `a stream that ends before a finish reason is incomplete`() =
        runTest {
            val (events, _) = events { ok(sse(chunk("Half"))) }
            assertEquals(listOf(CompletionEvent.Delta("Half"), CompletionEvent.Error(AiErrorKind.INCOMPLETE)), events)
        }

    @Test
    fun `data that is not JSON ends the stream as an unexpected response`() =
        runTest {
            val (events, _) = events { ok(sse("not json", chunk("Hi", finish = "STOP"))) }
            assertEquals(listOf(CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE)), events)
        }

    @Test
    fun `transport failures map to their kinds`() =
        runTest {
            val cases =
                listOf(
                    NetError.Unauthorized(403) to CompletionEvent.Error(AiErrorKind.UNAUTHORIZED),
                    NetError.RateLimited(30.seconds) to CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 30.seconds),
                    NetError.Server(503) to CompletionEvent.Error(AiErrorKind.OVERLOADED),
                    NetError.Server(500) to CompletionEvent.Error(AiErrorKind.SERVER),
                    NetError.Server(504) to CompletionEvent.Error(AiErrorKind.SERVER),
                    NetError.Offline("IOException") to CompletionEvent.Error(AiErrorKind.OFFLINE),
                    NetError.InvalidRequest("header") to CompletionEvent.Error(AiErrorKind.INVALID_REQUEST),
                    NetError.Protocol("ProtocolException") to CompletionEvent.Error(AiErrorKind.UNEXPECTED_RESPONSE),
                )
            for ((error, expected) in cases) {
                assertEquals(listOf<CompletionEvent>(expected), events { failure(error) }.first, error.toString())
            }
        }

    @Test
    fun `error responses map by their reason and status, else by the HTTP status`() =
        runTest {
            fun body(
                code: Int,
                status: String,
                reason: String? = null,
            ): String {
                val details =
                    reason
                        ?.let {
                            ""","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$it",""" +
                                """"domain":"googleapis.com"}]"""
                        }.orEmpty()
                return """{"error":{"code":$code,"message":"m","status":"$status"$details}}"""
            }
            val cases =
                listOf(
                    ok(body(400, "INVALID_ARGUMENT", "API_KEY_INVALID"), 400) to AiErrorKind.UNAUTHORIZED,
                    ok(body(400, "INVALID_ARGUMENT"), 400) to AiErrorKind.INVALID_REQUEST,
                    ok(body(400, "FAILED_PRECONDITION"), 400) to AiErrorKind.BILLING,
                    ok(
                        """{"error":{"code":400,"message":"User location is not supported for the API use.",""" +
                            """"status":"FAILED_PRECONDITION"}}""",
                        400,
                    ) to AiErrorKind.UNAVAILABLE_HERE,
                    ok(body(402, "RESOURCE_EXHAUSTED"), 402) to AiErrorKind.BILLING,
                    ok(body(404, "NOT_FOUND"), 404) to AiErrorKind.MODEL_NOT_FOUND,
                    ok("<html>gateway</html>", 404) to AiErrorKind.MODEL_NOT_FOUND,
                    ok("", 402) to AiErrorKind.BILLING,
                    ok("", 413) to AiErrorKind.REQUEST_TOO_LARGE,
                    ok(body(418, "SOME_FUTURE_STATUS"), 418) to AiErrorKind.UNEXPECTED_RESPONSE,
                )
            for ((response, kind) in cases) {
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events { response }.first, kind.name)
            }
        }

    @Test
    fun `the connection lost after the reply began leaves an incomplete reply`() =
        runTest {
            val net =
                object : NetClient {
                    override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("not used")

                    override suspend fun stream(
                        request: NetRequest,
                        onLine: suspend (String) -> Unit,
                    ): NetResult<NetResponse> {
                        sse(chunk("Half")).lines().forEach { onLine(it) }
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
            val first = provider(ReplayNetClient { ok(recorded(stream)) }).stream(request, key).first()
            assertEquals(CompletionEvent.Delta(RECORDED_TEXT.substringBefore("197") + "197"), first)
        }

    @Test
    fun `the model list follows its pages and keeps only the text models`() =
        runTest {
            val net =
                ReplayNetClient { request ->
                    val page = if ("pageToken" in request.query) 2 else 1
                    ok(recorded("google/models-page-$page.json"))
                }
            val listed = assertIs<ModelList.Listed>(provider(net).models(key)).models

            assertEquals(
                listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-4-argon", "gemini-3.1-flash-lite"),
                listed.map { it.id },
            )
            assertEquals(ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", null), listed.first())
            // The image and speech models in the pages take `generateContent` but write no text.
            val nonText =
                listOf(
                    "gemini-3.1-flash-lite-image",
                    "gemini-3.8-flash-tts",
                    "gemini-2.5-flash-native-audio-preview-12-2025",
                    "gemini-3.8-live",
                    "gemini-3.5-transcribe",
                    "gemini-nano-banana-2.1",
                    "lyria-3.5",
                    "veo-3.1-generate-preview",
                    "gemini-omni-1.1-flash",
                )
            for (id in nonText) assertEquals(false, GoogleProvider.writesText(id), id)
            for (id in listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-robotics-er-2-preview")) {
                assertEquals(true, GoogleProvider.writesText(id), id)
            }
            assertEquals(
                listOf(null, "Chxtb2RlbHMvZ2VtaW5pLWVtYmVkZGluZy0wMDE="),
                net.requests.map { it.query["pageToken"] },
            )
            net.requests.forEach {
                assertEquals("https://generativelanguage.googleapis.com/v1/models", it.url)
                assertEquals("1000", it.query["pageSize"])
                assertEquals(NetMethod.GET, it.method)
                assertEquals(mapOf("x-goog-api-key" to "AIza-test-key"), it.secrets.headers)
            }
        }

    @Test
    fun `a model list that fails says why`() =
        runTest {
            val provider = { answer: NetResult<NetResponse> -> provider(ReplayNetClient { answer }) }
            assertEquals(
                ModelList.Failed(AiErrorKind.UNAUTHORIZED),
                provider(failure(NetError.Unauthorized(403))).models(key),
            )
            assertEquals(
                ModelList.Failed(AiErrorKind.UNAUTHORIZED),
                provider(
                    ok(
                        """{"error":{"code":400,"status":"INVALID_ARGUMENT",""" +
                            """"details":[{"reason":"API_KEY_INVALID"}]}}""",
                        400,
                    ),
                ).models(key),
            )
            assertEquals(ModelList.Failed(AiErrorKind.UNEXPECTED_RESPONSE), provider(ok("[]")).models(key))
            assertEquals(ModelList.Listed(emptyList()), provider(ok("{}")).models(key))
        }

    @Test
    fun `a daily quota is used up at once, a rate limit keeps its wait`() =
        runTest {
            val daily = response(429, exhausted("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "38s"))
            val (capped, once) = events { daily }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.QUOTA_EXHAUSTED)), capped)
            assertEquals(1, once.requests.size)

            val long = response(429, exhausted("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "38s"))
            val (limited, alone) = events { long }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 38.seconds)), limited)
            assertEquals(1, alone.requests.size)
        }

    @Test
    fun `a monthly spending cap without details is billing at once, other 429s stay rate limits`() =
        runTest {
            fun exhausted(message: String) =
                response(429, """{"error":{"code":429,"message":"$message","status":"RESOURCE_EXHAUSTED"}}""")
            val cap =
                exhausted(
                    "Your billing account has exceeded its monthly spending cap. Please go to AI Studio at " +
                        "https://ai.studio/billing to manage your billing.",
                )
            val (capped, once) = events { cap }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.BILLING)), capped)
            assertEquals(1, once.requests.size)
            assertEquals(
                ModelList.Failed(AiErrorKind.BILLING),
                provider(ReplayNetClient { exhausted("Your project has exceeded its monthly spend cap.") }).models(key),
            )
            for (message in listOf(
                "You exceeded your current quota.",
                "Spend-based rate limit exceeded per 10 minutes.",
            )) {
                val (limited, twice) = events { exhausted(message) }
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED)), limited, message)
                assertEquals(2, twice.requests.size, message)
            }
        }

    @Test
    fun `a rate limit within the cap is sent once more after its RetryInfo or Retry-After`() =
        runTest {
            val cases =
                listOf(
                    response(429, exhausted("GenerateRequestsPerMinutePerProjectPerModel", "1.5s")) to 1_500L,
                    response(429, exhausted(null), mapOf("Retry-After" to listOf("3"))) to 3_000L,
                    response(429, exhausted(null)) to RetryPolicy().delayAfter(1).inWholeMilliseconds,
                )
            for ((limited, wait) in cases) {
                var calls = 0
                val started = currentTime
                val (events, net) =
                    events {
                        calls++
                        if (calls == 1) limited else ok(recorded(stream))
                    }
                assertEquals(RECORDED_TEXT, text(events))
                assertIs<CompletionEvent.Done>(events.last())
                assertEquals(2, net.requests.size)
                assertEquals(net.requests[0].body!!.text, net.requests[1].body!!.text)
                assertEquals(wait, currentTime - started)
            }
        }

    @Test
    fun `a rate limit is sent again once only, and a cancel during its wait sends nothing more`() =
        runTest {
            val limited = response(429, exhausted("GenerateRequestsPerMinutePerProjectPerModel", "2s"))
            val (again, twice) = events { limited }
            assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(AiErrorKind.RATE_LIMITED, 2.seconds)), again)
            assertEquals(2, twice.requests.size)

            val net = ReplayNetClient { response(429, exhausted(null, "5s")) }
            val collecting = launch { provider(net).stream(request, key).toList() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, net.requests.size)
            assertTrue(collecting.isActive)
            collecting.cancel()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(collecting.isCompleted)
            assertEquals(1, net.requests.size)
        }

    @Test
    fun `its host's policy has a 429 passed on, so the body can say why`() {
        assertTrue(GoogleProvider.HOST_POLICY.passOn429)
        assertEquals("https://${GoogleProvider.HOST}", GoogleProvider.BASE_URL)
        assertEquals(1.5.seconds, protobufDuration("1.5s"))
        assertEquals(null, protobufDuration("soon"))
    }

    @Test
    fun `a model list over a daily quota says its quota is used up`() =
        runTest {
            val daily = response(429, exhausted("GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
            assertEquals(ModelList.Failed(AiErrorKind.QUOTA_EXHAUSTED), provider(ReplayNetClient { daily }).models(key))
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-21T12:00:00Z")

        /** The reply in `google/stream-generate-content.sse`. */
        const val RECORDED_TEXT: String =
            "Written by Gordon Lightfoot, the folk rock song \"Sundown\" was released in early 1974 " +
                "[MB1][MB2][WD1]. The track became a major success, reaching number one on the U.S. Billboard " +
                "Hot 100 and on Canada's national singles chart [W2]. This recording features acoustic guitar " +
                "from Lightfoot and Terry Clements, with production by Lenny Waronker [MB3][MB4][MB6]."
    }
}
