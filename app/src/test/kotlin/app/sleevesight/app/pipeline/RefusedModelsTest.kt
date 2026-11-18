package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
 * The models a call found refused with a service's saved key (#970): whichever model, the default
 * too, each kept with how it was refused, across starts, until a call with it answers or the key is
 * saved, replaced or removed (#1425), never on a read of it; a selected model kept so is marked
 * again at start, and one chosen again is tried.
 * Fakes only: nothing is sent.
 */
class RefusedModelsTest {
    /** Kept in memory, as `SharedPreferences` would keep them across starts. */
    private class Kept(
        var models: RefusedModels = emptyMap(),
    ) : RefusedModelStore {
        var saves = 0

        override fun load(): RefusedModels = models

        override fun save(refused: RefusedModels) {
            saves++
            models = refused
        }
    }

    private object Anthropic : AiProvider {
        override val id = ProviderId.ANTHROPIC

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = emptyFlow()
    }

    private val kept = Kept()
    private var saved: Secret? = OLD_KEY

    /** Whether a key is stored, readable or not. */
    private var stored = false

    /** Anthropic's model lists asked for. */
    private var lists = 0

    private val client =
        object : AiProvider by Anthropic {
            override suspend fun models(key: Secret): ModelList = Anthropic.models(key).also { lists++ }
        }

    private fun TestScope.check() =
        ModelCheck(mapOf(ProviderId.ANTHROPIC to client), { saved }, backgroundScope, kept) { stored }

    /**
     * [provider]'s key saved as [saved] (or removed, when null) through the app, as the key store
     * tells it: before the write, and after it.
     */
    private suspend fun ModelCheck.edited(provider: ProviderId = ProviderId.ANTHROPIC) {
        keyChanging(provider)
        keyChanged(provider, written = true, now = saved)
        ticket()
    }

    /** Anthropic's client, which refuses every call as a model it does not know. */
    private object Refusing : AiProvider by Anthropic {
        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = flowOf(NOT_FOUND)
    }

    private suspend fun ModelCheck.ended(
        event: CompletionEvent,
        model: String,
        key: Secret = OLD_KEY,
    ) {
        answered(ticket(), ProviderId.ANTHROPIC, model, key, event)
        ticket()
    }

    private fun ModelCheck.anthropic() = refused.value[ProviderId.ANTHROPIC]

    private val anthropic = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val opus = anthropic.withModel(ProviderId.ANTHROPIC, OPUS)

    private fun opusRefused(how: ModelRefusal) = UnavailableModel(ProviderId.ANTHROPIC, OPUS, how)

    @Test
    fun aListedModelAndTheDefaultAreKeptAsRefusedEachWithHow() =
        runTest {
            val check = check()
            check.ended(NOT_FOUND, OPUS)
            check.ended(REFUSED, DEFAULT)

            val expected = mapOf(OPUS to ModelRefusal.NOT_SERVED, DEFAULT to ModelRefusal.ACCOUNT)
            assertEquals(expected, check.anthropic())
            assertEquals(mapOf(ProviderId.ANTHROPIC to expected), kept.models)
        }

    @Test
    fun otherFailuresKeepNothing() =
        runTest {
            val check = check()
            for (kind in AiErrorKind.entries - setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)) {
                check.ended(CompletionEvent.Error(kind), OPUS)
            }
            assertNull(check.anthropic())
            assertEquals(0, kept.saves)
        }

    @Test
    fun aModelNoLongerSelectedIsKeptAsRefusedThoughItIsNotMarked() =
        runTest {
            val check = check()
            val job = launch { check.run(flowOf(anthropic), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            // The selected model is the default; a call made with Opus, chosen before, ends refused.
            check.ended(NOT_FOUND, OPUS)

            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())
            assertFalse(check.blocks(anthropic))
            job.cancel()
        }

    @Test
    fun aCallWithItThatAnswersBringsItBack() =
        runTest {
            val check = check()
            check.ended(REFUSED, OPUS)
            check.ended(REFUSED, DEFAULT)
            check.ended(DONE, OPUS)

            assertEquals(mapOf(DEFAULT to ModelRefusal.ACCOUNT), check.anthropic())
            check.ended(DONE, DEFAULT)
            assertNull(check.anthropic())
            assertEquals(emptyMap<ProviderId, Map<String, ModelRefusal>>(), kept.models)
        }

    @Test
    fun theyAreKeptAcrossARestartAndTheSelectedOneIsMarkedAgainWithHow() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT))
            // The next start: read from where they were kept.
            val check = check()
            val job = launch { check.run(flowOf(opus), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ticket()

            assertEquals(mapOf(OPUS to ModelRefusal.ACCOUNT), check.anthropic())
            assertTrue(check.blocks(opus))
            assertEquals(opusRefused(ModelRefusal.ACCOUNT), check.of(opus, ProviderId.ANTHROPIC))
            assertEquals(UnavailableReason.REFUSED, check.unavailable.value[ProviderId.ANTHROPIC]?.why)
            job.cancel()
        }

    @Test
    fun theSelectedModelStaysMarkedUntilAnotherIsChosenAndOneChosenAgainIsTried() =
        runTest {
            val check = check()
            val settings = MutableStateFlow(opus)
            val job = launch { check.run(settings, flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ended(NOT_FOUND, OPUS)
            assertEquals(opusRefused(ModelRefusal.NOT_SERVED), check.of(opus, ProviderId.ANTHROPIC))

            // Another chosen: the note goes, the model stays refused, last in the list.
            settings.value = anthropic
            runCurrent()
            check.ticket()
            assertFalse(check.blocks(anthropic))
            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())

            // Chosen again from the list: tried, not blocked, until its next call ends.
            settings.value = opus
            runCurrent()
            check.ticket()
            assertFalse("chosen again, it is tried", check.blocks(opus))
            check.ended(NOT_FOUND, OPUS)
            assertTrue("refused again", check.blocks(opus))
            job.cancel()
        }

    @Test
    fun theKeySavedReplacedOrRemovedClearsThatServicesOnly() =
        runTest {
            val openAi = mapOf(GPT to ModelRefusal.NOT_SERVED)
            kept.models =
                mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT), ProviderId.OPENAI to openAi)
            val check = check()
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(opus), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            check.ticket()
            assertTrue("the key read at start is the one they were refused with", check.blocks(opus))
            assertEquals(mapOf(OPUS to ModelRefusal.ACCOUNT), check.anthropic())

            // Saved, replaced or removed: the key store tells the service alike.
            saved = NEW_KEY
            check.edited()
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertNull(check.anthropic())
            assertFalse(check.blocks(opus))
            assertEquals(mapOf(ProviderId.OPENAI to openAi), kept.models)
            job.cancel()
        }

    @Test
    fun aReadOfTheKeyAloneClearsNothing() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT))
            val check = check()
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(opus), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            check.ticket()

            // Read again, finding another key before the key store's change is heard, and then none
            // while one is stored: no read says the key changed; only the key store does.
            saved = NEW_KEY
            keyChanges.value++
            runCurrent()
            saved = null
            stored = true
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertEquals(mapOf(OPUS to ModelRefusal.ACCOUNT), check.anthropic())
            assertEquals(mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT)), kept.models)
            job.cancel()
        }

    @Test
    fun aRefusalMadeWithAKeySinceReplacedKeepsNothing() =
        runTest {
            val check = check()
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            val ticket = check.ticket()
            saved = NEW_KEY
            check.edited()
            keyChanges.value++
            runCurrent()
            check.answered(ticket, ProviderId.ANTHROPIC, OPUS, OLD_KEY, NOT_FOUND)
            check.ticket()
            assertNull(check.anthropic())
            job.cancel()
        }

    @Test
    fun aCheckAndACallAfterTheKeyStoreShowsTheNewKeyCountAndTheModelIsCheckedAgain() =
        runTest {
            val check = check()
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, MutableStateFlow(true)) }
            runCurrent()
            check.ticket()

            // The new key written: the store shows it, and its check runs, before the write is told
            // ended; that check counts for nothing, so another runs after.
            check.keyChanging(ProviderId.ANTHROPIC)
            saved = NEW_KEY
            keyChanges.value++
            runCurrent()
            check.ticket()
            val during = check.unavailable.value[ProviderId.ANTHROPIC]
            assertNull("made while the key was written, it counts for nothing", during)
            val before = lists
            check.keyChanged(ProviderId.ANTHROPIC, written = true, now = NEW_KEY)
            runCurrent()
            check.ticket()
            assertEquals("checked again once the write ended", before + 1, lists)
            assertEquals(UnavailableReason.LISTED_ABSENT, check.unavailable.value[ProviderId.ANTHROPIC]?.why)

            check.watching(Refusing).stream(CompletionRequest(OPUS, "prompt"), NEW_KEY).toList()
            check.ticket()
            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())
            job.cancel()
        }

    @Test
    fun theyAreGoneFromWhereTheyAreKeptBeforeTheKeyIsWritten() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT))
            check().keyChanging(ProviderId.ANTHROPIC)
            assertEquals(emptyMap<ProviderId, Map<String, ModelRefusal>>(), kept.models)

            // The process ends with the key written, its end never told: the next start keeps none.
            val next = check()
            next.ticket()
            assertNull(next.anthropic())
        }

    @Test
    fun withTheLoopEndedTheyAreStillForgottenBeforeTheKeyIsWritten() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT))
            val ended = CoroutineScope(Job().apply { cancel() })
            ModelCheck(mapOf(ProviderId.ANTHROPIC to client), { saved }, ended, kept).keyChanging(ProviderId.ANTHROPIC)
            assertEquals(emptyMap<ProviderId, Map<String, ModelRefusal>>(), kept.models)
        }

    @Test
    fun afterAWriteThatFailedNothingCountsUntilTheKeyIsReadAgain() =
        runTest {
            val check = check()
            val job = launch { check.run(flowOf(anthropic), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ticket()

            check.keyChanging(ProviderId.ANTHROPIC)
            check.keyChanged(ProviderId.ANTHROPIC, written = false, now = null)
            check.ended(NOT_FOUND, OPUS)
            assertNull("what the key holds is not known", check.anthropic())

            // A call reads the key first: the one kept, which then counts.
            check.watching(Refusing).stream(CompletionRequest(OPUS, "prompt"), OLD_KEY).toList()
            check.ticket()
            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())
            job.cancel()
        }

    @Test
    fun aRefusalHeardAfterTheChangeBeforeTheNewKeyIsReadKeepsNothing() =
        runTest {
            val check = check()
            val job = launch { check.run(flowOf(anthropic), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ticket()

            // The key replaced; a call made with the old one ends, under a ticket taken after the
            // change, before any read of the new key.
            saved = NEW_KEY
            check.edited()
            check.ended(NOT_FOUND, OPUS)
            assertNull(check.anthropic())
            job.cancel()
        }

    @Test
    fun theSelectedRefusedModelChosenAgainIsTriedAndStaysRefusedUntilACallAnswers() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.NOT_SERVED))
            val check = check()
            val job = launch { check.run(flowOf(opus), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ticket()
            assertTrue(check.blocks(opus))

            // Chosen again from the list, though selected already: no selection change, a retry.
            check.retry(ProviderId.ANTHROPIC, OPUS)
            check.ticket()
            assertFalse("tried again", check.blocks(opus))
            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())

            check.ended(DONE, OPUS)
            assertNull(check.anthropic())
            job.cancel()
        }

    @Test
    fun aKeyUnreadableAtStartThenReadKeepsWhatWasRefused() =
        runTest {
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT))
            saved = null
            stored = true
            val check = check()
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(opus), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            check.ticket()
            assertEquals("a failed read clears nothing", mapOf(OPUS to ModelRefusal.ACCOUNT), check.anthropic())

            // The same key read once the key store opens it again: nothing was saved in its place.
            saved = OLD_KEY
            stored = false
            keyChanges.value++
            runCurrent()
            check.ticket()
            assertEquals(mapOf(OPUS to ModelRefusal.ACCOUNT), check.anthropic())
            assertTrue("still marked refused", check.blocks(opus))
            assertEquals(mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.ACCOUNT)), kept.models)

            // Another key saved in its place: they go.
            saved = NEW_KEY
            check.edited()
            assertNull(check.anthropic())
            assertFalse(check.blocks(opus))
            job.cancel()
        }

    @Test
    fun aCallWithTheNewKeyAfterItWasSavedCounts() =
        runTest {
            val check = check()
            val job = launch { check.run(flowOf(anthropic), flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            check.ticket()

            // The new key saved; the pipeline calls with it, reading it first.
            saved = NEW_KEY
            check.edited()
            check.watching(Refusing).stream(CompletionRequest(OPUS, "prompt"), NEW_KEY).toList()
            check.ticket()
            assertEquals(mapOf(OPUS to ModelRefusal.NOT_SERVED), check.anthropic())
            job.cancel()
        }

    @Test
    fun aRefusedModelTheListNoLongerHoldsChosenToTryIsTriedWithListLookupsOn() =
        runTest {
            // The service's list holds none of its models now; Opus was refused before.
            kept.models = mapOf(ProviderId.ANTHROPIC to mapOf(OPUS to ModelRefusal.NOT_SERVED))
            val check = check()
            val settings = MutableStateFlow(anthropic)
            val job = launch { check.run(settings, flowOf(Unit), MutableStateFlow(true)) }
            runCurrent()
            check.ticket()

            // Chosen from the refused models: the retry, then the selection, which the start check
            // lists for, and the list does not hold it.
            check.retry(ProviderId.ANTHROPIC, OPUS)
            settings.value = opus
            runCurrent()
            check.ticket()
            assertFalse("tried, whatever the list says", check.blocks(opus))

            // Its call is refused: marked again, and the list's next check keeps it so.
            check.ended(NOT_FOUND, OPUS)
            assertTrue(check.blocks(opus))
            check.check(ProviderId.ANTHROPIC, OPUS)
            check.ticket()
            assertTrue(check.blocks(opus))

            // Chosen again, though selected: tried again, whatever mark it had.
            check.retry(ProviderId.ANTHROPIC, OPUS)
            check.check(ProviderId.ANTHROPIC, OPUS)
            check.ticket()
            assertFalse(check.blocks(opus))

            // Its call answers: it leaves the refused; the try is over.
            check.ended(DONE, OPUS)
            assertNull(check.anthropic())
            check.check(ProviderId.ANTHROPIC, OPUS)
            check.ticket()
            assertEquals(
                "the list's absence counts again",
                UnavailableReason.LISTED_ABSENT,
                check.unavailable.value[ProviderId.ANTHROPIC]?.why,
            )
            job.cancel()
        }

    private companion object {
        val DEFAULT = Settings.DEFAULT_MODELS.getValue(ProviderId.ANTHROPIC)
        const val OPUS = "claude-opus-5-5"
        const val GPT = "gpt-6"
        val OLD_KEY = Secret("sk-old")
        val NEW_KEY = Secret("sk-new")
        val NOT_FOUND = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)
        val REFUSED = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED)
        val DONE = CompletionEvent.Done(Usage(1, 1), StopReason.END)
    }
}
