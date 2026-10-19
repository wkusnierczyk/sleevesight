package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtist
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.fixtures.RegressionCase
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetResult
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

    private val musicBrainz = ArtistSearchStub()

    private fun adapter(
        net: NetClient = LastFmStubClient(),
        artists: ArtistSearchStub = musicBrainz,
    ) = LastFmAdapter(net, artists, Secret(KEY), vocabulary, JvmAccentFolder, clock)

    private fun fixtureTrack(n: Int): NormalizedTrack =
        BelowSoundtrack.track(n).let { normalizer.normalize(it.title, it.artist) }

    private fun resolved(vararg artists: Pair<String, String?>) =
        CompletableDeferred(Resolution(artists = artists.map { (name, mbid) -> ResolvedArtist(name, mbid) }))

    private val none = CompletableDeferred(Resolution.NONE)

    private fun gordonResolved() = resolved("Gordon Lightfoot" to GORDON_LIGHTFOOT)

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
            // The result carries the labels of the artists whose data may be shown, for the pipeline.
            assertEquals(listOf("Rap", "Hip-Hop"), lookup.result.genres.map { it.label })
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
            assertFalse(lookup.result.unverified.any { "biography" in it.text })
        }

    @Test
    fun `a shared page the bio's wording misses gets no aggregate verified, only its bio sentences`() =
        runTest {
            // Shared pages the bio's wording misses: a list numbered in brackets, and a bio about one
            // of the artists only.
            val bios =
                listOf(
                    "(1) Reem is a rapper from Denmark with Moroccan roots. (2) Reem is a singer from Lebanon.",
                    "Reem is a rapper from Denmark with Moroccan roots, whose debut single came out in 2019.",
                )
            for (bio in bios) {
                val body = Fixtures.text("http/lastfm/artist.getinfo/reem.json").replace(REEM_BIO, bio)
                val net =
                    LastFmStubClient(override = { request ->
                        LastFmStubClient.ok(body).takeIf { request.query["method"] == "artist.getinfo" }
                    })
                val artists = ArtistSearchStub()
                val lookup =
                    adapter(net, artists).lookupArtists(
                        normalizer.normalize("Bad Energy", "Reem"),
                        resolved("Reem" to REEM),
                    )

                val reem = lookup.artists.single()
                assertFalse(reem.artist!!.sharedPage, bio)
                // MusicBrainz has a second Reem: the listeners, tags and similar artists are not cited.
                assertEquals(LastFmMatch.BioVerified, reem.match, bio)
                assertEquals(listOf("Reem"), artists.searches, bio)
                assertTrue(lookup.result.evidence.isNotEmpty(), bio)
                assertTrue(lookup.result.evidence.all { "biography" in it.text }, bio)
                val aggregates = listOf("Last.fm counts ", "Last.fm listeners tag ", "Last.fm lists artists ")
                assertEquals(aggregates.size, lookup.result.unverified.size, bio)
                assertTrue(
                    lookup.result.unverified.zip(aggregates).all { (line, start) ->
                        line.text.startsWith(start)
                    },
                    bio,
                )
                assertFalse(lookup.result.provisional, bio)
            }
        }

    @Test
    fun `a namesake counts by name key, a longer name does not, and the artist must be found`() =
        runTest {
            val track = fixtureTrack(1)
            val cases =
                listOf(
                    listOf(GORDON, MbArtist(OTHER, "Gordon Lightfoot Tribute Band")) to LastFmMatch.Verified,
                    listOf(MbArtist(OTHER, "Gordon Lightfoot Jr."), GORDON) to LastFmMatch.Verified,
                    listOf(GORDON, MbArtist(OTHER, "GORDON LIGHTFOOT")) to LastFmMatch.BioVerified,
                    listOf(MbArtist(OTHER, "Gördon Light-foot"), GORDON) to LastFmMatch.BioVerified,
                    // The search proves nothing about an artist it does not find.
                    emptyList<MbArtist>() to LastFmMatch.BioVerified,
                    listOf(MbArtist(OTHER, "Gordon Lightfoot Jr.")) to LastFmMatch.BioVerified,
                )
            for ((found, expected) in cases) {
                val artists = ArtistSearchStub(mapOf("Gordon Lightfoot" to found))
                val lookup = adapter(artists = artists).lookupArtists(track, gordonResolved())
                assertEquals(expected, lookup.artists.single().match, "$found")
                assertFalse(lookup.result.provisional, "$found")
            }
        }

    @Test
    fun `with a namesake, the bio sentences are cited and the listeners, tags and similar artists only shown`() =
        runTest {
            val artists =
                ArtistSearchStub(
                    mapOf(
                        "Gordon Lightfoot" to listOf(GORDON, MbArtist(OTHER, "Gordon Lightfoot")),
                    ),
                )
            val lookup = adapter(artists = artists).lookupArtists(fixtureTrack(1), gordonResolved())

            assertEquals(LastFmMatch.BioVerified, lookup.artists.single().match)
            assertEquals(Outcome.ArtistArticle, lookup.result.outcome)
            assertEquals(3, lookup.result.evidence.size)
            assertTrue(
                lookup.result.evidence.all { it.text.startsWith("The Last.fm biography of Gordon Lightfoot says:") },
            )
            assertEquals(
                listOf(
                    "Last.fm counts 683,942 listeners for Gordon Lightfoot.",
                    "Last.fm listeners tag Gordon Lightfoot as: folk, singer-songwriter, 70s, canadian, acoustic.",
                ),
                lookup.result.unverified
                    .take(2)
                    .map { it.text },
            )
            assertEquals(3, lookup.result.unverified.size)
            // Genre labels come as for any match that may be shown.
            assertTrue(
                lookup.artists
                    .single()
                    .genres
                    .isNotEmpty(),
            )
        }

    @Test
    fun `one search page decides, and owns the name only when it holds every match`() =
        runTest {
            val longer = (1..LONGER).map { MbArtist("other-$it", "Gordon Lightfoot Cover Band $it") }
            val cases =
                listOf(
                    // Every match on the page, no namesake.
                    Triple(listOf(GORDON) + longer.take(99), null, LastFmMatch.Verified),
                    // More matches than the page holds: the bio only, though no namesake was seen.
                    Triple(listOf(GORDON) + longer.take(99), 101, LastFmMatch.BioVerified),
                    Triple(listOf(GORDON) + longer, 2150, LastFmMatch.BioVerified),
                    // A namesake on the page decides, complete or not ("Them", "Fred").
                    Triple(
                        longer.take(30) + MbArtist(OTHER, "Gordon Lightfoot") + GORDON,
                        2150,
                        LastFmMatch.BioVerified,
                    ),
                )
            for ((found, total, expected) in cases) {
                val artists = ArtistSearchStub(mapOf("Gordon Lightfoot" to found), totals = mapOfNotNull(total))
                val lookup = adapter(artists = artists).lookupArtists(fixtureTrack(1), gordonResolved())
                assertEquals(expected, lookup.artists.single().match, "$total, ${found.size}")
                assertEquals(listOf("Gordon Lightfoot"), artists.searches, "$total, ${found.size}")
                assertFalse(lookup.result.provisional, "$total, ${found.size}")
            }
        }

    @Test
    fun `a count below the artists listed fails the search - unverified and provisional`() =
        runTest {
            // `MusicBrainzClient.searchArtists` already fails such an answer; this is the backstop.
            val longer = (1..LONGER).map { MbArtist("other-$it", "Gordon Lightfoot Cover Band $it") }
            val cases =
                listOf(
                    listOf(GORDON) + longer.take(99) to 50,
                    listOf(GORDON) to 0,
                    // A namesake on the page does not decide an answer that is wrong.
                    listOf(MbArtist(OTHER, "Gordon Lightfoot"), GORDON) to 1,
                )
            for ((found, total) in cases) {
                val artists = ArtistSearchStub(mapOf("Gordon Lightfoot" to found), totals = mapOfNotNull(total))
                val lookup = adapter(artists = artists).lookupArtists(fixtureTrack(1), gordonResolved())
                assertEquals(LastFmMatch.Unverified, lookup.artists.single().match, "$total, ${found.size}")
                assertTrue(lookup.artists.single().provisional, "$total, ${found.size}")
                assertTrue(lookup.result.provisional, "$total, ${found.size}")
            }
        }

    @Test
    fun `MusicBrainz ids match whatever their case`() =
        runTest {
            val track = fixtureTrack(1)
            val upper = GORDON_LIGHTFOOT.uppercase()
            // The resolved id in upper case: Last.fm's lower-case id and the search's are the same artist.
            val upperResolved = adapter().lookupArtists(track, resolved("Gordon Lightfoot" to upper))
            assertEquals(LastFmMatch.Verified, upperResolved.artists.single().match)
            // The search's id in upper case is no namesake.
            val upperSearch = ArtistSearchStub(mapOf("Gordon Lightfoot" to listOf(MbArtist(upper, "Gordon Lightfoot"))))
            assertEquals(
                LastFmMatch.Verified,
                adapter(artists = upperSearch)
                    .lookupArtists(track, gordonResolved())
                    .artists
                    .single()
                    .match,
            )
        }

    @Test
    fun `a name with no letters or digits proves nothing, and is not searched - the bio only`() =
        runTest {
            val body =
                Fixtures
                    .text("http/lastfm/artist.getinfo/gordon-lightfoot.json")
                    .replaceFirst("\"name\": \"Gordon Lightfoot\"", "\"name\": \"!!!\"")
            val net =
                LastFmStubClient(override = { request ->
                    LastFmStubClient.ok(body).takeIf { request.query["method"] == "artist.getinfo" }
                })
            val artists = ArtistSearchStub()
            val lookup = adapter(net, artists).lookupArtists(fixtureTrack(1), gordonResolved())
            assertEquals(
                "!!!",
                lookup.artists
                    .single()
                    .artist
                    ?.name,
            )
            assertEquals(LastFmMatch.BioVerified, lookup.artists.single().match)
            assertTrue(artists.searches.isEmpty())
        }

    @Test
    fun `a MusicBrainz search that fails leaves the match unverified and the result provisional`() =
        runTest {
            for (failure in listOf(Lookup.Failed("Offline"), Lookup.NotFound)) {
                val artists = ArtistSearchStub(failure = failure)
                val lookup = adapter(artists = artists).lookupArtists(fixtureTrack(1), gordonResolved())
                assertEquals(LastFmMatch.Unverified, lookup.artists.single().match, "$failure")
                assertTrue(lookup.artists.single().provisional, "$failure")
                assertTrue(lookup.result.provisional, "$failure")
                assertTrue(lookup.result.evidence.isEmpty(), "$failure")
                assertEquals(6, lookup.result.unverified.size, "$failure")
            }
        }

    @Test
    fun `the search's answers are kept across tracks, whatever they prove, its failures are not`() =
        runTest {
            val cases =
                listOf(
                    listOf(GORDON) to LastFmMatch.Verified,
                    listOf(GORDON, MbArtist(OTHER, "Gordon Lightfoot")) to LastFmMatch.BioVerified,
                    emptyList<MbArtist>() to LastFmMatch.BioVerified,
                )
            for ((found, expected) in cases) {
                val artists = ArtistSearchStub(mapOf("Gordon Lightfoot" to found))
                val adapter = adapter(artists = artists)
                repeat(2) {
                    val lookup = adapter.lookupArtists(fixtureTrack(1), gordonResolved())
                    assertEquals(expected, lookup.artists.single().match, "$found")
                }
                assertEquals(listOf("Gordon Lightfoot"), artists.searches, "$found")
            }

            val failing = ArtistSearchStub(failure = Lookup.Failed("Offline"))
            val again = adapter(artists = failing)
            repeat(2) { again.lookup(fixtureTrack(1), gordonResolved()) }
            assertEquals(listOf("Gordon Lightfoot", "Gordon Lightfoot"), failing.searches)
        }

    @Test
    fun `answers are kept by the name as sent, so another spelling of the same key is searched again`() =
        runTest {
            val hyphenated =
                Fixtures
                    .text("http/lastfm/artist.getinfo/gordon-lightfoot.json")
                    .replaceFirst("\"name\": \"Gordon Lightfoot\"", "\"name\": \"Gordon-Lightfoot\"")
            var spell = false
            val net =
                LastFmStubClient(override = { request ->
                    LastFmStubClient.ok(hyphenated).takeIf { spell && request.query["method"] == "artist.getinfo" }
                })
            val artists =
                ArtistSearchStub(
                    DEFAULT_ARTISTS + ("Gordon-Lightfoot" to listOf(MbArtist(GORDON_LIGHTFOOT, "Gordon-Lightfoot"))),
                )
            val adapter = adapter(net, artists)
            adapter.lookup(fixtureTrack(1), gordonResolved())
            adapter.lookup(fixtureTrack(1), gordonResolved())
            spell = true
            adapter.lookup(fixtureTrack(1), gordonResolved())
            assertEquals(listOf("Gordon Lightfoot", "Gordon-Lightfoot"), artists.searches)
        }

    @Test
    fun `one artist's failed search makes the whole result provisional, the other's answer is kept`() =
        runTest {
            val n = RegressionCase.MULTIPLE_ARTISTS.tracks.single()
            val artists = ArtistSearchStub(failing = mapOf("Tony K" to Lookup.Failed("RateLimited")))
            val adapter = adapter(artists = artists)
            repeat(2) {
                val lookup = adapter.lookupArtists(fixtureTrack(n), resolved("PeleBoy" to PELEBOY, "Tony K" to TONY_K))
                assertEquals(listOf(LastFmMatch.Verified, LastFmMatch.Unverified), lookup.artists.map { it.match })
                assertEquals(listOf(false, true), lookup.artists.map { it.provisional })
                assertTrue(lookup.result.provisional)
            }
            assertEquals(listOf("PeleBoy", "Tony K", "Tony K"), artists.searches)
        }

    @Test
    fun `the cache drops the least recently used entry beyond its capacity`() =
        runTest {
            val lru = Lru<String, Int>(2)
            lru.put("a", 1)
            lru.put("b", 2)
            assertEquals(1, lru.get("a"))
            lru.put("c", 3)
            assertEquals(null, lru.get("b"))
            assertEquals(1, lru.get("a"))
            assertEquals(3, lru.get("c"))
            lru.put("a", 4)
            lru.put("d", 5)
            assertEquals(null, lru.get("c"))
            assertEquals(4, lru.get("a"))
        }

    @Test
    fun `MusicBrainz is searched only for a page with the resolved id, under the page's name`() =
        runTest {
            val corrected = ArtistSearchStub()
            val willow = normalizer.normalize("willow", "Jessiah")
            adapter(artists = corrected).lookup(willow, resolved("Jessiah" to JESAIAH))
            assertEquals(listOf("Jesaiah"), corrected.searches)

            for ((track, resolution) in listOf(
                fixtureTrack(1) to none,
                fixtureTrack(1) to resolved("Gordon Lightfoot" to null),
                normalizer.normalize("willow", "Jessiah") to resolved("Jessiah" to JESSIAH),
                // The bio already says the page is shared.
                normalizer.normalize("Bad Energy", "Reem") to resolved("Reem" to REEM),
            )) {
                val artists = ArtistSearchStub()
                adapter(artists = artists).lookup(track, resolution)
                assertTrue(artists.searches.isEmpty(), "${track.artists}")
            }
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
    fun `the adapter's version and the sources it consults`() {
        // Raised when the verification became structural (#75): results cached under 1 are missed.
        assertEquals(2, adapter().extractionVersion)
        assertEquals(setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ), adapter().consults)
    }

    @Test
    fun `listener counts are grouped by thousands`() {
        assertEquals(
            listOf("0", "2", "999", "1,000", "5,325", "683,942", "1,840,320"),
            listOf(0L, 2L, 999L, 1000L, 5325L, 683942L, 1840320L).map(LastFmAdapter::grouped),
        )
    }

    /**
     * MusicBrainz's artist search, answered from [artists] by the name asked, its first 100; the
     * total is the list's size unless [totals] gives another: a larger one, a search the page does
     * not hold whole, or a smaller one, a wrong count. [failure], when given, answers every search,
     * and [failing] those for its names. Every name asked is kept in [searches]. By default each
     * stubbed Last.fm artist is the only one of its name, except Reem, whose name two artists have.
     * Resolution's calls are not expected.
     */
    private class ArtistSearchStub(
        private val artists: Map<String, List<MbArtist>> = DEFAULT_ARTISTS,
        private val totals: Map<String, Int> = emptyMap(),
        private val failure: Lookup<MbArtistSearch>? = null,
        private val failing: Map<String, Lookup<MbArtistSearch>> = emptyMap(),
    ) : MusicBrainzLookup {
        val searches = mutableListOf<String>()

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> {
            searches += name
            (failure ?: failing[name])?.let { return it }
            val all = checkNotNull(artists[name]) { "no artist search stub for $name" }
            return Lookup.Found(MbArtistSearch(totals[name] ?: all.size, all.take(PAGE)))
        }

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> = error("unexpected recording search")

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = error("unexpected recording")

        override suspend fun work(mbid: String): Lookup<MbWork> = error("unexpected work")
    }

    private companion object {
        const val KEY = "lastfm-test-key-0123456789"
        const val GORDON_LIGHTFOOT = "9d97b077-b28d-4ba8-a3d9-c71926e3b2b6"
        const val JESAIAH = "b26aefc5-3332-462b-b770-1baf152e3a08"
        const val JESSIAH = "ac5019aa-c554-4ca0-b29b-ef4e5fe31b2d"
        const val PELEBOY = "00000000-0000-4000-8000-000000000014"
        const val TONY_K = "00000000-0000-4000-8000-000000000141"
        const val REEM = "00000000-0000-4000-8000-000000000012"
        const val OTHER_TONY_K = "00000000-0000-4000-8000-0000000001ff"
        const val TONY_K_REASON = "Last.fm's Tony K is a different MusicBrainz artist from Tony K"
        const val OTHER = "00000000-0000-4000-8000-0000000002ff"
        const val REEM_BIO =
            "There are several artists named Reem. 1) Reem is a rapper from Denmark with Moroccan roots. " +
                "2) Reem is another artist of the same name."

        const val PAGE = 100

        /** More longer names than a page holds. */
        const val LONGER = 320

        val GORDON = MbArtist(GORDON_LIGHTFOOT, "Gordon Lightfoot")

        val DEFAULT_ARTISTS: Map<String, List<MbArtist>> =
            mapOf(
                "Gordon Lightfoot" to listOf(GORDON),
                "PeleBoy" to listOf(MbArtist(PELEBOY, "PeleBoy")),
                "Tony K" to listOf(MbArtist(TONY_K, "Tony K")),
                "Jesaiah" to listOf(MbArtist(JESAIAH, "Jesaiah")),
                "Reem" to listOf(MbArtist(REEM, "Reem"), MbArtist(OTHER, "Reem"), MbArtist(OTHER, "Reem Kelani")),
            )

        fun mapOfNotNull(total: Int?): Map<String, Int> = total?.let { mapOf("Gordon Lightfoot" to it) } ?: emptyMap()
    }
}
