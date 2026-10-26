package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * not hold, or that a call names as not known or refused, is unavailable until another is chosen;
 * each mark keeps its reason, and an answer made with a key since replaced, or for a model since
 * left, counts for nothing. Fakes only: nothing is sent.
 */
class ModelCheckTest {
    /** Anthropic's client, listing [listed] (or failing with [failure]), and answering calls with [answer]. */
    private class Fake(
        var listed: List<ModelInfo> = listOf(model(HAIKU)),
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
    private val opus = anthropic.withModel(ProviderId.ANTHROPIC, OPUS)

    /** The key saved now; a test replaces it to save another. */
    private var saved: Secret? = OLD_KEY

    private fun check(fake: Fake) = ModelCheck(mapOf(ProviderId.ANTHROPIC to fake)) { saved }

    private fun ModelCheck.why() = unavailable.value[ProviderId.ANTHROPIC]?.why

    private val refused = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED)
    private val done = CompletionEvent.Done(Usage(1, 1), StopReason.END)

    @Test
    fun aSelectedModelTheListDoesNotHoldIsUnavailableAndBlocksTheService() =
        runTest {
            val check = check(Fake(listed = listOf(model(OPUS))))
            check.check(ProviderId.ANTHROPIC, HAIKU)

            assertEquals(UnavailableModel(ProviderId.ANTHROPIC, HAIKU), check.of(anthropic, ProviderId.ANTHROPIC))
            assertEquals(UnavailableReason.LISTED_ABSENT, check.why())
            assertTrue(check.blocks(anthropic))
            // Another model chosen for the service is not blocked by it, nor another service switched on.
            assertFalse(check.blocks(opus))
            assertFalse(check.blocks(Settings(aiProvider = ProviderId.OPENAI)))
        }

    @Test
    fun aListThatHoldsTheModelAgainADeprecatedOneTooLiftsAListedAbsentMark() =
        runTest {
            val fake = Fake(listed = emptyList())
            val check = check(fake)
            check.check(ProviderId.ANTHROPIC, HAIKU)
            assertTrue(check.blocks(anthropic))

            // Deprecated: it still answers for an account that uses it.
            fake.listed = listOf(model(HAIKU, offered = false))
            check.check(ProviderId.ANTHROPIC, HAIKU)
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun aListThatCannotBeHadOrNoKeySaysNothingOfTheModel() =
        runTest {
            for (kind in listOf(AiErrorKind.OFFLINE, AiErrorKind.UNAUTHORIZED, AiErrorKind.MODEL_NOT_FOUND)) {
                val check = check(Fake(failure = kind))
                check.check(ProviderId.ANTHROPIC, HAIKU)
                assertFalse(kind.name, check.blocks(anthropic))
            }
            saved = null
            val fake = Fake(listed = emptyList())
            check(fake).check(ProviderId.ANTHROPIC, HAIKU)
            assertEquals("no key, no list", 0, fake.lists)
        }

    @Test
    fun aCallTheServiceAnswersAsNotKnownOrRefusedMarksItsModelRefused() =
        runTest {
            for (kind in listOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)) {
                val fake = Fake(answer = CompletionEvent.Error(kind))
                val check = check(fake)
                val events = check.watching(fake).stream(CompletionRequest(HAIKU, "prompt"), OLD_KEY).toList()

                // The call's own answer is passed on as it came.
                assertEquals(listOf<CompletionEvent>(CompletionEvent.Error(kind)), events)
                assertTrue(kind.name, check.blocks(anthropic))
                assertEquals(UnavailableReason.REFUSED, check.why())
            }
        }

