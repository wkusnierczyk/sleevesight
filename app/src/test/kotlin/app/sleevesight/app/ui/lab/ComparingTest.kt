package app.sleevesight.app.ui.lab

import app.sleevesight.app.lab.PromptLab
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolResult
import app.sleevesight.core.ai.ToolSpec
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.LabMark
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.LabSettings
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Instant

/**
 * A comparison (#1003): each variant taking part written from the same request, its own opening
 * line in place, with no tools; shuffled; a failure said by its kind; and the log holding each
 * version's prompt, text, mark and note.
 */
class ComparingTest {
    private val sent = mutableListOf<CompletionRequest>()

    // Replies with the opening line it was sent; for variant B (#1003's "One fact") a time-out, for A
    // ("Music first") a call for a tool.
    private val provider =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList = throw UnsupportedOperationException()

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                flow {
                    sent += request
                    val opening = LabPrompts.opening(request)
                    when {
                        "Tell only" in opening -> emit(CompletionEvent.Error(AiErrorKind.TIMED_OUT))
                        "sounds like" in opening -> emit(CompletionEvent.ToolUse(ToolTurn(emptyList(), "")))
                        else -> {
                            emit(CompletionEvent.Delta(opening.take(OPENING)))
                            emit(CompletionEvent.Done(Usage(1, 1), StopReason.END))
                        }
                    }
                }
        }

    private val request =
        CompletionRequest(
            "claude-haiku-5-5",
            "<track>\nTitle: Sundown\n</track>",
            tools = listOf(ToolSpec("web_search", "", "{}")),
            system = InsightPrompt.system(false),
        )

    private val subject =
        LabSubject(PromptKind.KEY, "Sundown", "Gordon Lightfoot", null, "card-1")

    private fun TestScope.start(settings: LabSettings): Comparing =
        Comparing.start(
            subject,
            PromptLab.Sent(request, provider, Secret("k"), Instant.parse("2026-11-17T19:00:00Z")),
            settings,
            this,
            failure = { it.name },
            stopped = "stopped",
            random = Random(1),
        )

    @Test
    fun eachVariantIsWrittenFromTheSameRequestWithItsOwnOpeningAndNoFurtherSearch() =
        runTest {
            val run = start(LabSettings().compare(PromptKind.KEY, LabPrompts.CUSTOM, on = true))
            advanceUntilIdle()

            assertEquals(4, sent.size)
            assertTrue(sent.all { !it.toolsAllowed && it.tools == request.tools && it.prompt == request.prompt })
            assertEquals(listOf(1, 2, 3, 4), run.versions.map { it.shown })
            assertEquals(
                setOf(LabPrompts.RELEASE, "A", "B", LabPrompts.CUSTOM),
                run.versions.map { it.id }.toSet(),
            )
            val byId = run.versions.associateBy { it.id }
            assertEquals(InsightPrompt.ROLE.take(OPENING), byId.getValue(LabPrompts.RELEASE).text)
            // *Custom* until edited is the release line itself.
            assertEquals(InsightPrompt.ROLE.take(OPENING), byId.getValue(LabPrompts.CUSTOM).text)
            assertEquals(Failure.SEARCHED.name, byId.getValue("A").failed)
            assertEquals(Failure.TIMED_OUT.name, byId.getValue("B").failed)
            assertFalse(run.writing)
        }

    @Test
    fun theLogHoldsEachVersionsPromptTextMarkAndNote() =
        runTest {
            val run = start(LabSettings())
            advanceUntilIdle()
            val release = run.versions.single { it.id == LabPrompts.RELEASE }
            release.mark = LabMark.GOOD
            release.note = " Plain. "

            val logged = run.logged("0.8.0-debug", Instant.parse("2026-11-17T20:00:00Z"))

            assertEquals(PromptKind.KEY, logged.kind)
            assertEquals("Sundown", logged.title)
            assertEquals("ANTHROPIC", logged.service)
            assertEquals(request.prompt, logged.evidence)
            assertEquals(InsightPrompt.ROLE, logged.instructions[LabPrompts.RELEASE])
            val version = logged.versions.single { it.prompt == LabPrompts.RELEASE }
            assertEquals(LabMark.GOOD, version.mark)
            assertEquals("Plain.", version.note)
            assertEquals(release.shown, version.shown)
        }

    @Test
    fun aRequestWithCompletedSearchesIsComparedWithThePagesItFoundAndTheLogKeepsThem() =
        runTest {
            val searched =
                request.copy(
                    exchanges =
                        listOf(
                            ToolExchange(
                                ToolTurn(listOf(ToolCall("c1", "web_search", "{\"query\": \"Sundown\"}")), ""),
                                listOf(ToolResult("c1", "[S1] (web, song) Sundown was recorded in 1973.")),
                            ),
                        ),
                )
            val run =
                Comparing.start(
                    subject,
                    PromptLab.Sent(searched, provider, Secret("k"), Instant.parse("2026-11-17T19:00:00Z")),
                    LabSettings(),
                    this,
                    failure = { it.name },
                    stopped = "stopped",
                )
            advanceUntilIdle()

            assertTrue(sent.all { it.exchanges == searched.exchanges && !it.toolsAllowed })
            val evidence = run.logged("0.8.0-debug").evidence
            assertTrue(evidence, evidence.startsWith(searched.prompt))
            assertTrue(evidence, "[S1] (web, song) Sundown was recorded in 1973." in evidence)
        }

    @Test
    fun withNoRequestSentNothingIsWritten() =
        runTest {
            val run = Comparing.start(subject, null, LabSettings(), this, { it.name }, "stopped")
            advanceUntilIdle()
            assertTrue(run.versions.isEmpty())
            assertTrue(sent.isEmpty())
        }

    private companion object {
        const val OPENING = 40
    }
}
