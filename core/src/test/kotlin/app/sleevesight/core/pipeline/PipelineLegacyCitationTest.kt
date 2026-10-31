package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceAdapter
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A card kept in the synthesis cache from before web search's lines were `S1`, `S2` (#769) cites
 * them `[G1]`, `[G2]`. The evidence fingerprint still spells web search's ids so (`EvidenceSet`), so
 * the card has the key one written now has, and its citations reach the lines now called `S1`, `S2`.
 */
class PipelineLegacyCitationTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter>,
        provider: FakeProvider,
        cache: PipelineCache,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = normalizer,
                adapters = adapters,
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = ProviderId.ANTHROPIC) }

    @Test
    fun `a card kept before 0_1_12 citing G1 is still served, its G1 read as web search's line S1`() =
        runTest {
            val cache = MemoryCache()
            val provider = FakeProvider { FakeProvider.done("It is a song [MB1]. It went gold [S1].") }
            val web = FakeAdapter(SourceId.WEB, verifies = false)
            val pipeline = pipeline(listOf(FakeAdapter(SourceId.MUSICBRAINZ), web), provider, cache)
            pipeline.run(sundown, write = true)
            val prompt = provider.requests.single().prompt
            assertTrue("[S1]" in prompt)
            assertFalse("[G1]" in prompt)
            val key = cache.cards.keys.single()
            cache.cards[key] = "It is a song [MB1]. It went gold [G1]."

            val state = pipeline.run(sundown, write = true)
            val written = assertIs<InsightState.Written>(state.insight)

            assertTrue(written.cached)
            assertEquals(1, provider.requests.size)
            assertEquals("It is a song [MB1]. It went gold [G1].", written.grounded.text)
            val sentences = written.grounded.paragraphs.flatten()
            assertEquals(listOf(listOf("MB1"), listOf("S1")), sentences.map { it.cites })
            assertEquals(2, written.grounded.counts.ok)
            assertEquals(SourceId.WEB, state.evidence?.get(sentences.last().cites.single())?.source)
        }
}
