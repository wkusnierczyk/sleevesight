package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * With no evidence at all, no insight is written (#1142): neither *Key insights* nor the song's, the
 * artist's or the album's, and no call is made; *Key insights* shows the app's own text
 * ([InsightState.NoEvidence], "The sources give nothing to write an insight from.").
 */
class NoEvidenceInsightsTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val provider = FakeProvider { FakeProvider.done("Something [P1].") }
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // An album no source finds anything about.
    private class NoAlbum : AlbumLookup {
        override suspend fun renewWeb(query: AlbumQuery) = Unit

        override suspend fun lookup(query: AlbumQuery): AlbumFound = AlbumFound(emptyList(), webDue = false)

        override val searchesWeb: Boolean get() = false

        override suspend fun searchWeb(
            query: AlbumQuery,
            mayPay: () -> Boolean,
            keeping: (List<EvidenceDraft>) -> Unit,
            sent: () -> Unit,
        ): List<EvidenceDraft>? = null
    }

    @Test
    fun `no source finding anything writes none of the four insights, and makes no call`() =
        runTest {
            val nothing =
                FakeAdapter(SourceId.WIKIPEDIA) { _, _ -> SourceResult(Outcome.None, emptyList(), Duration.ZERO) }
            val track =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(nothing),
                        resolver = FakeResolver(),
                        hasher = JvmHasher,
                        ai = AiAccess(provider, Secret("sk-test")),
                        cache = MemoryCache(),
                    ),
                    PipelineTime(timeSource = testTimeSource),
                ) { settings }.run(sundown, true)
            // The player's own release alone, which tells only what the header shows.
            assertEquals(listOf("P1"), track.evidence?.ids)
            assertEquals(InsightState.NoEvidence, track.insight)

            val ai = AiAccess(provider, Secret("sk-test"))
            val items = ItemInsights(ai, MemoryCache(), JvmHasher, NameMatcher(JvmAccentFolder), albums = NoAlbum())
            for (item in ROW_ITEMS) {
                val evidence = checkNotNull(items.evidence(item, track, settings)) { item.name }
                val key = checkNotNull(items.key(item, track, evidence, settings)) { item.name }
                assertEquals(InsightState.NoEvidence, items.write(key, item, track, evidence) { _, _ -> }, item.name)
            }
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun `an empty evidence set has nothing to narrate whatever the switch`() {
        val empty = EvidenceSet.of(emptyList(), JvmHasher)
        for (allowModel in listOf(false, true)) {
            assertTrue(empty.nothingToNarrate(allowModel, artistsKnown = true))
        }
    }
}
