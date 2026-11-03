package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * *Rewrite insight* beside the insight kept, on the track under way, when a change to the AI
 * service's settings changes the card's key (#849): another model, reasoning effort or service
 * offers it, and going back to the ones the card was written with withdraws it. So do the
 * *Sources* switches (#857): a source switched on is looked up for the track under way and one
 * switched off drops its lines, changing the evidence the key fingerprints, and *AI service
 * knowledge* is in the key itself. Nothing is written without a tap.
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
    fun `another model chosen while the insight is written offers Rewrite once its call ends`() =
        runTest {
            provider.streaming = 10.seconds
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            val first = settings.value.model
            started()
            play("Alpha")
            settle()
            tap()
            advanceTimeBy(2.seconds)

            // The call under way is not cut off: it ends, and its card is kept, written with the
            // first model; the rebuild waits for it.
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            advanceTimeBy(15.seconds)
            settle()

            assertEquals(listOf(first), provider.models)
            assertKeptWithRewrite(true)
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

    @Test
    fun `a source switched off offers Rewrite, and switched back on withdraws it`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(Secret("lastfm")))
            written()

            switchSource(SourceSwitch.LASTFM, on = false)
            assertKeptWithRewrite(true)

            switchSource(SourceSwitch.LASTFM, on = true)
            assertKeptWithRewrite(false)
        }

    @Test
    fun `a source switched on is looked up for the track under way, offering Rewrite until switched off`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(Secret("lastfm")))
            switchSource(SourceSwitch.LASTFM, on = false)
            written()
            assertTrue(lastFm.asked.isEmpty())

            switchSource(SourceSwitch.LASTFM, on = true)
            assertTrue(lastFm.asked.isNotEmpty())
            assertKeptWithRewrite(true)

            switchSource(SourceSwitch.LASTFM, on = false)
            assertKeptWithRewrite(false)
        }

    @Test
    fun `AI service knowledge switched on offers Rewrite, and switched off withdraws it`() =
        runTest {
            written()

            switchSource(SourceSwitch.MODEL, on = true)
            assertKeptWithRewrite(true)

            switchSource(SourceSwitch.MODEL, on = false)
            assertKeptWithRewrite(false)
        }

    @Test
    fun `a source with nothing to add changes nothing beside the insight kept`() =
        runTest {
            written()
            val before = builds

            // No adapter for Discogs here: it finds nothing, so the evidence stays as it was, and the
            // rebuild's run finds the card under its key.
            switchSource(SourceSwitch.DISCOGS, on = false)
            assertEquals(before + 1, builds)
            assertKeptWithRewrite(false)
        }

    @Test
    fun `a Rewrite tapped after a source is switched off writes without its lines`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(Secret("lastfm")))
            written()

            switchSource(SourceSwitch.LASTFM, on = false)
            tap()
            settle()

            assertEquals(2, provider.requestsFor("Alpha"))
            val line = "A line from ${SourceId.LASTFM.tag}."
            assertTrue(line in provider.prompts.first())
            assertTrue(line !in provider.prompts.last())
        }

    private fun TestScope.switchSource(
        switch: SourceSwitch,
        on: Boolean,
    ) {
        val now = settings.value
        settings.value = now.copy(enabledSources = if (on) now.enabledSources + switch else now.enabledSources - switch)
        settle()
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
    // made since the first, through any service.
    private fun assertKeptWithRewrite(rewrite: Boolean) {
        assertTrue(
            host.state.value.track
                ?.insight is InsightState.Written,
        )
        assertEquals(rewrite, host.state.value.rewrite)
        assertEquals(1, (providers.values + provider).sumOf { it.requestsFor("Alpha") })
    }

    private companion object {
        const val OTHER_MODEL = "claude-other"
    }
}
