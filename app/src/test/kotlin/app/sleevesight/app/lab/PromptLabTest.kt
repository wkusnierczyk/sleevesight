package app.sleevesight.app.lab

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Instant

/**
 * The prompt lab (#1003): a request it varies is sent with the variant in use only while developer
 * mode is on, and remembered as built; a card written with a variant is kept apart from the release
 * prompt's; the settings and the log outlast the process.
 */
class PromptLabTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sentRequests = mutableListOf<CompletionRequest>()

    private val provider =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList = throw UnsupportedOperationException()

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> {
                sentRequests += request
                return flowOf(CompletionEvent.Delta("ok"), CompletionEvent.Done(Usage(1, 1), StopReason.END))
            }
        }

    private val keyRequest =
        CompletionRequest("claude-haiku-5-5", "<track>\nTitle: Sundown\n</track>", system = InsightPrompt.system(false))

    private fun TestScope.lab(): PromptLab =
        // The test's own scope: `advanceUntilIdle` leaves the background scope's work unrun.
        PromptLab(folder.root, this, StandardTestDispatcher(testScheduler))

    @Test
    fun aVariantInUseIsSentOnlyInDeveloperMode() =
        runTest {
            val lab = lab()
            advanceUntilIdle()
            lab.update { it.use(PromptKind.KEY, "B") }
            val varied = lab.through(provider)

            varied.stream(keyRequest, Secret("k")).toList()
            assertEquals(keyRequest, sentRequests.last())

            lab.developerMode(true)
            varied.stream(keyRequest, Secret("k")).toList()
            val instructions = LabPrompts.variant(PromptKind.KEY, "B")!!.instructions
            assertEquals(LabPrompts.withInstructions(keyRequest, instructions), sentRequests.last())
            assertTrue(sentRequests.last().system!!.startsWith(InsightPrompt.ROLE + " Tell only"))
        }

    @Test
    fun aRequestIsRememberedAsBuiltForAComparisonOfItsTrack() =
        runTest {
            val lab = lab()
            lab.developerMode(true)
            lab.update { it.use(PromptKind.KEY, "A") }
            lab.through(provider).stream(keyRequest, Secret("k")).toList()

            assertEquals(keyRequest, lab.sent(PromptKind.KEY, listOf("Sundown"))?.request)
            assertNull(lab.sent(PromptKind.KEY, listOf("Another title")))
            assertNull(lab.sent(PromptKind.SONG, emptyList()))
        }

    @Test
    fun aCardWrittenWithAVariantIsKeptApartFromTheReleasePrompts() =
        runTest {
            val lab = lab()
            lab.developerMode(true)
            val store = MapCache()
            val cache = lab.cache(store)
            val key =
                CardKey(
                    CardKind.ARTIST,
                    CardEntity.Artist("sundown", null),
                    "evidence",
                    1,
                    "style",
                    ProviderId.ANTHROPIC,
                    "claude-haiku-5-5",
                    false,
                    null,
                )
            cache.keep(key, "release card")

            lab.update { it.use(PromptKind.ARTIST, "A") }
            assertNull(cache.card(key))
            cache.keep(key, "variant card")
            assertEquals("variant card", cache.card(key))

            lab.update { it.use(PromptKind.ARTIST, LabPrompts.RELEASE) }
            assertEquals("release card", cache.card(key))
            assertEquals("release card", store.card(key))
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

    private class MapCache : PipelineCache by PipelineCache.None {
        private val cards = mutableMapOf<CardKey, String>()

        override suspend fun card(key: CardKey): String? = cards[key]

        override suspend fun keep(
            key: CardKey,
            text: String,
        ) {
            cards[key] = text
        }
    }
}
