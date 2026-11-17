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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The AI services whose saved key was refused (#1157), as *AI services* says under OpenRouter. */
class KeyRefusalsTest {
    private val request = CompletionRequest(model = "anthropic/claude-haiku-5.5", prompt = "p")

    /** OpenRouter's client, ending each call as [ends] and each model list as [lists]. */
    private class Answering(
        var ends: CompletionEvent,
        var lists: ModelList = ModelList.Listed(emptyList()),
    ) : AiProvider {
        override val id = ProviderId.OPENROUTER

        override suspend fun models(key: Secret): ModelList = lists

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = flowOf(ends)
    }

    private val refused = CompletionEvent.Error(AiErrorKind.UNAUTHORIZED)
    private val done = CompletionEvent.Done(Usage(1, 1), StopReason.END)

    @Test
    fun aCallRefusedForItsKeyMarksTheServiceAndOneThatSucceedsWithItLiftsTheMark() =
        runTest {
            val refusals = KeyRefusals()
            val client = Answering(refused)
            val watched = refusals.watching(client)
            watched.stream(request, Secret("old")).toList()
            assertEquals(setOf(ProviderId.OPENROUTER), refusals.refused.first())
            client.ends = done
            watched.stream(request, Secret("old")).toList()
            assertEquals(emptySet<ProviderId>(), refusals.refused.first())
        }

    @Test
    fun aModelListRefusedForTheKeyMarksItToo() =
        runTest {
            val refusals = KeyRefusals()
            refusals.watching(Answering(done, ModelList.Failed(AiErrorKind.UNAUTHORIZED))).models(Secret("old"))
            assertEquals(setOf(ProviderId.OPENROUTER), refusals.refused.first())
        }

    @Test
    fun anotherErrorMarksNothing() =
        runTest {
            val refusals = KeyRefusals()
            val billing = Answering(CompletionEvent.Error(AiErrorKind.BILLING))
            refusals.watching(billing).stream(request, Secret("k")).toList()
            assertEquals(emptySet<ProviderId>(), refusals.refused.first())
        }

    @Test
    fun anotherKeySavedOrTheKeyRemovedLiftsTheMarkTheSameKeyKeepsIt() =
        runTest {
            val refusals = KeyRefusals()
            refusals.watching(Answering(refused)).stream(request, Secret("old")).toList()
            refusals.keysChanged { Secret("old") }
            assertEquals(setOf(ProviderId.OPENROUTER), refusals.refused.first())
            refusals.keysChanged { Secret("new") }
            assertEquals(emptySet<ProviderId>(), refusals.refused.first())

            refusals.watching(Answering(refused)).stream(request, Secret("new")).toList()
            refusals.keysChanged { null }
            assertEquals(emptySet<ProviderId>(), refusals.refused.first())
        }

    @Test
    fun aCallThatSucceedsWithAnotherKeyDoesNotLiftTheMarkOfTheKeyRefused() =
        runTest {
            val refusals = KeyRefusals()
            val client = Answering(refused)
            refusals.watching(client).stream(request, Secret("old")).toList()
            client.ends = done
            refusals.watching(client).stream(request, Secret("other")).toList()
            assertEquals(setOf(ProviderId.OPENROUTER), refusals.refused.first())
        }
}
