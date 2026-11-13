package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What only lists a song, and what describes it (#958): decided by the app, with no paid call. */
class SongListingsTest {
    private val at = Instant.parse("2026-11-06T00:00:00Z")

    private fun line(
        source: SourceId,
        text: String,
        url: String? = null,
        scope: Scope = Scope.WEB,
        aggregate: Boolean = false,
    ): EvidenceLine =
        EvidenceSet
            .of(listOf(EvidenceDraft(source, scope, text, url, at, aggregate = aggregate)), JvmHasher)
            .lines
            .single()

    private fun web(
        text: String,
        url: String = "https://blog.example/post",
    ) = line(SourceId.WEB, text, url)

    @Test
    fun `structured data is a listing, whatever it says`() {
        assertTrue(
            SongListings.isListing(line(SourceId.MUSICBRAINZ, "It has a warm horn section.", scope = Scope.SONG)),
        )
        assertTrue(SongListings.isListing(line(SourceId.WIKIDATA, "It was produced by A.", scope = Scope.SONG)))
        assertTrue(SongListings.isListing(line(SourceId.PLAYER, "On the album Getaway.", scope = Scope.SONG)))
    }

    @Test
    fun `Wikipedia's and Last-fm's text describe the song, but not Last-fm's counts`() {
        assertFalse(SongListings.isListing(line(SourceId.WIKIPEDIA, "It was released on 5 April.", scope = Scope.SONG)))
        assertFalse(SongListings.isListing(line(SourceId.LASTFM, "A slow ballad.", scope = Scope.SONG)))
        assertTrue(
            SongListings.isListing(line(SourceId.LASTFM, "100 listeners.", scope = Scope.SONG, aggregate = true)),
        )
    }

    @Test
    fun `a page on a streaming, shop or chart site is a listing, its subdomains and country domains too`() {
        listOf(
            "https://open.spotify.com/track/1",
            "https://music.apple.com/us/album/1",
            "https://batov.bandcamp.com/track/phil",
            "https://www.amazon.de/dp/1",
            "https://www.officialcharts.com/songs/x",
        ).forEach { assertTrue(SongListings.isListing(web("A warm and slow groove.", it)), it) }
    }

    @Test
    fun `a host that only holds a listing's name in its words is not one`() {
        assertFalse(SongListings.isListing(web("A warm and slow groove.", "https://amazonian-records.example/x")))
        assertFalse(SongListings.isListing(web("A warm and slow groove.", "https://notspotify.com/x")))
    }

    @Test
    fun `the host is the one a browser opens, not one later in the address`() {
        assertFalse(SongListings.isListingHost("https://a.example#@open.spotify.com/x"))
        assertFalse(SongListings.isListingHost("https://a.example\\@open.spotify.com/x"))
        assertTrue(SongListings.isListingHost("https://a.example@open.spotify.com/x"))
    }

    @Test
    fun `a page that reads like a listing is one, on any host`() {
        listOf(
            "Phil's First Tear was released on April 5, 2024.",
            "Label: Batov Records. Release date: March 13, 2024.",
            "Listen on your favourite service.",
            "Tracklist of the album Getaway.",
            "1. Getaway 3:45 2. Phil's First Tear 4:10",
            "1. Getaway 2. Phil's First Tear 3. Sleep",
        ).forEach { assertTrue(SongListings.isListing(web(it)), it) }
    }

    @Test
    fun `a listing's phrase in mixed prose does not make it a listing`() {
        listOf(
            "Released on Friday, the song pairs a loping bass line with jagged horns.",
            "Release date: March 13, 2024. The song pairs a loping bass line with jagged horns.",
            "Listen to how the horns answer the bass in its second half.",
        ).forEach { assertFalse(SongListings.isListing(web(it)), it) }
    }

    @Test
    fun `metadata alone stays a listing, however long, and a list of tracks whatever surrounds it`() {
        listOf(
            "Phil's First Tear is a song by Kolonel Djafaar, released on April 5, 2024, on the album Getaway.",
            "Label: Batov Records. Release date: March 13, 2024. Recorded in Antwerp, Belgium.",
            "Listen on Spotify, Apple Music and Deezer.",
            "A warm record with a fine loping groove: 1. Getaway 3:45 2. Phil's First Tear 4:10",
        ).forEach { assertTrue(SongListings.isListing(web(it)), it) }
    }

    @Test
    fun `moments in prose are not a list of tracks, and lengths with entries or little else are`() {
        assertFalse(SongListings.isListing(web("At 0:45 the horns enter, and at 2:10 the bass takes over.")))
        listOf(
            "Getaway 3:45 Phil's First Tear 4:10",
            "1. Getaway 3:45 2. Phil's First Tear 4:10",
        ).forEach { assertTrue(SongListings.isListing(web(it)), it) }
    }

    @Test
    fun `a web line with no listing host and no listing shape describes the song`() {
        assertFalse(
            SongListings.isListing(web("An instrumental with a heavy Ethio-jazz horn line, cut live in Antwerp.")),
        )
        // Words of another language count as a description, unless its host says otherwise.
        assertFalse(SongListings.isListing(web("เพลงนี้พูดถึงรอยยิ้มอันตราย")))
    }

    @Test
    fun `only the song's own lines count, and with none the song has no description`() {
        val artist = line(SourceId.WIKIPEDIA, "An Afrobeat band from Antwerp.", scope = Scope.ARTIST)
        val release = line(SourceId.PLAYER, "On Getaway.", scope = Scope.RELEASE)
        val listed = web("Listen on Spotify.")
        assertTrue(SongListings.listingOnly(emptyList()))
        assertTrue(SongListings.listingOnly(listOf(artist, release)))
        assertTrue(SongListings.listingOnly(listOf(listed, artist)))
        assertFalse(SongListings.listingOnly(listOf(listed, web("A slow groove."))))
    }
}
