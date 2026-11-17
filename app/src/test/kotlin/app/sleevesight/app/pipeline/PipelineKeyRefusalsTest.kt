package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A key the AI service refuses during the pipeline's own calls is marked (#1157): the pipeline's
 * clients are heard by [KeyRefusals], as the settings' are, so *AI services* and Home can say it.
 * Every client is a fake that refuses the key: nothing is sent.
 */
class PipelineKeyRefusalsTest {
    /** Each service's client in place of the real one, refusing every call for its key. */
    private class Refusing(
        override val id: ProviderId,
    ) : AiProvider {
        override suspend fun models(key: Secret): ModelList = ModelList.Failed(AiErrorKind.UNAUTHORIZED)

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = flowOf(CompletionEvent.Error(AiErrorKind.UNAUTHORIZED))
    }

    private val wiring =
        appPipelineWiring(
            "0.1.0",
            JvmAccentFolder,
            JvmResourceReader,
            ArtistResults.None,
            aiThrough = { Refusing(it.id) },
        )

    @After
    fun close() = wiring.close()

    @Test
    fun aKeyRefusedInThePipelinesCallIsMarked() =
        runBlocking {
            val refusals = KeyRefusals()
            val parts =
                AppPipelineParts(wiring, Normalizer(JvmAccentFolder), refusals = refusals) { PipelineCache.None }
            val ai = parts(PipelineKeys(ai = Secret("sk-or-v1-key")), Settings(aiProvider = ProviderId.OPENROUTER)).ai!!
            ai.provider.stream(CompletionRequest("anthropic/claude-haiku-5.5", "p"), ai.key).toList()
            assertEquals(setOf(ProviderId.OPENROUTER), refusals.refused.first())
        }
}