    @Test
    fun otherFailuresOfACallMarkNothing() =
        runTest {
            for (kind in AiErrorKind.entries - setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)) {
                val fake = Fake(answer = CompletionEvent.Error(kind))
                val check = check(fake)
                check.watching(fake).stream(CompletionRequest(HAIKU, "prompt"), OLD_KEY).toList()
                assertFalse(kind.name, check.blocks(anthropic))
            }
        }

    @Test
    fun aRefusedModelStaysBlockedWhenAListHoldsIt() =
        runTest {
            // OpenAI and Google list the models they refuse for the account.
            val check = check(Fake(listed = listOf(model(HAIKU))))
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            check.check(ProviderId.ANTHROPIC, HAIKU)

            assertTrue(check.blocks(anthropic))
            assertEquals(UnavailableReason.REFUSED, check.why())
        }

    @Test
    fun aRefusedModelIsLiftedByALaterCallWithItThatSucceeds() =
        runTest {
            val check = check(Fake())
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            assertTrue(check.blocks(anthropic))

            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, done)
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun aRefusalMadeWithAKeySinceReplacedArrivingAfterTheNewKeysListMarksNothing() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            // A call with the old key is under way; another key is saved, and its list holds the model.
            saved = NEW_KEY
            check.check(ProviderId.ANTHROPIC, HAIKU)
            assertFalse(check.blocks(anthropic))

            // The old call ends refused, after it.
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            assertFalse(check.blocks(anthropic))
            // A list made with the old key, likewise.
            check.listed(ProviderId.ANTHROPIC, HAIKU, emptyList(), OLD_KEY)
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun anotherKeySavedDropsTheMarksMadeWithTheOldOne() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, flowOf(true)) }
            runCurrent()
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            assertTrue(check.blocks(anthropic))

            saved = NEW_KEY
            keyChanges.value++
            runCurrent()
            assertFalse("the refusal was made with the old key", check.blocks(anthropic))
            job.cancel()
        }

    @Test
    fun aStaleNotFoundForTheModelLeftLeavesTheNewModelsMarkAlone() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            val settings = MutableStateFlow(anthropic)
            val job = launch { check.run(settings, flowOf(Unit), flowOf(true)) }
            runCurrent()

            // Opus chosen: the list lacks it, so it is marked.
            settings.value = opus
            runCurrent()
            assertTrue(check.blocks(opus))

            // A call with Haiku, made before, ends not found.
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND))
            assertEquals(OPUS, check.unavailable.value[ProviderId.ANTHROPIC]?.model)
            assertEquals(UnavailableReason.LISTED_ABSENT, check.why())
            assertTrue(check.blocks(opus))
            job.cancel()
        }

    @Test
    fun theStartCheckWaitsForLookupsAndRunsAgainForAnotherModelWhichDropsTheOldMark() =
        runTest {
            val fake = Fake(listed = listOf(model(OPUS)))
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

            settings.value = opus
            runCurrent()
            assertEquals(2, fake.lists)
            assertEquals(emptyMap<ProviderId, UnavailableMark>(), check.unavailable.value)
            assertFalse(check.blocks(settings.value))
            job.cancel()
        }

    @Test
    fun anotherServiceSwitchedOnDropsTheMarksOfTheOthers() =
        runTest {
            val check = check(Fake())
            val settings = MutableStateFlow(anthropic)
            // Lookups not allowed: no list, so the marks are the call's alone.
            val job = launch { check.run(settings, flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.answered(ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            assertTrue(check.blocks(anthropic))

            settings.value = Settings(aiProvider = ProviderId.OPENAI)
            runCurrent()
            assertEquals(emptyMap<ProviderId, UnavailableMark>(), check.unavailable.value)
            job.cancel()
        }

    @Test
    fun noServiceSwitchedOnOrInsightsOffIsNotChecked() =
        runTest {
            for (settings in listOf(Settings(), anthropic.copy(writeInsights = false))) {
                val fake = Fake()
                val job = launch { check(fake).run(flowOf(settings), flowOf(Unit), flowOf(true)) }
                runCurrent()
                assertEquals(settings.toString(), 0, fake.lists)
                job.cancel()
            }
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
            check.check(ProviderId.ANTHROPIC, HAIKU)
            runCurrent()
            assertEquals(UnavailableModel(ProviderId.ANTHROPIC, HAIKU), shown.value)

            settings.value = Settings(aiProvider = ProviderId.OPENAI)
            runCurrent()
            assertNull("another service switched on", shown.value)
            job.cancel()
        }

    @Test
    fun aMarkAndThenItsClearEachAskForOneRebuildAndNoMoreLists() =
        runTest {
            val fake = Fake(listed = emptyList())
            val check = check(fake)
            val settings = MutableStateFlow<Settings?>(anthropic)
            var rebuilds = 0
            val job = launch { check.rebuilds(settings).collect { rebuilds++ } }
            runCurrent()
            assertEquals("none at first", 0, rebuilds)

            check.check(ProviderId.ANTHROPIC, HAIKU)
            runCurrent()
            assertEquals("the mark", 1, rebuilds)
            // A second list that still lacks it changes nothing.
            check.check(ProviderId.ANTHROPIC, HAIKU)
            runCurrent()
            assertEquals(1, rebuilds)

            fake.listed = listOf(model(HAIKU))
            check.check(ProviderId.ANTHROPIC, HAIKU)
            runCurrent()
            assertEquals("the clear", 2, rebuilds)
            // Only the lists asked for here: the rebuilds ask for none.
            assertEquals(3, fake.lists)
            job.cancel()
        }

    private companion object {
        const val HAIKU = "claude-haiku-5-5"
        const val OPUS = "claude-opus-5-5"
        val OLD_KEY = Secret("sk-old")
        val NEW_KEY = Secret("sk-new")

        fun model(
            id: String,
            offered: Boolean = true,
        ) = ModelInfo(id, id, null, offered)
    }
}
