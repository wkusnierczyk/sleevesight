package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings.Companion.DEFAULT_MODELS
import app.sleevesight.core.settings.Settings.Companion.EFFORT_SCALES
import app.sleevesight.core.settings.SourceSwitch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What rebuilds the pipeline in the app (#51): a key, a web search engine, the AI service, its model
 * and reasoning effort (#849), and the *Sources* switches (#857), which the card's key holds, and
 * *Show genres* (#444), whose adapters ask for tags or not as they are built; no other setting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostRebuildTest : PipelineHostFixture() {
    @Test
    fun `the pipeline is rebuilt for a key, an engine, the AI service or Show genres, not for other settings`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.copy(writeInsights = false, lookUpInBackground = true)
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.copy(webEngines = emptySet())
            settle()
            assertEquals(2, builds)

            settings.value = settings.value.copy(aiProvider = null)
            settle()
            assertEquals(3, builds)

            keyChanges.value++
            settle()
            assertEquals(4, builds)

            // *Show genres* (#444): the adapters ask for tags, or not, as they are built.
            settings.value = settings.value.copy(showGenres = false)
            settle()
            assertEquals(5, builds)
            // Each rebuild ran the track again; the same pipeline never does.
            assertEquals(5, musicBrainz.asked.size)
        }

    @Test
    fun `the pipeline is rebuilt for a source switched on or off, AI service knowledge included`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(1, builds)

            val sources = settings.value.enabledSources
            settings.value = settings.value.copy(enabledSources = sources - SourceSwitch.DISCOGS)
            settle()
            assertEquals(2, builds)

            settings.value = settings.value.copy(enabledSources = sources + SourceSwitch.MODEL)
            settle()
            assertEquals(3, builds)
        }

    @Test
    fun `the pipeline is rebuilt for the switched-on service's model or effort, not another service's`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.withModel(ProviderId.OPENAI, "gpt-other")
            settle()
            assertEquals(1, builds)

            // The model and effort already in use, now set explicitly: no rebuild.
            val model = DEFAULT_MODELS.getValue(ProviderId.ANTHROPIC)
            val effort = EFFORT_SCALES.getValue(ProviderId.ANTHROPIC).default
            settings.value =
                settings.value.copy(
                    aiModels = settings.value.aiModels + (ProviderId.ANTHROPIC to model),
                    aiEfforts = mapOf(ProviderId.ANTHROPIC to effort),
                )
            settle()
            assertEquals(1, builds)

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, "claude-other")
            settle()
            assertEquals(2, builds)

            settings.value = settings.value.withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)
            settle()
            assertEquals(3, builds)
            assertEquals(3, musicBrainz.asked.size)
        }
}
