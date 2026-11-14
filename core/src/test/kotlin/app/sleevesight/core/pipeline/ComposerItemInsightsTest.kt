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
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.ZERO

/**
 * The composer's insight beside the artist's (#1199): on a track whose roles tell the composer apart,
 * the Composer row is about the composer and the Artist row about the lead performer, each from the
 * lines about them alone; another performer's chip about that performer; the composer's card is the
 * one an artist's insight about the composer has, so it is written once. A track whose roles tell no
 * composer keeps one Artist row, about the primary artist, as before.
 */
class ComposerItemInsightsTest {
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

    private val lines =
        listOf(
            about(MOZART, "Wolfgang Amadeus Mozart was an Austrian composer."),
            about(KHATIA, "Khatia Buniatishvili is a Georgian-French pianist."),
            about(ACADEMY, "The Academy of St Martin in the Fields is a chamber orchestra."),
        )

    private fun track(
        title: String,
        artists: String,
        drafts: List<EvidenceDraft> = lines,
        roles: ArtistRoles? = null,
    ): TrackState {
        val playing = nowPlaying(title, artists, "Mozart: Piano Concertos Nos. 20 & 23")
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.ArtistArticle, drafts, ZERO), false),
                ),
            resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) },
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
        )
    }

    private val k545 = track(K545, "$MOZART, $KHATIA, $ACADEMY")

    private suspend fun texts(
        item: InsightItem,
        on: TrackState,
    ) = checkNotNull(items.evidence(item, on, settings)).evidence.lines.map { it.text }

    @Test
    fun `the composer's row is about the composer, the artist's about the lead performer, each from their own lines`() =
        runTest {
            assertEquals(MOZART, items.name(InsightItem.COMPOSER, k545))
            assertEquals(KHATIA, items.name(InsightItem.ARTIST, k545))
            assertEquals(CardEntity.Artist(KHATIA, null), items.entity(InsightItem.ARTIST, k545))
            assertEquals(listOf(lines[0].text), texts(InsightItem.COMPOSER, k545))
            assertEquals(listOf(lines[1].text), texts(InsightItem.ARTIST, k545))
            // Another performer's chip: that performer's own lines.
            val academy = InsightItem.named(ACADEMY)
            assertEquals(ACADEMY, items.name(academy, k545))
            assertEquals(listOf(lines[2].text), texts(academy, k545))
        }

    @Test
    fun `the roles MusicBrainz told split a title with no composer lead`() =
        runTest {
            val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA))
            val nessun = track("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", emptyList(), roles)
            assertEquals(PUCCINI, items.name(InsightItem.COMPOSER, nessun))
            assertEquals(PAVAROTTI, items.name(InsightItem.ARTIST, nessun))
            assertEquals(listOf(MEHTA), items.split(nessun)?.others)
            // Unresolved, nothing tells the roles: one Artist row, about the first credited artist.
            val unknown = track("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", emptyList())
            assertNull(items.name(InsightItem.COMPOSER, unknown))
            assertNull(items.evidence(InsightItem.COMPOSER, unknown, settings))
            assertEquals(PUCCINI, items.name(InsightItem.ARTIST, unknown))
        }

    @Test
    fun `the composer's card is the one an artist's insight about the composer has, written once`() =
        runTest {
            val composer = checkNotNull(items.evidence(InsightItem.COMPOSER, k545, settings))
            val alone = track("Eine kleine Nachtmusik", MOZART, listOf(lines[0]))
            val artist = checkNotNull(items.evidence(InsightItem.ARTIST, alone, settings))
            assertEquals(
                items.key(InsightItem.ARTIST, alone, artist, settings),
                items.key(InsightItem.COMPOSER, k545, composer, settings),
            )
        }

    @Test
    fun `a pop track keeps one Artist row about its primary artist`() =
        runTest {
            val pop = track("Somebody That I Used To Know", "Gotye, Kimbra", emptyList())
            assertNull(items.name(InsightItem.COMPOSER, pop))
            assertEquals("Gotye", items.name(InsightItem.ARTIST, pop))
            assertEquals(CardEntity.Artist("Gotye", null), items.entity(InsightItem.ARTIST, pop))
        }

    private companion object {
        const val K545 = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"
        const val ACADEMY = "Academy of St. Martin in the Fields"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
    }
}
