package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.InsightPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A written insight names what it was written with, for a problem report (#390). */
@OptIn(ExperimentalCoroutinesApi::class)
class WrittenByTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private val settings =
        Settings(aiProvider = ProviderId.ANTHROPIC)
            .copy(aiModels = mapOf(ProviderId.ANTHROPIC to "claude-opus-5-5"))
            .withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)

    private fun TestScope.pipeline(cache: PipelineCache): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ)),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(FakeProvider(), Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private val expected =
        WrittenBy(InsightPrompt.VERSION, ProviderId.ANTHROPIC, "claude-opus-5-5", ReasoningEffort.HIGH)

    @Test
    fun `an insight written by the model names the prompt version, service, model and effort of its call`() =
        runTest {
            val state = pipeline(PipelineCache.None).run(sundown, write = true)

            val written = assertIs<InsightState.Written>(state.insight)

            assertEquals(expected, written.writtenBy)
        }

    @Test
    fun `an insight served from the cache names the same, from its card's key`() =
        runTest {
            val pipeline = pipeline(MemoryCache())
            pipeline.run(sundown, write = true)

            val written = assertIs<InsightState.Written>(pipeline.run(sundown, write = true).insight)

            assertTrue(written.cached)
            assertEquals(expected, written.writtenBy)
        }
}
