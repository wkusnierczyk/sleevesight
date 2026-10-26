package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check of each AI service's selected model (#540): a model the service's list for the key does
 * not hold, or that a call names as not known or refused, is unavailable until another is chosen.
 * Fakes only: nothing is sent.
 */
class ModelCheckTest {
    /** Anthropic's client, listing [listed] (or failing with [failure]), and answering calls with [answer]. */
    private class Fake(
        var listed: List<ModelInfo> = listOf(model("claude-haiku-5-5")),
        var failure: AiErrorKind? = null,
        var answer: CompletionEvent = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND),
    ) : AiProvider {
        override val id = ProviderId.ANTHROPIC
        var lists = 0

        override suspend fun models(key: Secret): ModelList {
            lists++
            return failure?.let { ModelList.Failed(it) } ?: ModelList.Listed(listed)
        }

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = flowOf(answer)
    }

    private val anthropic = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun check(
        fake: Fake,
        key: Secret? = Secret("sk-test"),
    ) = ModelCheck(mapOf(ProviderId.ANTHROPIC to fake)) { key }

    @Test
    fun aSelectedModelTheListDoesNotHoldIsUnavailableAndBlocksTheService() =
        runTest {
            val fake = Fake(listed = listOf(model("claude-opus-5-5")))
            val check = check(fake)
            check.check(ProviderId.ANTHROPIC, "claude-haiku-5-5")

            val haiku = UnavailableModel(ProviderId.ANTHROPIC, "claude-haiku-5-5")
            assertEquals(haiku, check.of(anthropic, ProviderId.ANTHROPIC))
            assertTrue(check.blocks(anthropic))
            // Another model chosen for the service is not blocked by it.
            assertFalse(check.blocks(anthropic.withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")))
            // Nor is another service switched on.
            assertFalse(check.blocks(Settings(aiProvider = ProviderId.OPENAI)))
        }

    @Test
    fun aListedModelIsAvailableADeprecatedOneTooAndAListedAgainOneIsAvailableAgain() =
        runTest {
            val fake = Fake(listed = emptyList())
            val check = check(fake)
            check.check(ProviderId.ANTHROPIC, "claude-haiku-5-5")
            assertTrue(check.blocks(anthropic))

            // Deprecated: it still answers for an account that uses it.
            fake.listed = listOf(model("claude-haiku-5-5", offered = false))
            check.check(ProviderId.ANTHROPIC, "claude-haiku-5-5")
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun aListThatCannotBeHadOrNoKeySaysNothingOfTheModel() =
        runTest {
            for (kind in listOf(AiErrorKind.OFFLINE, AiErrorKind.UNAUTHORIZED, AiErrorKind.MODEL_NOT_FOUND)) {
                val check = check(Fake(failure = kind))
                check.check(ProviderId.ANTHROPIC, "claude-haiku-5-5")
                assertFalse(kind.name, check.blocks(anthropic))
            }
            val fake = Fake(listed = emptyList())
            check(fake, key = null).check(ProviderId.ANTHROPIC, "claude-haiku-5-5")
            assertEquals("no key, no list", 0, fake.lists)
        }

    @Test
    fun aCallTheServiceAnswersAsNotKnownOrRefusedMarksItsModel() =
        runTest {
            for (kind in listOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)) {
                val fake = Fake(answer = CompletionEvent.Error(kind))
                val check = check(fake)
                val request = CompletionRequest("claude-haiku-5-5", "prompt")
                val events = check.watching(fake).stream(request, Secret("sk-test")).toList()

                // The call's own answer is passed on as it came.
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events)
                assertTrue(kind.name, check.blocks(anthropic))
            }
        }

    @Test
    fun otherFailuresOfACallMarkNothing() =
        runTest {
            for (kind in AiErrorKind.entries - setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)) {
                val fake = Fake(answer = CompletionEvent.Error(kind))
                val check = check(fake)
                check.watching(fake).stream(CompletionRequest("claude-haiku-5-5", "prompt"), Secret("k")).toList()
                assertFalse(kind.name, check.blocks(anthropic))
            }
        }

    @Test
    fun theStartCheckWaitsForLookupsAndRunsAgainForAnotherModelWhichForgetsTheOld() =
        runTest {
            val fake = Fake(listed = listOf(model("claude-opus-5-5")))
            val check = check(fake)
            val settings = MutableStateFlow(anthropic)
            val allowed = MutableStateFlow(false)
            val job = launch { check.run(settings, flowOf(Unit), allowed) }
            runCurrent()
            assertEquals("nothing before lookups are allowed", 0, fake.lists)

            allowed.value = true
            runCurrent()
            assertEquals(1, fake.lists)
            assertTrue(check.blocks(settings.value))
            assertEquals(mapOf(ProviderId.ANTHROPIC to "claude-haiku-5-5"), check.unavailable.value)

            // Another model chosen: the old one is forgotten, and the new one checked.
            settings.value = anthropic.withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")
            runCurrent()
            assertEquals(2, fake.lists)
            assertEquals(emptyMap<ProviderId, String>(), check.unavailable.value)
            assertFalse(check.blocks(settings.value))
            job.cancel()
        }

    @Test
    fun noServiceSwitchedOnIsNotChecked() =
        runTest {
            val fake = Fake()
            val job = launch { check(fake).run(flowOf(Settings()), flowOf(Unit), flowOf(true)) }
            runCurrent()
            assertEquals(0, fake.lists)
            job.cancel()
        }

    @Test
    fun whatIsShownIsTheSwitchedOnServicesModelWhileUnavailable() =
        runTest {
            val check = check(Fake(listed = emptyList()))
            val settings = MutableStateFlow<Settings?>(null)
            val shown = MutableStateFlow<UnavailableModel?>(UnavailableModel(ProviderId.GOOGLE, "x"))
            val job = launch { check.shown(settings).collect { shown.value = it } }
            runCurrent()
            assertNull("before the settings are read", shown.value)

            settings.value = anthropic
            check.check(ProviderId.ANTHROPIC, "claude-haiku-5-5")
            runCurrent()
            assertEquals(UnavailableModel(ProviderId.ANTHROPIC, "claude-haiku-5-5"), shown.value)

            settings.value = Settings(aiProvider = ProviderId.GOOGLE)
            runCurrent()
            assertNull("another service switched on", shown.value)
            job.cancel()
        }

    private companion object {
        fun model(
            id: String,
            offered: Boolean = true,
        ) = ModelInfo(id, id, null, offered)
    }
}
