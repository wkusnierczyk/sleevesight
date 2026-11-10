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
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/**
 * When the artist's insight says the sources give nothing to write it from, for a well-documented
 * band credited with a guest (#1163: *Teardrop*, "Massive Attack, Elizabeth Fraser"): MusicBrainz not
 * reached leaves it with no line (Last.fm's unverified, Wikipedia's article unconfirmed), which it
 * names; with MusicBrainz reached, the primary artist's lines are taken and the insight is written,
 * with no web search, since the sources describe the artist; a reply of no evidence is kept, and
 * served again with no call.
 */
class ArtistNothingInsightsTest {
    private val cache = MemoryCache()
    private val names = NameMatcher(JvmAccentFolder)
    private val artistWeb = SearchCount()

    private val webOff = Settings(aiProvider = ProviderId.ANTHROPIC, aiWebSearch = AiWebSearch.OFF)

    private val webOnce =
        Settings(
            aiProvider = ProviderId.ANTHROPIC,
            enabledSources = Settings().enabledSources + SourceSwitch.WEB,
            webEngines = setOf(WebEngine.EXA),
            aiWebSearch = AiWebSearch.ONCE,
        )

    // Counts the artist's web searches, which find nothing.
    private class SearchCount : ArtistWebLookup {
        var searches = 0

        override suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>? = null

        override suspend fun search(
            artist: ArtistWebQuery,
            sent: () -> Unit,
            keeping: (List<EvidenceDraft>) -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft>? {
            searches++
            return emptyList()
        }

        override suspend fun keep(
            artist: ArtistWebQuery,
            lines: List<EvidenceDraft>,
        ) = Unit

        override suspend fun renew(artist: ArtistWebQuery) = Unit

        override fun pageLine(
            artist: ArtistWebQuery,
            page: WebPage,
        ): String? = null
    }

    private fun items(provider: FakeProvider) =
        ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, artistWeb = artistWeb)

    private val teardrop =
        NowPlaying(
            title = "Teardrop",
            artists = "Massive Attack, Elizabeth Fraser",
            album = "Mezzanine",
            player = "p",
            duration = 330.seconds,
        )

    private fun about(
        source: SourceId,
        artist: String,
        text: String,
    ) = line(source, text, Scope.ARTIST).copy(about = artist)

    // Last.fm's lines for each credited artist: its bio, and a count every artist of the name adds to.
    private fun lastFm(artist: String) =
        listOf(
            about(SourceId.LASTFM, artist, "The Last.fm biography of $artist says: \"$artist is from Bristol.\""),
            about(SourceId.LASTFM, artist, "Last.fm counts 3,000,000 listeners for $artist.").copy(aggregate = true),
        )

    private val wikipediaSong = line(SourceId.WIKIPEDIA, "\"Teardrop\" is a song by Massive Attack.")

