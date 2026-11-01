@file:Suppress("MaxLineLength") // The services' events, each on one line as they come.

package app.sleevesight.ai

import app.sleevesight.ai.ReplayNetClient.Companion.ok
import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolResult
import app.sleevesight.core.ai.ToolSpec
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
import app.sleevesight.net.RetryPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tool use (#801) on Anthropic's and OpenAI's clients, from streams in the shape each service sends:
 * a turn that calls the search tool ends with its calls and the turn to send back, then Done with
 * [StopReason.TOOL_USE]; the next request declares the tools, sends that turn and the results, and
 * with `toolsAllowed` false asks for no call (`tool_choice` none).
 */
class ToolUseTest {
    private val tool =
        ToolSpec("exa_search", "Searches the web.", """{"type":"object","properties":{"query":{"type":"string"}}}""")
    private val request = CompletionRequest("gpt-5.5", "Question?", 256, tools = listOf(tool))

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" }

    private fun body(net: ReplayNetClient): JsonObject =
        Json
            .parseToJsonElement(
                net.requests
                    .last()
                    .body!!
                    .text,
            ).jsonObject

    // An Anthropic turn that reasons, says a word, and calls the tool with its input in two pieces.
    private val anthropicTurn =
        sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":50}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Search it."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Searching."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"exa_search","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"Gr"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"ace\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
            """{"type":"message_stop"}""",
        )

    @Test
    fun anthropicsTurnThatCallsTheToolEndsWithItsCallsAndTheTurnToSendBack() =
        runTest {
            val net = ReplayNetClient { ok(anthropicTurn) }
            val events = AnthropicProvider(net, RetryPolicy()).stream(request, Secret("k")).toList()
            val use = assertIs<CompletionEvent.ToolUse>(events[events.size - 2])
            assertEquals(listOf(ToolCall("toolu_1", "exa_search", """{"query":"Grace"}""")), use.turn.calls)
            val replay = Json.parseToJsonElement(use.turn.replay).jsonArray
            assertEquals(
                listOf("thinking", "text", "tool_use"),
                replay.map { it.jsonObject["type"]!!.jsonPrimitive.content },
            )
            assertEquals("sig", replay[0].jsonObject["signature"]!!.jsonPrimitive.content)
            assertEquals("Search it.", replay[0].jsonObject["thinking"]!!.jsonPrimitive.content)
            assertEquals(CompletionEvent.Done(Usage(50, 20), StopReason.TOOL_USE), events.last())
            // The first request declares the tool, with its schema.
            val sent = body(net)
            assertEquals(
                "exa_search",
                sent["tools"]!!
                    .jsonArray[0]
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            assertIs<JsonObject>(sent["tools"]!!.jsonArray[0].jsonObject["input_schema"])
            assertTrue("tool_choice" !in sent)
        }

    @Test
    fun anthropicsNextRequestSendsTheTurnAndTheResultsAndMayForbidACall() =
        runTest {
            val first = ReplayNetClient { ok(anthropicTurn) }
            val turn =
                AnthropicProvider(first, RetryPolicy())
                    .stream(request, Secret("k"))
                    .toList()
                    .filterIsInstance<CompletionEvent.ToolUse>()
                    .single()
                    .turn
            val net = ReplayNetClient { ok(anthropicTurn) }
            val next =
                request.copy(
                    exchanges =
                        listOf(
                            ToolExchange(
                                turn,
                                listOf(
                                    ToolResult("toolu_1", "PAGES"),
                                    ToolResult("toolu_2", "The search failed.", isError = true),
                                ),
                            ),
                        ),
                    toolsAllowed = false,
                )
            AnthropicProvider(net, RetryPolicy()).stream(next, Secret("k")).toList()
            val sent = body(net)
            val messages = sent["messages"]!!.jsonArray
            assertEquals(
                listOf("user", "assistant", "user"),
                messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
            )
            assertEquals(Json.parseToJsonElement(turn.replay), messages[1].jsonObject["content"])
            val result = messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
            assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
            assertEquals("PAGES", result["content"]!!.jsonPrimitive.content)
            assertTrue("is_error" !in result)
            val failed = messages[2].jsonObject["content"]!!.jsonArray[1].jsonObject
            assertEquals("true", failed["is_error"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""{"type":"none"}"""), sent["tool_choice"])
        }

    // An OpenAI response that reasons and calls the tool.
    private val openAiTurn =
        sse(
            """{"type":"response.created","response":{"status":"in_progress"}}""",
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"exa_search","arguments":"{\"query\":\"Grace\"}","status":"completed"}}""",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":40,"output_tokens":12}}}""",
        )

    @Test
    fun openAisResponseThatCallsTheToolEndsWithItsCallsAndItsItems() =
        runTest {
            val net = ReplayNetClient { ok(openAiTurn) }
            val events = OpenAiProvider(net, RetryPolicy()).stream(request, Secret("k")).toList()
            val use = assertIs<CompletionEvent.ToolUse>(events[events.size - 2])
            assertEquals(listOf(ToolCall("call_1", "exa_search", """{"query":"Grace"}""")), use.turn.calls)
            assertEquals(2, Json.parseToJsonElement(use.turn.replay).jsonArray.size)
            assertEquals(CompletionEvent.Done(Usage(40, 12), StopReason.TOOL_USE), events.last())
            val sent = body(net)
            assertEquals(
                "function",
                sent["tools"]!!
                    .jsonArray[0]
                    .jsonObject["type"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("reasoning.encrypted_content", sent["include"]!!.jsonArray[0].jsonPrimitive.content)
        }

    @Test
    fun openAisNextRequestSendsTheItemsAndTheOutputsAndMayForbidACall() =
        runTest {
            val turn =
                OpenAiProvider(ReplayNetClient { ok(openAiTurn) }, RetryPolicy())
                    .stream(request, Secret("k"))
                    .toList()
                    .filterIsInstance<CompletionEvent.ToolUse>()
                    .single()
                    .turn
            val net = ReplayNetClient { ok(openAiTurn) }
            val next =
                request.copy(
                    exchanges = listOf(ToolExchange(turn, listOf(ToolResult("call_1", "PAGES")))),
                    toolsAllowed = false,
                )
            OpenAiProvider(net, RetryPolicy()).stream(next, Secret("k")).toList()
            val sent = body(net)
            val input = assertIs<JsonArray>(sent["input"])
            assertEquals(4, input.size)
            assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
            val output = input[3].jsonObject
            assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
            assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
            assertEquals("none", sent["tool_choice"]!!.jsonPrimitive.content)
        }

    @Test
    fun openAisTurnIsSentBackWholeAMessageBesideTheCallIncluded() =
        runTest {
            val withMessage =
                sse(
                    """{"type":"response.created","response":{"status":"in_progress"}}""",
                    """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"}}""",
                    """{"type":"response.output_item.done","item":{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Searching."}]}}""",
                    """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"exa_search","arguments":"{\"query\":\"Grace\"}"}}""",
                    """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":40,"output_tokens":12}}}""",
                )
            val turn =
                OpenAiProvider(ReplayNetClient { ok(withMessage) }, RetryPolicy())
                    .stream(request, Secret("k"))
                    .toList()
                    .filterIsInstance<CompletionEvent.ToolUse>()
                    .single()
                    .turn
            val items = Json.parseToJsonElement(turn.replay).jsonArray
            assertEquals(
                listOf("reasoning", "message", "function_call"),
                items.map {
                    it.jsonObject["type"]!!.jsonPrimitive.content
                },
            )
            val net = ReplayNetClient { ok(openAiTurn) }
            val next = request.copy(exchanges = listOf(ToolExchange(turn, listOf(ToolResult("call_1", "PAGES")))))
            OpenAiProvider(net, RetryPolicy()).stream(next, Secret("k")).toList()
            val input = assertIs<JsonArray>(body(net)["input"])
            assertEquals(
                listOf("user", "reasoning", "message", "function_call", "function_call_output"),
                input.map { (it.jsonObject["type"] ?: it.jsonObject.getValue("role")).jsonPrimitive.content },
            )
        }

    @Test
    fun aModelThatDoesNotReasonGetsNoIncludeAndNoUnencryptedReasoningBack() =
        runTest {
            val plain = request.copy(model = "gpt-4.1")
            val net = ReplayNetClient { ok(openAiTurn) }
            OpenAiProvider(net, RetryPolicy()).stream(plain, Secret("k")).toList()
            assertTrue("include" !in body(net))
            // A reasoning item with no encrypted content is not sent back; the call is.
            val turn =
                ToolTurn(
                    listOf(ToolCall("call_1", "exa_search", "{}")),
                    """[{"type":"reasoning","id":"rs_1","summary":[]},""" +
                        """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"exa_search","arguments":"{}"}]""",
                )
            val next = plain.copy(exchanges = listOf(ToolExchange(turn, listOf(ToolResult("call_1", "PAGES")))))
            OpenAiProvider(net, RetryPolicy()).stream(next, Secret("k")).toList()
            val input = assertIs<JsonArray>(body(net)["input"])
            assertEquals(
                listOf("user", "function_call", "function_call_output"),
                input.map { (it.jsonObject["type"] ?: it.jsonObject.getValue("role")).jsonPrimitive.content },
            )
        }

    @Test
    fun aRefusedIncludeIsSentAgainWithoutItWhateverTheEffort() =
        runTest {
            // No effort asked for, so the effort's retry would not run: `include` has its own.
            val refused =
                """{"error":{"type":"invalid_request_error","code":"unsupported_parameter",""" +
                    """"param":"include"}}"""
            var calls = 0
            val net = ReplayNetClient { if (calls++ == 0) ok(refused, status = 400) else ok(openAiTurn) }
            val events = OpenAiProvider(net, RetryPolicy()).stream(request, Secret("k")).toList()
            assertEquals(2, net.requests.size)
            assertTrue("include" in Json.parseToJsonElement(net.requests[0].body!!.text).jsonObject)
            assertTrue("include" !in body(net))
            assertIs<CompletionEvent.Done>(events.last())
        }

    @Test
    fun aRequestWithNoToolsIsSentAsBefore() =
        runTest {
            val net = ReplayNetClient { ok(openAiTurn) }
            OpenAiProvider(net, RetryPolicy()).stream(request.copy(tools = emptyList()), Secret("k")).toList()
            val sent = body(net)
            assertTrue("tools" !in sent && "include" !in sent)
            assertEquals("Question?", sent["input"]!!.jsonPrimitive.content)
        }
}
