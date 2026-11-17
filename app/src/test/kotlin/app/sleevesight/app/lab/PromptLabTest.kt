package app.sleevesight.app.lab

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.ToolCall
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolResult
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptChoice
import app.sleevesight.core.synthesis.PromptChoices
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Instant

/**
 * The prompt lab (#1003): its choices taken once per pipeline, and kept by a call under way whatever
 * the lab is switched to; nothing varied with developer mode off; each card's request remembered by
 * the card, as last sent, with its tools' answers; the settings and the log outlast the process.
 */
class PromptLabTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sentRequests = mutableListOf<CompletionRequest>()
    private var gate: CompletableDeferred<Unit>? = null

    private val provider =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList = throw UnsupportedOperationException()

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                flow {
                    sentRequests += request
                    emit(CompletionEvent.Delta(REPLY))
                    gate?.await()
                    emit(CompletionEvent.Done(Usage(1, 1), StopReason.END))
                }
        }

    private fun keyRequest(card: String? = "card-1") =
        CompletionRequest(
            "claude-haiku-5-5",
            "<track>\nTitle: Sundown\n</track>",
            system = InsightPrompt.system(false),
            card = card,
        )

    // Developer mode and the lab's switch on (#1388): the lab varies what it is set to.
    private fun PromptLab.switchOn() {
        developerMode(true)
        update { it.withEnabled(true) }
    }

    private fun TestScope.lab(): PromptLab =
        // The test's own scope: `advanceUntilIdle` leaves the background scope's work unrun.
        PromptLab(folder.root, this, StandardTestDispatcher(testScheduler))

    @Test
    fun withDeveloperModeOffNothingIsChosenAndTheClientIsLeftAsItIs() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.update { it.use(PromptKind.KEY, "B") }
            assertSame(PromptChoices.NONE, lab.choices())
            assertSame(provider, lab.through(provider, lab.choices()))
        }

    @Test
    fun aCallKeepsTheChoicesItsPipelineWasBuiltWithThoughTheLabIsSwitchedWhileItStreams() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.switchOn()
            lab.update { it.use(PromptKind.KEY, "B") }
            val chosen = lab.choices()
            val b = LabPrompts.variant(PromptKind.KEY, "B")!!.instructions
            assertEquals(PromptChoice("B", b), chosen[PromptKind.KEY])
            gate = CompletableDeferred()
            val varied = lab.through(provider, chosen)
            val call = launch { varied.stream(keyRequest(), Secret("k")).toList() }
            advanceUntilIdle()
            // Switched back to the release prompt while the reply streams.
            lab.update { it.use(PromptKind.KEY, LabPrompts.RELEASE) }
            gate?.complete(Unit)
            call.join()

            assertTrue(sentRequests.single().system!!.startsWith(InsightPrompt.ROLE + " Tell only"))
            assertEquals("B", chosen[PromptKind.KEY]?.id)
            // A pipeline built now takes the release prompt.
            assertEquals(PromptChoice(LabPrompts.RELEASE, null), lab.choices()[PromptKind.KEY])
        }

    @Test
    fun eachCardsRequestIsRememberedByTheCardAsLastSentWithItsToolsAnswers() =
        runTest {
            val lab = lab()
            lab.switchOn()
            val varied = lab.through(provider, lab.choices())
            val first = keyRequest()
            val answered =
                first.copy(
                    exchanges =
                        listOf(
                            ToolExchange(
                                ToolTurn(listOf(ToolCall("c1", "web_search", "{}")), ""),
                                listOf(ToolResult("c1", "[S1] A page on Sundown.")),
                            ),
                        ),
                )
            varied.stream(first, Secret("k")).toList()
            varied.stream(answered, Secret("k")).toList()

            assertEquals(answered, lab.sent("card-1")?.request)
            assertNull(lab.sent("card-2"))
            assertNull(lab.sent(null))
        }

    @Test
    fun twoCardsWithTheSameRequestAndReplyAreRememberedApart() =
        runTest {
            val lab = lab()
            lab.switchOn()
            lab.update { it.use(PromptKind.KEY, "A") }
            val withA = lab.through(provider, lab.choices())
            lab.update { it.use(PromptKind.KEY, "B") }
            val withB = lab.through(provider, lab.choices())
            withA.stream(keyRequest("card-a"), Secret("k")).toList()
            withB.stream(keyRequest("card-b"), Secret("k")).toList()

            assertEquals("card-a", lab.sent("card-a")?.request?.card)
            assertEquals("card-b", lab.sent("card-b")?.request?.card)
            assertNotSame(lab.sent("card-a"), lab.sent("card-b"))
            // Each written by its own variant, though their replies are the same.
            assertTrue(sentRequests[0].system!!.contains("open with that"))
            assertTrue(sentRequests[1].system!!.contains("Tell only"))
        }

    @Test
    fun aChangeOfTheChoicesAsksForARebuild() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            val asked = mutableListOf<Unit>()
            val watching = launch { lab.rebuilds.collect { asked += it } }
            advanceUntilIdle()
            lab.switchOn()
            advanceUntilIdle()
            lab.update { it.use(PromptKind.SONG, "A") }
            advanceUntilIdle()
            // The same choices again: no rebuild.
            lab.update { it.compare(PromptKind.SONG, "C", on = true) }
            advanceUntilIdle()
            watching.cancel()
            assertEquals(2, asked.size)
        }

    @Test
    fun typingCustomRebuildsNothingUntilItIsApplied() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.switchOn()
            lab.update { it.use(PromptKind.SONG, LabPrompts.CUSTOM) }
            lab.applyCustom(PromptKind.SONG)
            advanceUntilIdle()
            val asked = mutableListOf<Unit>()
            val watching = launch { lab.rebuilds.collect { asked += it } }
            advanceUntilIdle()
            for (typed in listOf("W", "Wr", "Write the sound first.")) {
                lab.update { it.withCustom(PromptKind.SONG, typed) }
                advanceUntilIdle()
            }
            assertEquals(0, asked.size)
            assertEquals(LabPrompts.ROLE, lab.choices()[PromptKind.SONG]?.instructions)

            // The field left: the instructions as typed are taken, and the pipeline rebuilt once.
            lab.applyCustom(PromptKind.SONG)
            advanceUntilIdle()
            watching.cancel()
            assertEquals(1, asked.size)
            assertEquals("Write the sound first.", lab.choices()[PromptKind.SONG]?.instructions)
        }

    @Test
    fun withTheLabsSwitchOffTheReleasePromptsAreWrittenWithAndTheChoicesAreKept() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.developerMode(true)
            lab.update { it.use(PromptKind.KEY, "B") }
            // Developer mode on, the switch off until turned on: nothing varied.
            assertFalse(lab.on.value)
            assertSame(PromptChoices.NONE, lab.choices())
            assertSame(provider, lab.through(provider, lab.choices()))

            lab.update { it.withEnabled(true) }
            assertTrue(lab.on.value)
            assertEquals("B", lab.choices()[PromptKind.KEY]?.id)

            lab.update { it.withEnabled(false) }
            assertFalse(lab.on.value)
            assertSame(PromptChoices.NONE, lab.choices())
            // On again, the choice made before is back.
            lab.update { it.withEnabled(true) }
            assertEquals("B", lab.choices()[PromptKind.KEY]?.id)
        }

    @Test
    fun turningTheLabsSwitchOnOrOffAsksForARebuild() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.developerMode(true)
            lab.update { it.use(PromptKind.SONG, "A") }
            advanceUntilIdle()
            val asked = mutableListOf<Unit>()
            val watching = launch { lab.rebuilds.collect { asked += it } }
            advanceUntilIdle()
            lab.update { it.withEnabled(true) }
            advanceUntilIdle()
            lab.update { it.withEnabled(false) }
            advanceUntilIdle()
            watching.cancel()
            assertEquals(2, asked.size)
        }

    @Test
    fun theLabsSwitchOutlastsTheProcess() =
        runTest {
            val first = lab()
            advanceUntilIdle()
            first.update { it.use(PromptKind.SONG, "C").withEnabled(true) }
            advanceUntilIdle()

            val second = lab()
            advanceUntilIdle()
            second.developerMode(true)
            assertTrue(second.on.value)
            assertEquals("C", second.choices()[PromptKind.SONG]?.id)
        }

    @Test
    fun theSettingsAndTheLogOutlastTheProcess() =
        runTest {
            val first = lab()
            advanceUntilIdle()
            first.update { it.use(PromptKind.SONG, "C") }
            first.record(comparison())
            advanceUntilIdle()

            val second = lab()
            advanceUntilIdle()
            assertEquals("C", second.settings.value.inUse(PromptKind.SONG))
            assertEquals(listOf(comparison()), second.log.value)

            second.clear()
            advanceUntilIdle()
            val third = lab()
            advanceUntilIdle()
            assertEquals(emptyList<LabComparison>(), third.log.value)
        }

    private fun comparison() =
        LabComparison(
            time = Instant.parse("2026-11-17T19:58:12Z"),
            app = "0.8.0-debug",
            kind = PromptKind.SONG,
            title = "Sundown",
            artist = "Gordon Lightfoot",
            album = null,
            service = "ANTHROPIC",
            model = "claude-haiku-5-5",
            effort = null,
            evidence = "",
            instructions = emptyMap(),
            versions = emptyList(),
        )

    private companion object {
        const val REPLY = "Gordon Lightfoot wrote Sundown at his farm in Orillia. [W1]"
    }
}