    // *Teardrop* with every source answered: MusicBrainz as [musicBrainz] says, Last.fm and Wikipedia as
    // their adapters answer for it, each line [cited] or not.
    private fun track(
        musicBrainz: Outcome,
        resolution: Resolution,
        lastFm: SourceResult,
        wikipedia: SourceResult,
    ): TrackState {
        val drafts = lastFm.evidence + wikipedia.evidence
        return TrackState(
            nowPlaying = teardrop,
            normalized = Normalizer(JvmAccentFolder).normalize(teardrop),
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to SourceState.Done(SourceResult(musicBrainz, emptyList(), ZERO), false),
                    SourceId.LASTFM to SourceState.Done(lastFm, false),
                    SourceId.WIKIPEDIA to SourceState.Done(wikipedia, false),
                ),
            resolution = resolution,
            evidence = EvidenceSet.of(playerLines(teardrop, RETRIEVED) + drafts, JvmHasher),
        )
    }

    // MusicBrainz not reached: nothing to verify Last.fm's artists by, which stay unverified (shown,
    // never given to the model), nor to confirm Wikipedia's articles on the artists, which are rejected.
    private val unreached =
        track(
            Outcome.Unreachable,
            Resolution.unreachable("rate limited"),
            SourceResult(
                Outcome.ArtistArticle,
                emptyList(),
                ZERO,
                unverified = lastFm("Massive Attack") + lastFm("Elizabeth Fraser"),
            ),
            SourceResult(
                Outcome.SongArticle,
                listOf(wikipediaSong),
                ZERO,
                rejections = listOf(Rejection("Massive Attack", "no MusicBrainz id", Scope.ARTIST, true)),
            ),
        )

    // MusicBrainz reached: it credits the band alone, whose Last.fm page and Wikipedia article are
    // confirmed, the guest's Last.fm page unverified, and her article found by her name.
    private val reached =
        track(
            Outcome.SongArticle,
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = "rec-teardrop",
                artists = listOf(ResolvedArtist("Massive Attack", "mb-massive-attack")),
            ),
            SourceResult(
                Outcome.ArtistArticle,
                lastFm("Massive Attack"),
                ZERO,
                unverified = lastFm("Elizabeth Fraser"),
            ),
            SourceResult(
                Outcome.SongArticle,
                listOf(
                    wikipediaSong,
                    about(SourceId.WIKIPEDIA, "Massive Attack", "Massive Attack are an English trip hop collective."),
                    about(SourceId.WIKIPEDIA, "Elizabeth Fraser", "Elizabeth Fraser is a Scottish singer."),
                ),
                ZERO,
            ),
        )

    @Test
    fun `MusicBrainz not reached leaves the band with no line, and its evidence names it, not nothing found`() =
        runTest {
            val provider = FakeProvider()
            val items = items(provider)
            for (settings in listOf(webOff, webOnce)) {
                val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, unreached, settings))
                assertTrue(evidence.evidence.isEmpty())
                // Unreachable, gathered again, and naming MusicBrainz for its row, AI web search off too.
                assertTrue(evidence.unreachable)
                assertEquals(listOf(SourceId.MUSICBRAINZ), evidence.unanswered)
                // No search while a source that may describe the artist could not be asked (#912).
                assertFalse(evidence.webDue)
                assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, unreached, settings, evidence))
                // Nothing to write from: no call.
                val key = checkNotNull(items.key(InsightItem.ARTIST, unreached, evidence, settings))
                val state = items.write(key, InsightItem.ARTIST, unreached, evidence) { _, _ -> }
                assertEquals(InsightState.NoEvidence, state)
            }
            assertEquals(0, artistWeb.searches)
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun `MusicBrainz reached, the band's own lines are taken, not the guest's, and it is written with no search`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Massive Attack are an English trip hop collective [W1].") }
            val items = items(provider)
            for (settings in listOf(webOff, webOnce)) {
                val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, reached, settings))
                // The band's bio and article, numbered on their own; not its listeners, nor the guest's lines.
                assertEquals(
                    setOf(
                        "The Last.fm biography of Massive Attack says: \"Massive Attack is from Bristol.\"",
                        "Massive Attack are an English trip hop collective.",
                    ),
                    evidence.evidence.lines
                        .map { it.text }
                        .toSet(),
                )
                assertFalse(evidence.unreachable)
                assertTrue(evidence.unanswered.isEmpty())
                // The sources describe the artist: no web search is due (#912), so "0 web searches".
                assertFalse(evidence.webDue)
                assertSame(ItemSearch.None, items.search(InsightItem.ARTIST, reached, settings, evidence))
            }
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, reached, webOnce))
            val key = checkNotNull(items.key(InsightItem.ARTIST, reached, evidence, webOnce))
            assertIs<InsightState.Written>(items.write(key, InsightItem.ARTIST, reached, evidence) { _, _ -> })
            assertEquals(1, provider.requests.size)
            assertEquals(0, artistWeb.searches)
        }

    @Test
    fun `a reply of no evidence is kept, and served again for the same lines with no call`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(GroundingCheck.NO_EVIDENCE) }
            val items = items(provider)
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, reached, webOff))
            val key = checkNotNull(items.key(InsightItem.ARTIST, reached, evidence, webOff))
            assertEquals(InsightState.NoEvidence, items.write(key, InsightItem.ARTIST, reached, evidence) { _, _ -> })
            assertEquals(1, provider.requests.size)
            // Kept: the next play of the band finds it, and asks nothing, until it expires or its lines change.
            assertEquals(InsightState.NoEvidence, items.kept(key, evidence)?.state)
        }
}
