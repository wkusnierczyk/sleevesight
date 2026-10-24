package app.sleevesight.ai.anthropic

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.settings.Settings
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A smoke test against the real API, with the developer's key: skipped unless
 * `SLEEVESIGHT_ANTHROPIC_API_KEY` is set, which CI never does. It spends a few tokens of the
 * default model.
 */
class AnthropicLiveTest {
    private val key: String? = System.getenv(KEY_VARIABLE)?.takeIf { it.isNotBlank() }

    @Test
    fun `the default model is listed, and a short stream completes`() {
        assumeTrue(key != null, "$KEY_VARIABLE is not set")
        val secret = Secret(key!!)
        val model = Settings(aiProvider = app.sleevesight.core.model.ProviderId.ANTHROPIC).model!!
        val hosts = mapOf(AnthropicProvider.HOST to AnthropicProvider.HOST_POLICY)
        val config = NetConfig(appVersion = "0.0.0-live-test", hosts = hosts)
        // Real time and a real network: not `runTest`, whose virtual clock would skip the timeouts.
        runBlocking {
            NetClient(config).use { net ->
                val provider = AnthropicProvider(net, config.retry)
                val models = assertIs<ModelList.Listed>(provider.models(secret)).models
                assertTrue(models.any { it.id == model }, "the default model $model is not listed")
                val events =
                    provider
                        .stream(
                            CompletionRequest(model, "Say hello in three words.", maxTokens = 256),
                            secret,
                        ).toList()
                assertIs<CompletionEvent.Done>(events.last(), events.last().toString())
                assertTrue(events.filterIsInstance<CompletionEvent.Delta>().joinToString("") { it.text }.isNotBlank())
            }
        }
    }

    private companion object {
        const val KEY_VARIABLE = "SLEEVESIGHT_ANTHROPIC_API_KEY"
    }
}
