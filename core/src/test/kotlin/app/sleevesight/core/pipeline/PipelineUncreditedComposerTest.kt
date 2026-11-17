package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.InsightPrompt
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The key insight of a track whose player credits the performer alone names the composer MusicBrainz
 * names for its classical work (#1376, `ArtistRoles.uncredited`) as its composer, as the item insights
 * do, and its card is keyed by its own prompt version; with none, as before.
 */
class PipelineUncreditedComposerTest {
    private val dorma = nowPlaying("Nessun Dorma", "Luciano Pavarotti", album = "Tutto Pavarotti")

    private fun resolution(uncredited: List<String>) =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "rec-1",
            roles = ArtistRoles(emptyList(), listOf("Luciano Pavarotti"), classical = true, uncredited = uncredited),
        )

    private suspend fun TestScope.written(
        provider: FakeProvider,
        uncredited: List<String>,
    ): InsightState.Written {
        val pipeline =
            Pipeline(
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters =
                        listOf(
                            FakeAdapter(SourceId.MUSICBRAINZ),
                            FakeAdapter(SourceId.WIKIPEDIA, byName = 200.milliseconds) { _, _ ->
                                SourceResult(
                                    Outcome.SongArticle,
                                    listOf(line(SourceId.WIKIPEDIA, "Wikipedia line.")),
                                    200.milliseconds,
                                )
                            },
                        ),
                    resolver = FakeResolver(resolution = resolution(uncredited)),
                    hasher = JvmHasher,
                    ai = AiAccess(provider, Secret("sk-test")),
                    cache = MemoryCache(),
                ),
                PipelineTime(timeSource = testTimeSource),
            ) { Settings(aiProvider = ProviderId.ANTHROPIC) }
        return assertIs(pipeline.run(dorma, write = true).insight)
    }

    @Test
    fun `the uncredited composer is the prompt's composer, under its own version`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is an aria [W1].") }
            val written = written(provider, listOf("Giacomo Puccini"))
            val prompt = provider.requests.single().prompt
            assertTrue("\nComposer: Giacomo Puccini\nPerformers: Luciano Pavarotti\n" in prompt, prompt)
            assertFalse("Primary artist:" in prompt)
            assertEquals(InsightPrompt.UNCREDITED_VERSION, written.writtenBy?.promptVersion)
        }

    @Test
    fun `with no uncredited composer, the prompt and its version are as before`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is a song [W1].") }
            val written = written(provider, emptyList())
            val prompt = provider.requests.single().prompt
            assertTrue("\nPrimary artist: Luciano Pavarotti\n" in prompt, prompt)
            assertFalse("Composer:" in prompt)
            assertEquals(InsightPrompt.VERSION, written.writtenBy?.promptVersion)
        }
}
