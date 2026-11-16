package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
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
        provider: FakeProvider?,
        cache: PipelineCache,
        selected: ProviderId = ProviderId.ANTHROPIC,
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
                ai = provider?.let { AiAccess(it, Secret("sk-test")) },
                cache = cache,
                // No AI access: the selected model found unavailable (#540), as `keptWithoutService` serves.
                modelUnavailable = provider == null,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = selected) }

    @Test
    fun `a card served with the selected model unavailable says it too, its own or another model's`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Gordon Lightfoot wrote it [MB2].") }
            val cache = MemoryCache()
            assertIs<InsightState.Written>(pipeline(HEADER, provider, cache).run(sundown, true).insight)
            // The selected model's own card (`keptWithoutService`, served under its key).
            val own = assertIs<InsightState.Written>(pipeline(HEADER, null, cache).run(sundown, true).insight)
            assertTrue(own.cached)
            assertTrue(own.nothingBeyondHome)
            // Another service selected: the card the first kept (`keptByOther`).
            val other = pipeline(HEADER, null, cache, selected = ProviderId.OPENAI).run(sundown, true).insight
            val shown = assertIs<InsightState.Written>(other)
            assertEquals(ProviderId.ANTHROPIC, shown.writtenBy?.provider)
            assertTrue(shown.nothingBeyondHome)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `the prompt's line is decided on the lines sent, the card's note on the whole evidence`() =
        runTest {
            // Four lines of the song's own, so the artist's line is left out of the prompt (#239).
            val own =
                HEADER +
                    listOf(
                        line(SourceId.MUSICBRAINZ, "Gordon Lightfoot performs on this recording of “Sundown”."),
                        line(SourceId.WIKIDATA, "“Sundown” was first published in 1974."),
                    )
            val artist = line(SourceId.MUSICBRAINZ, "Gordon Lightfoot was a Canadian singer.", Scope.ARTIST)
            val provider = FakeProvider { FakeProvider.done("Gordon Lightfoot wrote it [MB2].") }
            val run = pipeline(own + artist, provider, MemoryCache()).run(sundown, true)
            assertFalse(assertIs<InsightState.Written>(run.insight).nothingBeyondHome, "the artist's line is beyond")
            val prompt = provider.requests.single().prompt
            assertFalse("Canadian singer" in prompt, "left out of the prompt")
            assertTrue(HEADER.first().text in prompt, "with nothing else sent, the header's lines stay")
            assertTrue(prompt.endsWith(InsightPrompt.NOTHING_BEYOND), prompt)
        }

    @Test
    fun `the release and the artist's own credit are written, the card saying nothing more was found`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Gordon Lightfoot wrote it [MB2].") }
            val cache = MemoryCache()
            val written = assertIs<InsightState.Written>(pipeline(HEADER, provider, cache).run(sundown, true).insight)
            // No new suppression: the call is made as before, the thin case told in the user message.
            assertEquals(1, provider.requests.size)
            val prompt = provider.requests.single().prompt
            assertTrue(prompt.endsWith("</evidence>\n\n" + InsightPrompt.NOTHING_BEYOND), prompt)
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
                val prompt = provider.requests.single().prompt
                assertFalse(InsightPrompt.NOTHING_BEYOND in prompt, beyond.text)
                // The header's lines left out (`PromptEvidence`), the line beyond it sent.
                assertTrue(HEADER.none { it.text in prompt } && beyond.text in prompt, prompt)
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
