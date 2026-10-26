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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelCheck]'s loop at its edges (#540): a key read that fails for now while another is held
 * back, another service's model changed while an answer is held, and the loop's end. Fakes only.
 */
class ModelCheckLoopTest {
    /** Anthropic's client: lists Haiku, and answers a call with [answer] once [gate] is completed. */
    private class Fake(
        val answer: CompletionEvent = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED),
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : AiProvider {
        override val id = ProviderId.ANTHROPIC

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(listOf(ModelInfo(HAIKU, HAIKU, null)))

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                gate.await()
                emit(answer)
            }
    }

    private val anthropic = Settings(aiProvider = ProviderId.ANTHROPIC)

    private var reader: suspend () -> Secret? = { OLD_KEY }
    private var stored = false

    private fun check(
        fake: Fake,
        scope: CoroutineScope,
    ) = ModelCheck(mapOf(ProviderId.ANTHROPIC to fake), { reader() }, scope) { stored }

    @Test
    fun aFailedReadAfterAHeldReplacementReadLetsItApplySoTheOldKeysRefusalMarksNothing() =
        runTest {
            val fake = Fake()
            val check = check(fake, backgroundScope)
            val keyChanges = MutableStateFlow(0)
            val job = launch { check.run(flowOf(anthropic), keyChanges.map {}, MutableStateFlow(false)) }
            runCurrent()
            // A call with the old key, its refusal held back.
            val call = async { check.watching(fake).stream(CompletionRequest(HAIKU, "p"), OLD_KEY).toList() }
            runCurrent()

            // The replacement key is read, and the read held back.
            val held = CompletableDeferred<Unit>()
            reader = {
                held.await()
                NEW_KEY
            }
            val replacement = launch { check.check(ProviderId.ANTHROPIC, HAIKU) }
            runCurrent()

            // A later read fails for now: no key read, though one is stored.
            reader = { null }
            stored = true
            keyChanges.value++
            runCurrent()

            // The held read comes back: it applies, the key having changed.
            held.complete(Unit)
            replacement.join()
            check.ticket()

            // The old key's refusal comes last.
            fake.gate.complete(Unit)
            call.await()
            check.ticket()
            assertFalse("the refusal was made with the key replaced", check.blocks(anthropic))
            job.cancel()
        }

    @Test
    fun anotherServicesModelChangedWhileAnAnswerIsHeldLeavesItCounting() =
        runTest {
            val fake = Fake()
            val check = check(fake, backgroundScope)
            val settings = MutableStateFlow(anthropic)
            val job = launch { check.run(settings, flowOf(Unit), MutableStateFlow(false)) }
            runCurrent()
            val call = async { check.watching(fake).stream(CompletionRequest(HAIKU, "p"), OLD_KEY).toList() }
            runCurrent()

            // OpenAI's model chosen meanwhile: Anthropic's selection is unchanged.
            settings.value = anthropic.withModel(ProviderId.OPENAI, "gpt-6.1")
            runCurrent()

            fake.gate.complete(Unit)
            call.await()
            check.ticket()
            assertTrue(check.blocks(settings.value))
            assertEquals(UnavailableReason.REFUSED, check.unavailable.value[ProviderId.ANTHROPIC]?.why)
            job.cancel()
        }

    @Test
    fun onceTheLoopHasEndedATicketComesAtOnceAndNothingCountsUnderIt() =
        runTest {
            val loop = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val fake = Fake()
            val check = check(fake, loop)
            // Asked for before the loop has run (sent at once), and still waiting when it ends.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { check.ticket() }
            loop.cancel()
            runCurrent()
            val queued = waiting.await()

            val after = check.ticket()
            val refused = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED)
            check.answered(after, ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            check.answered(queued, ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            runCurrent()
            assertFalse(check.blocks(anthropic))
            // A call still goes through, and is not held up by the ended loop.
            fake.gate.complete(Unit)
            val events = check.watching(fake).stream(CompletionRequest(HAIKU, "p"), OLD_KEY).toList()
            assertEquals(listOf(fake.answer), events)
        }

    @Test
    fun aTicketHandedToTheWaitingLoopAsItIsCancelledStillComes() =
        runTest {
            val loop = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val check = check(Fake(), loop)
            // The loop runs, and waits for its next event.
            runCurrent()
            // The ask is handed to it, and the loop cancelled before it resumes to take it.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { check.ticket() }
            loop.cancel()
            runCurrent()
            val ticket = waiting.await()

            val refused = CompletionEvent.Error(AiErrorKind.MODEL_REFUSED)
            check.answered(ticket, ProviderId.ANTHROPIC, HAIKU, OLD_KEY, refused)
            runCurrent()
            assertFalse(check.blocks(anthropic))
        }

    @Test
    fun aScopeCancelledBeforeTheCheckIsMadeEndsTheLoopAtOnce() =
        runTest {
            val loop = CoroutineScope(Job())
            loop.cancel()
            val check = check(Fake(), loop)
            check.ticket()
            assertFalse(check.blocks(anthropic))
        }

    private companion object {
        const val HAIKU = "claude-haiku-5-5"
        val OLD_KEY = Secret("sk-old")
        val NEW_KEY = Secret("sk-new")
    }
}
