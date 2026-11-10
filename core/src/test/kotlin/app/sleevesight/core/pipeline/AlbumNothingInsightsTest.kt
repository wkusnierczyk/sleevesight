package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/**
 * The album's insight with nothing to write from while a source could not be reached (#1187): its
 * evidence names the sources its own lookups did not reach, gathered again by *Retry*, and MusicBrainz
 * when the track's lookup of it did not answer, so the album has no release group, asked again as the
 * track's *Retry* asks it; with every source answered, or with a line to write from, none.
 */
class AlbumNothingInsightsTest {
    private val provider = FakeProvider { FakeProvider.done("Sundown is his tenth album [W1].") }
    private val albums = Albums()
    private val items =
        ItemInsights(
            AiAccess(provider, Secret("k")),
            MemoryCache(),
            JvmHasher,
            NameMatcher(JvmAccentFolder),
            albums = albums,
        )
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // The album's lookup: [lines], with [unanswered] not reached; [throws] for a bug; with [web], a search
    // due, which finds those lines.
    private class Albums : AlbumLookup {
        var lines: List<EvidenceDraft> = emptyList()
        var unanswered: List<SourceId> = emptyList()
        var throws = false
        var web: List<EvidenceDraft>? = null
        val asked = mutableListOf<AlbumQuery>()

        override suspend fun lookup(query: AlbumQuery): AlbumFound {
            asked += query
            check(!throws) { "a bug" }
            return AlbumFound(lines, unanswered, webDue = web != null && query.web)
        }

        override val searchesWeb: Boolean get() = web != null

        override suspend fun searchWeb(
            query: AlbumQuery,
            mayPay: () -> Boolean,
            keeping: (List<EvidenceDraft>) -> Unit,
            sent: () -> Unit,
        ): List<EvidenceDraft>? = web?.also { sent() }
    }

