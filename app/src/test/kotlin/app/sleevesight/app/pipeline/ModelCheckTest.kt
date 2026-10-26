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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
 * left, counts for nothing, whatever order the coroutines run in (the interleavings below hold a
 * key read or a call suspended on a `CompletableDeferred`). Fakes only: nothing is sent.
 */
class ModelCheckTest {
    /**
     * Anthropic's client, listing [listed] (or failing with [failure]), and answering calls with
     * [answer], once [gate] (when set) is completed.
     */
    private class Fake(
        var listed: List<ModelInfo> = listOf(model(HAIKU)),
        var failure: AiErrorKind? = null,
        var answer: CompletionEvent = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND),
        var gate: CompletableDeferred<Unit>? = null,
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
        ): Flow<CompletionEvent> =
            flow {
                gate?.await()
                emit(answer)
            }
    }

    private val anthropic = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val opus = anthropic.withModel(ProviderId.ANTHROPIC, OPUS)

    /** The key saved now; a test replaces it to save another. */
    private var saved: Secret? = OLD_KEY

    /** How the key is read: at once by default; a test holds a read back with a gate. */
    private var reader: suspend () -> Secret? = { saved }

    /** Whether a key is stored, readable or not. */
    private var stored = false

    private fun TestScope.check(fake: Fake) = check(fake, backgroundScope)

    private fun check(
        fake: Fake,
        scope: CoroutineScope,
    ) = ModelCheck(mapOf(ProviderId.ANTHROPIC to fake), { reader() }, scope) { stored }

    private fun ModelCheck.why() = unavailable.value[ProviderId.ANTHROPIC]?.why

    private fun ModelCheck.markedModel() = unavailable.value[ProviderId.ANTHROPIC]?.model

    /** A call with [model] and [key] through the watched client, to its end. */
    private suspend fun ModelCheck.call(
        fake: Fake,
        model: String = HAIKU,
        key: Secret = OLD_KEY,
    ) = watching(fake).stream(CompletionRequest(model, "prompt"), key).toList()

    /** [event] as the end of a call with [model] and [key] issued now; applied by the next ticket. */
    private suspend fun ModelCheck.ended(
        event: CompletionEvent,
        model: String = HAIKU,
        key: Secret = OLD_KEY,
    ) {
        answered(ticket(), ProviderId.ANTHROPIC, model, key, event)
        ticket()
    }

    private val refused = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED)
    private val notFound = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)
    private val done = CompletionEvent.Done(Usage(1, 1), StopReason.END)

    // The rules, one answer at a time.

    @Test
    fun aSelectedModelTheListDoesNotHoldIsUnavailableAndBlocksTheService() =
        runTest {
            val check = check(Fake(listed = listOf(model(OPUS))))
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ticket()

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
            check.ticket()
            assertTrue(check.blocks(anthropic))

            // Deprecated: it still answers for an account that uses it.
            fake.listed = listOf(model(HAIKU, offered = false))
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ticket()
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun aListThatCannotBeHadOrNoKeySaysNothingOfTheModel() =
        runTest {
            for (kind in listOf(AiErrorKind.OFFLINE, AiErrorKind.UNAUTHORIZED, AiErrorKind.MODEL_NOT_FOUND)) {
                val check = check(Fake(failure = kind))
                check.check(ProviderId.ANTHROPIC, HAIKU)
                check.ticket()
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
                val events = check.call(fake)
                check.ticket()

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
                check.call(fake)
                check.ticket()
                assertFalse(kind.name, check.blocks(anthropic))
            }
        }

    @Test
    fun aRefusedModelStaysBlockedWhenAListHoldsIt() =
        runTest {
            // OpenAI and Google list the models they refuse for the account.
            val check = check(Fake(listed = listOf(model(HAIKU))))
            check.ended(refused)
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ticket()

            assertTrue(check.blocks(anthropic))
            assertEquals(UnavailableReason.REFUSED, check.why())
        }

    @Test
    fun aRefusedModelIsLiftedByALaterCallWithItThatSucceeds() =
        runTest {
            val check = check(Fake())
            check.ended(refused)
            assertTrue(check.blocks(anthropic))

            check.ended(done)
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun anotherKeySavedDropsTheMarksMadeWithTheOldOne() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, flowOf(true)) }
            runCurrent()
            check.ended(refused)
            assertTrue(check.blocks(anthropic))

            saved = NEW_KEY
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertFalse("the refusal was made with the old key", check.blocks(anthropic))
            job.cancel()
        }

    @Test
    fun aKeyReadThatFindsNoneWhileOneIsStoredDropsNoMark() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            check.ended(refused)
            assertTrue(check.blocks(anthropic))

            // The key store fails for now: no key read, though one is stored.
            saved = null
            stored = true
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertTrue("a failed read drops nothing", check.blocks(anthropic))

            // The key removed: its marks go.
            stored = false
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertFalse(check.blocks(anthropic))
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
            check.ticket()
            assertEquals(1, fake.lists)
            assertTrue(check.blocks(settings.value))

            settings.value = opus
            runCurrent()
            check.ticket()
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
            check.ended(refused)
            assertTrue(check.blocks(anthropic))

            settings.value = Settings(aiProvider = ProviderId.OPENAI)
            runCurrent()
            check.ticket()
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
            check.ticket()
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
            check.ticket()
            runCurrent()
            assertEquals("the mark", 1, rebuilds)
            // A second list that still lacks it changes nothing.
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ticket()
            runCurrent()
            assertEquals(1, rebuilds)

            fake.listed = listOf(model(HAIKU))
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ticket()
            runCurrent()
            assertEquals("the clear", 2, rebuilds)
            // Only the lists asked for here: the rebuilds ask for none.
            assertEquals(3, fake.lists)
            job.cancel()
        }

    // The interleavings: an answer or a key read held back while the selection or the key moves on.

    @Test
    fun aRefusalWithTheOldKeyHeldBackWhileANewKeyIsSavedAndListedMarksNothing() =
        runTest {
            val fake = Fake(listed = listOf(model(HAIKU)), answer = refused, gate = CompletableDeferred())
            val check = check(fake)
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, flowOf(true)) }
            runCurrent()
            // A call with the old key, its answer held back.
            val call = async { check.call(fake, key = OLD_KEY) }
            runCurrent()

            // Another key saved; its read and its list go through.
            saved = NEW_KEY
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertFalse(check.blocks(anthropic))

            // The old call ends refused, after both.
            checkNotNull(fake.gate).complete(Unit)
            call.await()
            check.ticket()
            assertFalse("the refusal was made with the key replaced", check.blocks(anthropic))
            job.cancel()
        }

    @Test
    fun aModelSwitchWhileAnAnswerIsHeldBackLeavesTheNewModelsMarkAlone() =
        runTest {
            val fake = Fake(listed = listOf(model(HAIKU)), answer = notFound, gate = CompletableDeferred())
            val check = check(fake)
            val settings = MutableStateFlow(anthropic)
            val job = launch { check.run(settings, flowOf(Unit), flowOf(true)) }
            runCurrent()
            // A call with Haiku, its answer held back.
            val call = async { check.call(fake, model = HAIKU) }
            runCurrent()

            // Opus chosen: the list lacks it, so it is marked.
            settings.value = opus
            runCurrent()
            check.ticket()
            assertTrue(check.blocks(opus))

            // The Haiku call ends not found, after it.
            checkNotNull(fake.gate).complete(Unit)
            call.await()
            check.ticket()
            assertEquals(OPUS, check.markedModel())
            assertEquals(UnavailableReason.LISTED_ABSENT, check.why())
            assertTrue(check.blocks(opus))
            job.cancel()
        }

    @Test
    fun aKeyScanOvertakenByAKeyChangeAndANewRefusalLeavesTheRefusal() =
        runTest {
            val check = check(Fake(listed = listOf(model(HAIKU))))
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()

            // A scan of the keys starts, and its read is held back, having found the old key.
            val slow = CompletableDeferred<Unit>()
            reader = {
                slow.await()
                OLD_KEY
            }
            keyChanges.value++
            runCurrent()

            // Meanwhile the new key is saved, read by a check (which reads it on its own while the
            // scan waits), and its call refused.
            reader = { saved }
            saved = NEW_KEY
            check.check(ProviderId.ANTHROPIC, HAIKU)
            check.ended(refused, key = NEW_KEY)
            assertTrue(check.blocks(anthropic))

            // The overtaken scan ends with the old key: its read was issued first, and drops nothing.
            slow.complete(Unit)
            runCurrent()
            check.ticket()
            assertTrue(check.blocks(anthropic))
            assertEquals(UnavailableReason.REFUSED, check.why())
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
