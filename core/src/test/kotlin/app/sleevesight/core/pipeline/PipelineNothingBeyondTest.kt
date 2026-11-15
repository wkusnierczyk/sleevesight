package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The key insight of a track whose evidence holds nothing beyond what Home shows is still written,
 * and says so (#1142, `HomeShown`); with anything beyond, it does not. Decided before the call, and
 * again the same way for the card served from the cache.
 */
class PipelineNothingBeyondTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private fun TestScope.pipeline(
        lines: List<EvidenceDraft>,
        provider: FakeProvider,
        cache: PipelineCache,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters =
                    listOf(
                        FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                            SourceResult(Outcome.SongArticle, lines, Duration.ZERO)
                        },
                    ),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = ProviderId.ANTHROPIC) }

    @Test
    fun `the release and the artist's own credit are written, the card saying nothing more was found`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Gordon Lightfoot wrote it [MB2].") }
            val cache = MemoryCache()
            val written = assertIs<InsightState.Written>(pipeline(HEADER, provider, cache).run(sundown, true).insight)
            // No new suppression: the call is made as before.
            assertEquals(1, provider.requests.size)
            assertTrue(written.nothingBeyondHome)
            assertFalse(written.cached)
            // The card served again, with no call, says it again.
            val kept = assertIs<InsightState.Written>(pipeline(HEADER, provider, cache).run(sundown, true).insight)
            assertEquals(1, provider.requests.size)
            assertTrue(kept.cached)
            assertTrue(kept.nothingBeyondHome)
        }

    @Test
    fun `a producer or a source's own text beyond the header writes the card with no such line`() =
        runTest {
            for (beyond in listOf(PRODUCER, line(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."))) {
                val provider = FakeProvider { FakeProvider.done("It was recorded in Toronto [MB3].") }
                val run = pipeline(HEADER + beyond, provider, MemoryCache()).run(sundown, write = true)
                assertFalse(assertIs<InsightState.Written>(run.insight).nothingBeyondHome, beyond.text)
                assertEquals(1, provider.requests.size)
            }
        }

    private companion object {
        val HEADER =
            listOf(
                line(
                    SourceId.MUSICBRAINZ,
                    "“Sundown” by Gordon Lightfoot was first released in 1974, on the album “Sundown”.",
                ),
                line(SourceId.MUSICBRAINZ, "“Sundown” was written by Gordon Lightfoot."),
            )
        val PRODUCER = line(SourceId.MUSICBRAINZ, "This recording of “Sundown” was produced by Lenny Waronker.")
    }
}
