package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
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
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Last.fm's answers and the verdicts of its check, kept per artist in the artist cache (#238,
 * #114 item 4), against the hand-written stubs in `fixtures/http/lastfm/`.
 */
class LastFmArtistCacheTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private var now = Instant.parse("2026-10-21T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun adapter(
        net: NetClient = LastFmStubClient(),
        search: GordonSearch = GordonSearch(),
        kept: ArtistResults = ArtistResults.None,
        genres: Boolean = true,
    ) = LastFmAdapter(net, search, Secret(KEY), vocabulary, JvmAccentFolder, clock, artists = kept, genres = genres)

    private val sundown = normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val carefree = normalizer.normalize("Carefree Highway", "Gordon Lightfoot")

    private fun resolved(mbid: String = GORDON_LIGHTFOOT) =
        CompletableDeferred(Resolution(artists = listOf(ResolvedArtist("Gordon Lightfoot", mbid))))

    @Test
    fun `a second track by the same artist asks Last fm and MusicBrainz nothing while the artist cache has it`() =
        runTest {
            val kept = MemoryArtistResults()
            val first = LastFmStubClient()
            val firstSearch = GordonSearch()
            val before = adapter(first, firstSearch, kept).lookupArtists(sundown, resolved())
            assertEquals(2, first.requests.size)
            assertEquals(1, firstSearch.searches)
            assertEquals(setOf("Gordon Lightfoot"), kept.keys(LastFmAdapter.ARTIST_PART))

            // A new adapter, as after a restart or a new key: nothing in memory, the cache kept.
            val second = LastFmStubClient()
            val secondSearch = GordonSearch()
            val again = adapter(second, secondSearch, kept).lookupArtists(carefree, resolved())

            assertEquals(emptyList(), second.requests, "no artist.getInfo or artist.getTopTags")
            assertEquals(0, secondSearch.searches, "the verdict is kept too (#114)")
            assertEquals(LastFmMatch.Verified, again.artists.single().match)
            assertEquals(before.result.evidence, again.result.evidence)
            assertEquals(before.result.unverified, again.result.unverified)
            assertEquals(before.result.genres, again.result.genres)
        }

    @Test
    fun `a kept answer is judged against each track's own resolution`() =
        runTest {
            val kept = MemoryArtistResults()
            adapter(kept = kept).lookup(sundown, resolved())

            val net = LastFmStubClient()
            val other = adapter(net, kept = kept).lookupArtists(carefree, resolved(OTHER))

            assertEquals(emptyList(), net.requests)
            assertIs<LastFmMatch.Rejected>(other.artists.single().match)
            assertTrue(other.result.evidence.isEmpty())
        }

    @Test
    fun `an artist Last fm does not know is kept, a failure or another error is not`() =
        runTest {
            val kept = MemoryArtistResults()
            val nobody = normalizer.normalize("Song", "Nobody Here")
            val none = CompletableDeferred(Resolution.NONE)
            adapter(kept = kept).lookup(nobody, none)
            val net = LastFmStubClient()
            val again = adapter(net, kept = kept).lookupArtists(nobody, none)
            assertEquals(LastFmMatch.NotFound, again.artists.single().match)
            assertEquals(emptyList(), net.requests)

            val failures =
                listOf(
                    NetResult.Failure(NetError.Offline("UnknownHostException")),
                    LastFmStubClient.ok(Fixtures.text("http/lastfm/invalid-key.json")),
                )
            for (failure in failures) {
                val failing = MemoryArtistResults()
                adapter(LastFmStubClient(override = { failure }), kept = failing).lookup(sundown, none)
                assertEquals(emptySet(), failing.keys(LastFmAdapter.ARTIST_PART), "$failure")
            }
        }

    @Test
    fun `an artist whose top tags did not arrive is not kept, so a later lookup gets them`() =
        runTest {
            val kept = MemoryArtistResults()
            val timeout = NetResult.Failure(NetError.Offline("timeout"))
            val tagsFail = { request: NetRequest -> timeout.takeIf { request.query["method"] == "artist.gettoptags" } }
            adapter(LastFmStubClient(override = tagsFail), kept = kept).lookup(sundown, resolved())

            assertEquals(emptySet(), kept.keys(LastFmAdapter.ARTIST_PART))
        }

    @Test
    fun `with genres off no top tags are asked for and no labels made, and results are kept apart`() =
        runTest {
            val net = LastFmStubClient()
            val off = adapter(net, genres = false)
            val lookup = off.lookupArtists(sundown, resolved())

            assertEquals(listOf("artist.getinfo"), net.requests.map { it.query["method"] })
            assertEquals(LastFmMatch.Verified, lookup.artists.single().match)
            assertTrue(lookup.result.genres.isEmpty())
            assertTrue(lookup.result.evidence.isNotEmpty(), "the evidence is as before")
            assertNotEquals(adapter().extractionVersion, off.extractionVersion)
        }

    @Test
    fun `an artist kept without its top tags is asked for again once genres are on`() =
        runTest {
            val kept = MemoryArtistResults()
            adapter(kept = kept, genres = false).lookup(sundown, resolved())
            assertEquals(setOf("Gordon Lightfoot"), kept.keys(LastFmAdapter.ARTIST_PART))

            // While genres stay off, the kept answer serves.
            val stillOff = LastFmStubClient()
            adapter(stillOff, kept = kept, genres = false).lookup(carefree, resolved())
            assertEquals(emptyList(), stillOff.requests)

            // Once on, it is asked for whole, top tags included, and kept so.
            val on = LastFmStubClient()
            val again = adapter(on, kept = kept).lookupArtists(carefree, resolved())
            assertEquals(setOf("artist.getinfo", "artist.gettoptags"), on.requests.map { it.query["method"] }.toSet())
            assertTrue(again.result.genres.isNotEmpty())
            val later = LastFmStubClient()
            adapter(later, kept = kept).lookup(sundown, resolved())
            assertEquals(emptyList(), later.requests)
        }

    @Test
    fun `the cache keeps the bio's quotable sentences, never its summary`() =
        runTest {
            val kept = MemoryArtistResults()
            adapter(kept = kept).lookup(sundown, resolved())

            val value = checkNotNull(kept.get(LastFmAdapter.ARTIST_PART, "Gordon Lightfoot")).value
            assertFalse("summary" in value, value)
            assertFalse("Read more on Last.fm" in value, value)
        }

    @Test
    fun `a failed search's verdict is not kept in the artist cache`() =
        runTest {
            val kept = MemoryArtistResults()
            adapter(search = GordonSearch(fails = true), kept = kept).lookup(sundown, resolved())
            assertEquals(emptySet(), kept.keys(ArtistCheck.OWNERSHIP_PART))

            adapter(kept = kept).lookup(sundown, resolved())
            assertEquals(setOf("$GORDON_LIGHTFOOT gordon lightfoot"), kept.keys(ArtistCheck.OWNERSHIP_PART))
        }

    @Test
    fun `a kept answer that cannot be read is asked for again`() =
        runTest {
            val kept = MemoryArtistResults()
            kept.keep(LastFmAdapter.ARTIST_PART, "Gordon Lightfoot", "not json")
            val net = LastFmStubClient()

            val lookup = adapter(net, kept = kept).lookupArtists(sundown, resolved())

            assertEquals(2, net.requests.size)
            assertEquals(LastFmMatch.Verified, lookup.artists.single().match)
        }

    @Test
    fun `names that fold alike are separate queries, so they do not share an entry`() =
        runTest {
            val kept = MemoryArtistResults()
            val files = LastFmStubClient.DEFAULT_FILES + ("Gördon Lightfoot" to "not-found")
            adapter(LastFmStubClient(files), kept = kept).lookup(sundown, resolved())

            val net = LastFmStubClient(files)
            val accented = normalizer.normalize("Sundown", "Gördon Lightfoot")
            val lookup = adapter(net, kept = kept).lookupArtists(accented, CompletableDeferred(Resolution.NONE))

            assertEquals(2, net.requests.size, "the other spelling is asked for")
            assertEquals(LastFmMatch.NotFound, lookup.artists.single().match)
            assertEquals(setOf("Gordon Lightfoot", "Gördon Lightfoot"), kept.keys(LastFmAdapter.ARTIST_PART))
        }

    @Test
    fun `a result built from kept answers says when the first of them stops being fresh`() =
        runTest {
            val kept = MemoryArtistResults(clock, 7.days)
            val fresh = adapter(kept = kept).lookup(sundown, resolved())
            assertEquals(null, fresh.freshUntil, "nothing kept was reused")

            now += 6.days
            val again = adapter(kept = kept).lookup(carefree, resolved())
            assertEquals(Instant.parse("2026-10-28T12:00:00Z"), again.freshUntil)
        }

    @Test
    fun `a kept verdict past its lifetime is searched again, and not remembered in memory meanwhile`() =
        runTest {
            val kept = MemoryArtistResults(clock, 30.days)
            val search = GordonSearch()
            val adapter = adapter(search = search, kept = kept)
            adapter.lookup(sundown, resolved())
            adapter.lookup(carefree, resolved())
            assertEquals(1, search.searches)

            now += 30.days
            adapter.lookup(sundown, resolved())
            assertEquals(2, search.searches, "the same adapter asks again once the kept verdict expires")
        }

    @Test
    fun `with a cache that keeps nothing, wrapped as the CLI wraps it, two tracks send one search`() =
        runTest {
            // The CLI's counting wrapper around no cache (`record`, `--cache none`) keeps nothing too.
            val wrapped = object : ArtistResults by ArtistResults.None {}
            val search = GordonSearch()
            val adapter = adapter(search = search, kept = wrapped)
            adapter.lookup(sundown, resolved())
            adapter.lookup(carefree, resolved())

            assertFalse(wrapped.persists)
            assertEquals(1, search.searches, "the verdict is kept in memory instead")
        }

    @Test
    fun `the kept parts have the answers' version, the results the adapter's extraction version`() {
        assertEquals(LastFmAdapter.ANSWERS_VERSION, LastFmAdapter.ARTIST_PART.extraction)
        assertEquals(LastFmAdapter.ANSWERS_VERSION, ArtistCheck.OWNERSHIP_PART.extraction)
        assertEquals(LastFmAdapter.EXTRACTION_VERSION, adapter().extractionVersion)
    }

    /**
     * MusicBrainz's artist search, which knows one Gordon Lightfoot, or [fails]; counts the
     * [searches]. Resolution's calls are not expected.
     */
    class GordonSearch(
        private val fails: Boolean = false,
    ) : MusicBrainzLookup {
        var searches = 0
            private set

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> {
            searches++
            return if (fails) {
                Lookup.Failed("Offline")
            } else {
                Lookup.Found(MbArtistSearch(1, listOf(MbArtist(GORDON_LIGHTFOOT, name))))
            }
        }

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> = error("unexpected recording search")

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = error("unexpected recording")

        override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = error("unexpected releases")

        override suspend fun work(mbid: String): Lookup<MbWork> = error("unexpected work")
    }

    private companion object {
        const val KEY = "lastfm-test-key-0123456789"
        const val GORDON_LIGHTFOOT = "9d97b077-b28d-4ba8-a3d9-c71926e3b2b6"
        const val OTHER = "00000000-0000-4000-8000-0000000002ff"
    }
}
