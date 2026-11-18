package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.ZERO

/**
 * A further artist's insight on a track crediting several, with no composer told apart (#1168): the
 * *Artists* panel's section for that artist is its named insight ([InsightItem.named]), about that
 * artist from their own lines alone; its card is the one the artist's own row has on a track of
 * theirs, so it is written once and shown again on their other tracks.
 */
class EveryArtistItemInsightsTest {
    private val items =
        ItemInsights(
            AiAccess(FakeProvider { FakeProvider.done("A line [W1].") }, Secret("k")),
            MemoryCache(),
            JvmHasher,
            NameMatcher(JvmAccentFolder),
        )
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun about(
        artist: String,
        text: String,
    ) = line(SourceId.WIKIPEDIA, text, Scope.ARTIST).copy(about = artist)

    private val gotye = about(GOTYE, "Gotye is a Belgian-Australian multi-instrumentalist.")
    private val kimbra = about(KIMBRA, "Kimbra is a New Zealand singer and songwriter.")

    private fun track(
        title: String,
        artists: String,
        album: String,
        drafts: List<EvidenceDraft>,
    ): TrackState {
        val playing = nowPlaying(title, artists, album)
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.ArtistArticle, drafts, ZERO), false),
                ),
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
        )
    }

    private val duet = track("Somebody That I Used To Know", "$GOTYE, $KIMBRA", "Making Mirrors", listOf(gotye, kimbra))
    private val solo = track("Settle Down", KIMBRA, "Vows", listOf(kimbra))

    private suspend fun texts(
        item: InsightItem,
        on: TrackState,
    ) = checkNotNull(items.evidence(item, on, settings)).evidence.lines.map { it.text }

    @Test
    fun `the first artist's row and a further artist's section are each about their own artist`() =
        runTest {
            assertEquals(GOTYE, items.name(InsightItem.ARTIST, duet))
            assertEquals(listOf(gotye.text), texts(InsightItem.ARTIST, duet))
            val further = InsightItem.named(KIMBRA)
            assertEquals(KIMBRA, items.name(further, duet))
            assertEquals(listOf(kimbra.text), texts(further, duet))
        }

    @Test
    fun `a further artist's insight is the one their own track's artist row keeps, so it is written once`() =
        runTest {
            val further = InsightItem.named(KIMBRA)
            assertEquals(items.entity(InsightItem.ARTIST, solo), items.entity(further, duet))
            val onDuet = checkNotNull(items.evidence(further, duet, settings))
            val onSolo = checkNotNull(items.evidence(InsightItem.ARTIST, solo, settings))
            assertEquals(
                items.keptKey(InsightItem.ARTIST, solo, onSolo, settings),
                items.keptKey(further, duet, onDuet, settings),
            )
        }

    private companion object {
        const val GOTYE = "Gotye"
        const val KIMBRA = "Kimbra"
    }
}
