package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolSpec
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptDumpTest {
    private val request =
        CompletionRequest(
            model = "claude-haiku-5-5",
            prompt = "Line one\nC:\\path",
            maxTokens = 4096,
            effort = ReasoningEffort.LOW,
            tools = listOf(ToolSpec("web_search", "Searches the web.", "{}")),
            system = "Narrate.",
        )

    @Test
    fun aRequestOpensWithItsDetailsThenItsSystemPromptAndPrompt() {
        assertEquals(
            listOf(
                "#3 request 1/1 provider=anthropic model=claude-haiku-5-5 maxTokens=4096 effort=low " +
                    "tools=web_search toolsAllowed=true exchanges=0 at=T",
                "#3 system 1/1 Narrate.",
                "#3 prompt 1/1 Line one\\nC:\\\\path",
            ),
            PromptDump.requestRecords(3, ProviderId.ANTHROPIC, request, "T"),
        )
        val plain = PromptDump.requestRecords(4, ProviderId.GOOGLE, CompletionRequest("m", "p"), "T")
        assertEquals(listOf("request", "prompt"), plain.map { it.split(' ')[1] })
        assertTrue(plain[0], "effort=default tools=none" in plain[0])
    }

    @Test
    fun aLongTextIsLoggedInPartsEachOneLine() {
        val records = PromptDump.records(7, "prompt", "y\n".repeat(600))
        assertEquals(listOf("#7 prompt 1/2 ", "#7 prompt 2/2 "), records.map { it.substring(0, 14) })
        assertTrue(records.none { '\n' in it })
        assertEquals("y\\n".repeat(600), records.joinToString("") { it.substringAfter("/2 ") })
    }

    @Test
    fun theEndSaysTheTokensAndStopReasonOrTheError() {
        assertEquals(
            listOf("#2 done 1/1 inputTokens=10 outputTokens=20 stopReason=max_tokens ms=5"),
            PromptDump.endRecords(2, CompletionEvent.Done(Usage(10, 20), StopReason.MAX_TOKENS), 5),
        )
        val broken =
            CompletionEvent.Error(
                AiErrorKind.INCOMPLETE,
                breakoff = Breakoff(BreakoffCause.CONNECTION_LOST, Usage(1, 2), "Socket closed"),
            )
        assertEquals(
            listOf(
                "#2 error 1/1 kind=incomplete breakoff=connection_lost inputTokens=1 outputTokens=2 " +
                    "detail=Socket_closed ms=9",
            ),
            PromptDump.endRecords(2, broken, 9),
        )
    }

    @Test
    fun theWatchedProviderPassesEverythingThrough() {
        val events =
            listOf(CompletionEvent.Delta("Hello"), CompletionEvent.Done(Usage(1, 1), StopReason.END))
        val provider =
            object : AiProvider {
                override val id = ProviderId.ANTHROPIC
                override val usesTools = true

                override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

                override fun stream(
                    request: CompletionRequest,
                    key: Secret,
                ): Flow<CompletionEvent> = flowOf(*events.toTypedArray())
            }
        val watched = PromptDump.watching(provider)

        assertEquals(events, runBlocking { watched.stream(request, Secret("k")).toList() })
        assertEquals(true, watched.usesTools)
        assertEquals(ProviderId.ANTHROPIC, watched.id)
    }
}
