package io.github.wkusnierczyk.sleevesight.sources.lastfm

import io.github.wkusnierczyk.sleevesight.core.Secret
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.genres.GenreVocabulary
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.platform.JvmResourceReader
import io.github.wkusnierczyk.sleevesight.core.sources.Rejection
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.Fixtures
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetError
import io.github.wkusnierczyk.sleevesight.net.NetResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LastFmAdapterTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun adapter(net: NetClient = LastFmStubClient()) =
        LastFmAdapter(net, Secret(KEY), vocabulary, JvmAccentFolder, clock)

    private fun fixtureTrack(n: Int): NormalizedTrack =
        BelowSoundtrack.track(n).let { normalizer.normalize(it.title, it.artist) }

    private fun resolved(vararg artists: Pair<String, String?>) =
        CompletableDeferred(Resolution(artists = artists.map { (name, mbid) -> ResolvedArtist(name, mbid) }))

    private val none = CompletableDeferred(Resolution.NONE)

    @Test
    fun `a match with the resolved MusicBrainz id is verified, and its lines may be cited`() =
        runTest {
            val lookup = adapter().lookupArtists(fixtureTrack(1), resolved("Gordon Lightfoot" to GORDON_LIGHTFOOT))

            assertEquals(Outcome.ArtistArticle, lookup.result.outcome)
            assertEquals(LastFmMatch.Verified, lookup.artists.single().match)
            assertTrue(lookup.result.unverified.isEmpty())
            val url = "https://www.last.fm/music/Gordon+Lightfoot"
            val bio = "The Last.fm biography of Gordon Lightfoot says:"
            assertEquals(
                listOf(
                    "$bio \"Gordon Lightfoot was a Canadian singer-songwriter, born on 17 November 1938 in " +
                        "Orillia, Ontario, who died on 1 May 2023 at the age of 84.\"" to "$url/+wiki",
                    "$bio \"He is credited with helping to define the singer-songwriter era of the 1970s, " +
                        "and is counted among Canada's most influential songwriters.\"" to "$url/+wiki",
                    "$bio \"His biographer Nicholas Jennings summed up his subjects as trains, shipwrecks, " +
                        "rivers, highways, lovers & loneliness.\"" to "$url/+wiki",
                    "Last.fm counts 683,942 listeners for Gordon Lightfoot." to url,
                    "Last.fm listeners tag Gordon Lightfoot as: " +
                        "folk, singer-songwriter, 70s, canadian, acoustic." to url,
                    "Last.fm lists artists similar to Gordon Lightfoot by listening overlap: " +
                        "Jim Croce, John Denver, James Taylor, Harry Chapin." to url,
                ),
                lookup.result.evidence.map { it.text to it.url },
            )
            assertTrue(lookup.result.evidence.all { it.source == SourceId.LASTFM && it.scope == Scope.ARTIST })
            assertTrue(lookup.result.evidence.all { it.retrievedAt == now })
        }

    @Test
    fun `genre labels come from the top tags above the weight floor, through the vocabulary`() =
        runTest {
            val lookup = adapter().lookupArtists(fixtureTrack(1), resolved("Gordon Lightfoot" to GORDON_LIGHTFOOT))
            // `soft rock` is in the vocabulary but weighted 8, under `MIN_TAG_COUNT`.
            assertEquals(
                listOf("Folk", "Singer-Songwriter", "Acoustic", "Country"),
                lookup.artists
                    .single()
                    .genres
                    .map { it.label },
            )
        }

    @Test
    fun `without the top tags, the labels come from the tags of getInfo`() =
        runTest {
            val net =
                LastFmStubClient(override = { request ->
                    val topTags = request.query["method"] == "artist.gettoptags"
                    if (topTags) NetResult.Failure(NetError.Server(502)) else null
                })
            val lookup = adapter(net).lookupArtists(fixtureTrack(1), none)
            assertEquals(
                listOf("Folk", "Singer-Songwriter", "Acoustic"),
                lookup.artists
                    .single()
                    .genres
                    .map { it.label },
            )
        }

    @Test
    fun `with nothing to check against, the match is unverified, shown and not cited`() =
        runTest {
            val lookup = adapter().lookupArtists(fixtureTrack(1), none)

            assertEquals(Outcome.ArtistArticle, lookup.result.outcome)
            assertEquals(LastFmMatch.Unverified, lookup.artists.single().match)
            assertTrue(lookup.result.evidence.isEmpty())
            assertEquals(6, lookup.result.unverified.size)
            assertTrue(
                lookup.artists
                    .single()
                    .genres
                    .isNotEmpty(),
            )
        }

    @Test
    fun `a resolved artist without an MBID leaves the match unverified`() =
        runTest {
            val lookup = adapter().lookupArtists(fixtureTrack(1), resolved("Gordon Lightfoot" to null))
            assertEquals(LastFmMatch.Unverified, lookup.artists.single().match)
        }

    @Test
    fun `track 61, Last fm's correction of Jessiah to an unrelated band, is rejected without resolution`() =
        runTest {
            val n = RegressionCase.WRONG_LASTFM_ARTIST.tracks.single()
            val lookup = adapter().lookupArtists(fixtureTrack(n), none)

            val match = assertIs<LastFmMatch.Rejected>(lookup.artists.single().match)
            assertEquals("Last.fm corrected Jessiah to Jesaiah, and no MusicBrainz id confirms it", match.reason)
            assertEquals(Outcome.Rejected(match.reason), lookup.result.outcome)
            assertEquals(listOf(Rejection("Jessiah", match.reason)), lookup.result.rejections)
            assertTrue(lookup.result.evidence.isEmpty())
            assertTrue(lookup.result.unverified.isEmpty())
            assertTrue(
                lookup.artists
                    .single()
                    .genres
                    .isEmpty(),
            )
        }

    @Test
    fun `track 61 is rejected when resolution finds a different Jessiah`() =
        runTest {
            val n = RegressionCase.WRONG_LASTFM_ARTIST.tracks.single()
            val lookup = adapter().lookupArtists(fixtureTrack(n), resolved("Jessiah" to JESSIAH))

            assertEquals(
                LastFmMatch.Rejected("Last.fm's Jesaiah is a different MusicBrainz artist from Jessiah"),
                lookup.artists.single().match,
            )
            assertTrue(lookup.result.evidence.isEmpty() && lookup.result.unverified.isEmpty())
        }

    @Test
    fun `a corrected name is accepted when the MusicBrainz ids agree`() =
        runTest {
            val track = normalizer.normalize("willow", "Jessiah")
            val lookup = adapter().lookupArtists(track, resolved("Jessiah" to JESAIAH))
            assertEquals(LastFmMatch.Verified, lookup.artists.single().match)
        }

    @Test
    fun `each credited artist is looked up and judged separately, in credit order`() =
        runTest {
            val n = RegressionCase.MULTIPLE_ARTISTS.tracks.single()
            val lookup = adapter().lookupArtists(fixtureTrack(n), none)

            assertEquals(listOf("Peleboy", "Tony K"), lookup.artists.map { it.credited })
            // `PeleBoy` differs from the credit in case only.
            assertEquals(listOf(LastFmMatch.Unverified, LastFmMatch.Unverified), lookup.artists.map { it.match })
            assertEquals(
                listOf(
                    "Last.fm counts 5,325 listeners for Peleboy.",
                    "Last.fm lists artists similar to Peleboy by listening overlap: " +
                        "Tara George, Mad Night, YAEN, Dumi Maraire.",
                    "Last.fm counts 48,210 listeners for Tony K.",
                    "Last.fm listeners tag Tony K as: rap.",
                    "Last.fm lists artists similar to Tony K by listening overlap: Reem, Peleboy.",
                ),
                lookup.result.unverified.map { it.text },
            )
            assertEquals(
                listOf(emptyList(), listOf("Rap", "Hip-Hop")),
                lookup.artists.map { a -> a.genres.map { it.label } },
            )
        }

    @Test
    fun `a page several artists share is never verified, even with the resolved MBID`() =
        runTest {
            val lookup = adapter().lookupArtists(normalizer.normalize("Bad Energy", "Reem"), resolved("Reem" to REEM))

            assertEquals(LastFmMatch.Unverified, lookup.artists.single().match)
            assertTrue(
                lookup.artists
                    .single()
                    .artist!!
                    .sharedPage,
            )
            assertTrue(lookup.result.evidence.isEmpty())
            assertTrue(lookup.result.unverified.isNotEmpty())
        }

    @Test
    fun `one credited artist verified and one rejected, the rejection kept with its reason`() =
        runTest {
            val n = RegressionCase.MULTIPLE_ARTISTS.tracks.single()
            // Resolution names the artist `PeleBoy`, the credit `Peleboy`: matched by name key.
            val lookup =
                adapter().lookupArtists(fixtureTrack(n), resolved("PeleBoy" to PELEBOY, "Tony K" to OTHER_TONY_K))

            assertEquals(
                listOf(LastFmMatch.Verified, LastFmMatch.Rejected(TONY_K_REASON)),
                lookup.artists.map { it.match },
            )
            assertEquals(Outcome.ArtistArticle, lookup.result.outcome)
            assertEquals(listOf(Rejection("Tony K", TONY_K_REASON)), lookup.result.rejections)
            assertEquals(
                listOf("Last.fm counts 5,325 listeners for Peleboy.", "Last.fm lists artists similar to Peleboy"),
                lookup.result.evidence.map { it.text.substringBefore(" by listening") },
            )
            assertTrue(lookup.result.unverified.isEmpty())
            val tonyK = lookup.artists[1]
            assertEquals(null, tonyK.shown)
            assertTrue(tonyK.genres.isEmpty())
        }

    @Test
    fun `a bio shared by several artists of the name gives no sentence`() =
        runTest {
            val lookup = adapter().lookupArtists(normalizer.normalize("Bad Energy", "Reem"), none)
            assertFalse(lookup.result.unverified.any { "biography" in it.text })
            assertTrue(lookup.result.unverified.isNotEmpty())
        }

    @Test
    fun `an artist Last fm does not know is none`() =
        runTest {
            val lookup = adapter().lookupArtists(normalizer.normalize("Song", "Nobody Here"), none)
            assertEquals(LastFmMatch.NotFound, lookup.artists.single().match)
            assertEquals(Outcome.None, lookup.result.outcome)
        }

    @Test
    fun `an error answer with an error status is still read`() =
        runTest {
            val notFound = Fixtures.text("http/lastfm/artist.getinfo/not-found.json")
            val net = LastFmStubClient(override = { LastFmStubClient.ok(notFound, 404) })
            assertEquals(Outcome.None, adapter(net).lookup(normalizer.normalize("Song", "Nobody Here"), none).outcome)
        }

    @Test
    fun `a failed request, a rejected key or an unreadable answer is unreachable`() =
        runTest {
            val failures =
                listOf(
                    NetResult.Failure(NetError.Offline("UnknownHostException")),
                    NetResult.Failure(NetError.Unauthorized(403)),
                    LastFmStubClient.ok(Fixtures.text("http/lastfm/invalid-key.json")),
                    LastFmStubClient.ok("<html>Not found</html>", 404),
                    LastFmStubClient.ok("{}"),
                )
            for (failure in failures) {
                val lookup = adapter(LastFmStubClient(override = { failure })).lookupArtists(fixtureTrack(1), none)
                assertIs<LastFmMatch.Failed>(lookup.artists.single().match, "$failure")
                assertEquals(Outcome.Unreachable, lookup.result.outcome, "$failure")
            }
        }

    @Test
    fun `the key goes in the request's secrets, never in its URL or query`() =
        runTest {
            val net = LastFmStubClient()
            adapter(net).lookup(fixtureTrack(1), none)

            assertEquals(setOf("artist.getinfo", "artist.gettoptags"), net.requests.map { it.query["method"] }.toSet())
            for (request in net.requests) {
                assertEquals(LastFmAdapter.API_URL, request.url)
                assertEquals(mapOf("api_key" to KEY), request.secrets.query)
                assertEquals(
                    mapOf("artist" to "Gordon Lightfoot", "autocorrect" to "1", "format" to "json"),
                    request.query - "method",
                )
                assertFalse(KEY in request.toString())
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the lookup by name starts before the resolution lands (D4)`() =
        runTest {
            val net = LastFmStubClient()
            val resolution = CompletableDeferred<Resolution>()
            val lookup = async { adapter(net).lookupArtists(fixtureTrack(1), resolution) }
            runCurrent()
            assertEquals(2, net.requests.size)
            assertFalse(lookup.isCompleted)

            resolution.complete(Resolution(artists = listOf(ResolvedArtist("Gordon Lightfoot", GORDON_LIGHTFOOT))))
            assertEquals(
                LastFmMatch.Verified,
                lookup
                    .await()
                    .artists
                    .single()
                    .match,
            )
        }

    @Test
    fun `listener counts are grouped by thousands`() {
        assertEquals(
            listOf("0", "2", "999", "1,000", "5,325", "683,942", "1,840,320"),
            listOf(0L, 2L, 999L, 1000L, 5325L, 683942L, 1840320L).map(LastFmAdapter::grouped),
        )
    }

    private companion object {
        const val KEY = "lastfm-test-key-0123456789"
        const val GORDON_LIGHTFOOT = "9d97b077-b28d-4ba8-a3d9-c71926e3b2b6"
        const val JESAIAH = "b26aefc5-3332-462b-b770-1baf152e3a08"
        const val JESSIAH = "ac5019aa-c554-4ca0-b29b-ef4e5fe31b2d"
        const val PELEBOY = "00000000-0000-4000-8000-000000000014"
        const val REEM = "00000000-0000-4000-8000-000000000012"
        const val OTHER_TONY_K = "00000000-0000-4000-8000-0000000001ff"
        const val TONY_K_REASON = "Last.fm's Tony K is a different MusicBrainz artist from Tony K"
    }
}