    // A track of Sundown, its MusicBrainz lookup ended as [musicBrainz]: reached, the album's release
    // group known; else none.
    private fun track(musicBrainz: SourceState = done(Outcome.SongArticle)): TrackState {
        val playing =
            NowPlaying(
                title = "Sundown",
                artists = "Gordon Lightfoot",
                album = "Sundown",
                player = "p",
                duration = 200.seconds,
            )
        val song = line(SourceId.WIKIPEDIA, "Sundown is a song.")
        val reached = (musicBrainz as? SourceState.Done)?.result?.outcome == Outcome.SongArticle
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to musicBrainz,
                    SourceId.WIKIPEDIA to done(Outcome.SongArticle, listOf(song)),
                ),
            resolution =
                if (reached) {
                    Resolution(
                        outcome = Outcome.SongArticle,
                        recordingMbid = "rec-sundown",
                        artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
                        releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "rg-sundown", "Album")),
                    )
                } else {
                    Resolution.unreachable("io")
                },
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + song, JvmHasher),
        )
    }

    private fun done(
        outcome: Outcome,
        lines: List<EvidenceDraft> = emptyList(),
    ) = SourceState.Done(SourceResult(outcome, lines, ZERO), false)

    private suspend fun evidence(track: TrackState = track()) =
        checkNotNull(items.evidence(InsightItem.ALBUM, track, settings))

    @Test
    fun `a source the album's lookup did not reach is named, and a Retry gathers the album again`() =
        runTest {
            albums.unanswered = listOf(SourceId.WIKIPEDIA)
            val evidence = evidence()
            assertTrue(evidence.unreachable)
            assertEquals(listOf(SourceId.WIKIPEDIA), evidence.unanswered)
            // Not the track's Wikipedia, which answered: the album's own lookup, gathered again.
            assertEquals(emptyList(), evidence.retry)
            assertTrue(checkNotNull(evidence.held).gatherAgain)
            // Still nothing to write from: no call.
            val key = checkNotNull(items.key(InsightItem.ALBUM, track(), evidence, settings))
            assertEquals(InsightState.NoEvidence, items.write(key, InsightItem.ALBUM, track(), evidence) { _, _ -> })
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun `MusicBrainz not reached on the track, the album with no group names it, asked again as the track's`() =
        runTest {
            val evidence = evidence(track(done(Outcome.Unreachable)))
            assertNull(albums.asked.single().group)
            assertEquals(listOf(SourceId.MUSICBRAINZ), evidence.unanswered)
            assertEquals(listOf(SourceId.MUSICBRAINZ), evidence.retry)
            assertFalse(checkNotNull(evidence.held).gatherAgain)
        }

    @Test
    fun `MusicBrainz and the album's Wikipedia not reached are named together`() =
        runTest {
            albums.unanswered = listOf(SourceId.WIKIPEDIA)
            val evidence = evidence(track(done(Outcome.Unreachable)))
            assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), evidence.unanswered)
            assertEquals(listOf(SourceId.MUSICBRAINZ), evidence.retry)
            assertTrue(checkNotNull(evidence.held).gatherAgain)
        }

    @Test
    fun `with every source answered and no line, nothing is named`() =
        runTest {
            val evidence = evidence()
            assertNull(evidence.held)
            assertFalse(evidence.unreachable)
        }

    @Test
    fun `with a line to write from, a source not reached is not named, and the insight is written`() =
        runTest {
            albums.lines =
                listOf(line(SourceId.MUSICBRAINZ, "“Sundown” is an album by Gordon Lightfoot.", Scope.RELEASE))
            albums.unanswered = listOf(SourceId.WIKIPEDIA)
            val track = track()
            val evidence = evidence(track)
            assertTrue(evidence.unreachable)
            assertNull(evidence.held)
            val key = checkNotNull(items.key(InsightItem.ALBUM, track, evidence, settings))
            assertIs<InsightState.Written>(items.write(key, InsightItem.ALBUM, track, evidence) { _, _ -> })
        }

    @Test
    fun `a lookup that threw names nothing, its sources failed for the diagnostics`() =
        runTest {
            albums.throws = true
            val evidence = evidence()
            assertTrue(evidence.unreachable)
            assertEquals(emptyList(), evidence.unanswered)
            assertEquals(
                listOf(SourceId.MUSICBRAINZ, SourceId.WIKIDATA, SourceId.WIKIPEDIA, SourceId.LASTFM),
                evidence.failed,
            )
            // Wikidata with Wikipedia's switch: off, neither is named.
            val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WIKIPEDIA)
            assertEquals(
                listOf(SourceId.MUSICBRAINZ, SourceId.LASTFM),
                checkNotNull(items.evidence(InsightItem.ALBUM, track(), off)).failed,
            )
        }

    @Test
    fun `MusicBrainz that failed on the track is not named, only in the diagnostics`() =
        runTest {
            val evidence = evidence(track(SourceState.Failed("bug")))
            assertEquals(emptyList(), evidence.unanswered)
            assertEquals(listOf(SourceId.MUSICBRAINZ), evidence.failed)
        }

    @Test
    fun `a web search that finds nothing keeps the sources not reached, and one that finds lines drops them`() =
        runTest {
            val web =
                settings.copy(
                    enabledSources = Settings().enabledSources + SourceSwitch.WEB,
                    webEngines = setOf(WebEngine.EXA),
                )
            albums.unanswered = listOf(SourceId.WIKIPEDIA)
            albums.web = emptyList()
            val track = track()
            val gathered = checkNotNull(items.evidence(InsightItem.ALBUM, track, web))
            assertTrue(gathered.webDue)
            val none = assertNotNull(items.searched(InsightItem.ALBUM, track, web, gathered))
            assertEquals(listOf(SourceId.WIKIPEDIA), none.unanswered)

            albums.web = listOf(line(SourceId.WEB, "Sundown topped the Billboard 200.", Scope.WEB))
            val found = assertNotNull(items.searched(InsightItem.ALBUM, track, web, gathered))
            assertNull(found.held)
        }
}
