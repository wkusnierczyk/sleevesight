package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Rewrite insight* beside the insight kept, on the track under way, when a change to the AI
 * service's settings changes the card's key (#849): another model, reasoning effort or service
 * offers it, and going back to the ones the card was written with withdraws it. Nothing is
 * written without a tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostSettingsRewriteTest : PipelineHostFixture() {
    @Test
    fun `another model offers Rewrite beside the insight kept, and the model it was written with withdraws it`() =
        runTest {
            written()
            val first = settings.value.model

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            assertKeptWithRewrite(true)

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, first)
            settle()
            assertKeptWithRewrite(false)
        }

    @Test
    fun `a Rewrite tapped after another model is chosen writes with that model`() =
        runTest {
            written()

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            tap()
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
            assertEquals(OTHER_MODEL, provider.models.last())
        }

    @Test
    fun `another reasoning effort offers Rewrite, and the one it was written with withdraws it`() =
        runTest {
            written()
            val first = settings.value.effort

            settings.value = settings.value.withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)
            settle()
            assertKeptWithRewrite(true)

            settings.value = settings.value.withEffort(ProviderId.ANTHROPIC, first)
            settle()
            assertKeptWithRewrite(false)
        }

    @Test
    fun `another service's model changes nothing beside the insight kept`() =
        runTest {
            written()

            settings.value = settings.value.withModel(ProviderId.OPENAI, OTHER_MODEL)
            settle()
            assertKeptWithRewrite(false)
        }

    @Test
    fun `another AI service offers Rewrite, and the one it was written with withdraws it`() =
        runTest {
            providers[ProviderId.OPENAI] = CountingProvider(ProviderId.OPENAI)
            written()

            settings.value = settings.value.copy(aiProvider = ProviderId.OPENAI)
            settle()
            assertKeptWithRewrite(true)

            settings.value = settings.value.copy(aiProvider = ProviderId.ANTHROPIC)
            settle()
            assertKeptWithRewrite(false)
        }

    // The track written on a tap under *On demand*, as the issue saw it, with nothing offered beside it.
    private fun TestScope.written() {
        settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
        started()
        play("Alpha")
        settle()
        tap()
        settle()
        assertKeptWithRewrite(false)
    }

    // The insight written stays on screen, with *Rewrite insight* offered beside it or not; no call
    // made since the first.
    private fun assertKeptWithRewrite(rewrite: Boolean) {
        assertTrue(
            host.state.value.track
                ?.insight is InsightState.Written,
        )
        assertEquals(rewrite, host.state.value.rewrite)
        assertEquals(1, provider.requestsFor("Alpha"))
    }

    private companion object {
        const val OTHER_MODEL = "claude-other"
    }
}
