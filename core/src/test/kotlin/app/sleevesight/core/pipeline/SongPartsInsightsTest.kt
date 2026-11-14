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
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.synthesis.SongParts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * The song's insight in two parts, the music and this recording (#1133): on a track whose roles tell
 * its composer apart from its performers, asked for under a prompt version of its own, its part
 * headings read as headings, as written and as kept; a pop track's song card asked for and keyed as
 * before.
 */
class SongPartsInsightsTest {
    private val provider = FakeProvider { FakeProvider.done(REPLY) }
    private val cache = MemoryCache()
    private val items =
        ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, NameMatcher(JvmAccentFolder))
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private val songLines: List<EvidenceDraft> =
        listOf(
            line(SourceId.WIKIPEDIA, "The Piano Sonata No. 16 in C major, K. 545, was described as for beginners."),
            line(SourceId.WIKIDATA, "Piano Sonata No. 16 was created on June 26, 1788."),
        )

    private fun track(
        title: String,
        artists: String,
        roles: ArtistRoles?,
    ): TrackState {
        val playing = nowPlaying(title, artists, "Mozart: Piano Concertos Nos. 20 & 23")
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to
                        SourceState.Done(SourceResult(Outcome.SongArticle, songLines, ZERO), false),
                ),
            resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) },
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + songLines, JvmHasher),
        )
    }

    private val k545 = track(K545, "$MOZART, $KHATIA", ArtistRoles(listOf(MOZART), listOf(KHATIA)))
    private val pop = track("Somebody That I Used To Know", "Gotye, Kimbra", null)

    @Test
    fun `a split track's song is asked for in two parts, and its headings are read as such, written and kept`() =
        runTest {
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, k545, settings))
            assertTrue(evidence.parts)
            val key = checkNotNull(items.key(InsightItem.SONG, k545, evidence, settings))
            assertEquals(ItemPrompt.PARTS_VERSION, key.promptVersion)
            val written = assertIs<InsightState.Written>(items.write(key, InsightItem.SONG, k545, evidence) { _, _ -> })
            val prompt = provider.requests.single().prompt
            assertTrue(ItemPrompt.PARTS_TASK in prompt)
            assertTrue(ItemPrompt.PARTS_RULE in prompt)
            assertFalse(ItemPrompt.subjectRule(InsightItem.SONG.kind) in prompt)
            for (state in listOf(written, assertIs<InsightState.Written>(items.kept(key, evidence)?.state))) {
                val paragraphs = state.grounded.paragraphs
                assertEquals(
                    listOf(SongParts.MUSIC, null, SongParts.RECORDING, null),
                    paragraphs.map { p -> p.singleOrNull()?.takeIf { it.heading }?.text },
                )
                // The headings are no sentences of the counts: two sentences, both cited.
                assertEquals(2, state.grounded.counts.total)
                assertEquals(2, state.grounded.counts.ok)
            }
        }

    @Test
    fun `a pop track's song is asked for and keyed as before, a heading-like line read as text`() =
        runTest {
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, pop, settings))
            assertFalse(evidence.parts)
            val key = checkNotNull(items.key(InsightItem.SONG, pop, evidence, settings))
            assertEquals(ItemPrompt.VERSION, key.promptVersion)
            val written = assertIs<InsightState.Written>(items.write(key, InsightItem.SONG, pop, evidence) { _, _ -> })
            val prompt = provider.requests.single().prompt
            assertTrue(ItemPrompt.subjectRule(InsightItem.SONG.kind) in prompt)
            assertFalse(ItemPrompt.PARTS_RULE in prompt)
            assertTrue(
                written.grounded.paragraphs
                    .flatten()
                    .none { it.heading },
            )
        }

    private companion object {
        const val K545 = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"

        // As the model writes it: each heading on a line of its own, the second straight above its part.
        const val REPLY =
            "The music\n\nMozart described the sonata as for beginners, and wrote it in 1788 [W1][WD1].\n\n" +
                "This recording\nThis recording is on the album Mozart: Piano Concertos Nos. 20 & 23 [P1]."
    }
}
