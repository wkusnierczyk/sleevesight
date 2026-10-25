package app.sleevesight.app.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What rebuilds the pipeline in the app (#51): a key, a web search engine, the AI service, and
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
}
