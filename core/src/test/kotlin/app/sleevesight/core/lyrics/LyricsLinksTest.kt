package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Home's lyrics links (#728): what the *Lyrics* panel holds for each MusicBrainz answer, the
 * whitelist and its order, the one page per site, the slugs, and the Genius search.
 */
class LyricsLinksTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val rafferty = normalizer.normalize("Right Down the Line", "Gerry Rafferty")

    private fun links(
        musicBrainz: MusicBrainzAnswer,
        search: Boolean = true,
        track: NormalizedTrack = rafferty,
    ) = lyricsLinks(musicBrainz, track, search, JvmAccentFolder)

    private fun found(
        links: List<String> = emptyList(),
        languages: List<String> = listOf("eng"),
        instrumental: Boolean = false,
    ) = MusicBrainzAnswer.Found(WorkLyrics(links, languages, instrumental))

    private val raffertySearch =
        LyricsLink(LyricsSite.GENIUS, "https://genius.com/search?q=Right%20Down%20the%20Line%20Gerry%20Rafferty")

    @Test
    fun `while MusicBrainz has not answered, nothing yet, not even the search`() {
        assertEquals(LyricsLinks.Looking, links(MusicBrainzAnswer.Looking))
        assertEquals(LyricsLinks.Looking, links(MusicBrainzAnswer.Looking, search = false))
    }

    @Test
    fun `the two Genius pages of Right Down the Line give the playing artist's`() {
        val raitt = "https://genius.com/Bonnie-raitt-right-down-the-line-lyrics"
        val gerry = "https://genius.com/Gerry-rafferty-right-down-the-line-lyrics"
        assertEquals(
            LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, gerry))),
            links(found(listOf(raitt, gerry))),
        )
        // Bonnie Raitt playing: hers.
        val raittTrack = normalizer.normalize("Right Down the Line", "Bonnie Raitt")
        assertEquals(
            LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, raitt))),
            links(found(listOf(gerry, raitt)), track = raittTrack),
        )
    }

    @Test
    fun `a page naming none of the artists, the first of the site`() {
        // A cover: the original's page, which has the same lyrics.
        val siouxsie = normalizer.normalize("The Passenger", "Siouxsie and the Banshees")
        val iggy = "https://genius.com/Iggy-pop-the-passenger-lyrics"
        val other = "https://genius.com/Someone-else-the-passenger-lyrics"
        assertEquals(
            LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, iggy))),
            links(found(listOf(iggy, other)), track = siouxsie),
        )
    }

    @Test
    fun `one page per site, Genius first, then Musixmatch, then the whitelist's order`() {
        val urls =
            listOf(
                "https://hoick.jp/mdb/detail/1",
                "https://en.wikisource.org/wiki/Song",
                "https://www.musixmatch.com/lyrics/Anne-Murray/Snowbird",
                "https://artist.bandcamp.com/track/song",
                "https://genius.com/Anne-murray-snowbird-lyrics",
                "https://www.musixmatch.com/lyrics/Other/Snowbird",
            )
        val result = links(found(urls), track = normalizer.normalize("Snowbird", "Anne Murray"))
        assertEquals(
            listOf(
                LyricsLink(LyricsSite.GENIUS, "https://genius.com/Anne-murray-snowbird-lyrics"),
                LyricsLink(LyricsSite.MUSIXMATCH, "https://www.musixmatch.com/lyrics/Anne-Murray/Snowbird"),
                LyricsLink(LyricsSite.BANDCAMP, "https://artist.bandcamp.com/track/song"),
                LyricsLink(LyricsSite.WIKISOURCE, "https://en.wikisource.org/wiki/Song"),
                LyricsLink(LyricsSite.HOICK, "https://hoick.jp/mdb/detail/1"),
            ),
            (result as LyricsLinks.Links).links,
        )
    }

    @Test
    fun `the sites are named and ordered as the pick has them`() {
        assertEquals(
            listOf(
                "Genius" to "genius.com",
                "Musixmatch" to "musixmatch.com",
                "Bandcamp" to "bandcamp.com",
                "LiederNet" to "lieder.net",
                "Muzikum" to "muzikum.eu",
                "Wikisource" to "wikisource.org",
                "Project Gutenberg" to "gutenberg.org",
                "Project Runeberg" to "runeberg.org",
                "Online-Bijbel.nl" to "online-bijbel.nl",
                "Uta-Net" to "uta-net.com",
                "J-Lyric.net" to "j-lyric.net",
                "Utaten" to "utaten.com",
                "Kashinavi" to "kashinavi.com",
                "PetitLyrics" to "petitlyrics.com",
                "JOYSOUND" to "joysound.com",
                "Hoick" to "hoick.jp",
            ),
            LyricsSite.entries.map { it.siteName to it.domain },
        )
    }

    @Test
    fun `a host is whitelisted as the site's domain or a subdomain of it, never a look-alike`() {
        assertEquals(LyricsSite.GENIUS, LyricsSite.of("genius.com"))
        assertEquals(LyricsSite.BANDCAMP, LyricsSite.of("artist.bandcamp.com"))
        assertEquals(LyricsSite.WIKISOURCE, LyricsSite.of("en.wikisource.org"))
        assertNull(LyricsSite.of("notgenius.com"))
        assertNull(LyricsSite.of("genius.com.evil.example"))
        assertNull(LyricsSite.of("genius.co"))
        // And through a whole link, as MusicBrainz could give it.
        for (url in listOf(
            "https://notgenius.com/Gerry-rafferty-right-down-the-line-lyrics",
            "https://genius.com.evil.example/Gerry-rafferty-right-down-the-line-lyrics",
            "https://genius.com@evil.example/Gerry-rafferty-right-down-the-line-lyrics",
            "https://evil.example/genius.com/Gerry-rafferty-right-down-the-line-lyrics",
            "https://evil.example\\@genius.com/",
            "https://genius.com /x",
            "https:///genius.com",
        )) {
            assertEquals(LyricsLinks.NoLink(NoLinkReason.NoLyricsPage, raffertySearch), links(found(listOf(url))), url)
        }
        // A host in capitals, with a port or the root's dot, is still the site's.
        for (url in listOf("https://GENIUS.com/x", "https://genius.com:443/x", "https://genius.com./x")) {
            assertEquals(LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, url))), links(found(listOf(url))), url)
        }
    }

    @Test
    fun `only https links`() {
        val result =
            links(found(listOf("http://genius.com/Gerry-rafferty-right-down-the-line-lyrics", "ftp://genius.com/x")))
        assertEquals(LyricsLinks.NoLink(NoLinkReason.NoLyricsPage, raffertySearch), result)
        assertEquals(
            LyricsSite.GENIUS to "/x",
            parse("HTTPS://Genius.com/x?y#z")?.let {
                LyricsSite.of(it.first)!! to
                    it.second
            },
        )
    }

    @Test
    fun `an instrumental, by its performance or by its works' languages, has no link and no search`() {
        val page = listOf("https://genius.com/Gerry-rafferty-right-down-the-line-lyrics")
        assertEquals(LyricsLinks.Instrumental, links(found(page, instrumental = true)))
        assertEquals(LyricsLinks.Instrumental, links(found(languages = listOf("zxx"))))
        // Not when one work has words, nor when no language is known.
        assertEquals(
            LyricsLinks.NoLink(NoLinkReason.NoLyricsPage, raffertySearch),
            links(found(languages = listOf("zxx", "eng"))),
        )
        assertEquals(
            LyricsLinks.NoLink(NoLinkReason.NoLyricsPage, raffertySearch),
            links(found(languages = emptyList())),
        )
    }

    @Test
    fun `no link, each reason, with the search while it is on`() {
        val cases =
            listOf(
                MusicBrainzAnswer.Off to NoLinkReason.MusicBrainzOff,
                MusicBrainzAnswer.Unreachable to NoLinkReason.Unreachable,
                MusicBrainzAnswer.NotFound to NoLinkReason.NotFound,
                found() to NoLinkReason.NoLyricsPage,
                // A result kept before #728: no link known, not instrumental.
                MusicBrainzAnswer.Found(null) to NoLinkReason.NoLyricsPage,
            )
        for ((answer, reason) in cases) {
            assertEquals(LyricsLinks.NoLink(reason, raffertySearch), links(answer), "$answer")
            assertEquals(LyricsLinks.NoLink(reason, null), links(answer, search = false), "$answer")
        }
    }

    @Test
    fun `the search is for the title and artist as the sources name them, encoded`() {
        // "2006 Remaster" and a featured artist are not sent; the title as the sources search for it.
        val remaster = normalizer.normalize("Right Down the Line - 2006 Remaster", "Gerry Rafferty")
        assertEquals("Right Down the Line", remaster.title)
        assertEquals(raffertySearch.url, searchUrl(remaster))
        assertEquals(
            "https://genius.com/search?q=Don%E2%80%99t%20Stop%20%26%20Go%3F%20Bj%C3%B6rk",
            searchUrl(normalizer.normalize("Don’t Stop & Go?", "Björk")),
        )
        assertEquals("a-b_c.d~e%2B%2F%3D%23", percentEncoded("a-b_c.d~e+/=#"))
    }

    @Test
    fun `a slug is lowercase, accents folded, apostrophes dropped, other runs one hyphen`() {
        assertEquals("gerry-rafferty", slug("Gerry Rafferty", JvmAccentFolder))
        assertEquals("sinead-oconnor", slug("Sinéad O’Connor", JvmAccentFolder))
        assertEquals("beyonce", slug("Beyoncé!", JvmAccentFolder))
        assertEquals("ac-dc", slug("  AC/DC  ", JvmAccentFolder))
        assertEquals("", slug("!!!", JvmAccentFolder))
    }

    @Test
    fun `a path names an artist whole, from its slug or percent-encoded`() {
        val slugs = listOf("anne-murray")
        assertTrue(namesArtist("/lyrics/Anne-Murray/Snowbird", slugs, JvmAccentFolder))
        assertFalse(namesArtist("/lyrics/Joanne-Murray/Snowbird", slugs, JvmAccentFolder))
        assertTrue(namesArtist("/lyrics/Beyonc%C3%A9/Halo", listOf("beyonce"), JvmAccentFolder))
        assertFalse(namesArtist("/lyrics/Beyonc%ZZ/Halo", listOf("beyonce"), JvmAccentFolder))
        // "&" as Genius writes it: and.
        val simon = normalizer.normalize("The Boxer", "Simon & Garfunkel")
        val page = "https://genius.com/Simon-and-garfunkel-the-boxer-lyrics"
        assertEquals(
            LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, page))),
            links(found(listOf("https://genius.com/Other-the-boxer-lyrics", page)), track = simon),
        )
    }

    @Test
    fun `MusicBrainz's answer is read from its state in the run, as Sources reads it`() {
        fun done(
            outcome: Outcome,
            lyrics: WorkLyrics? = null,
        ) = SourceState.Done(SourceResult(outcome, emptyList(), Duration.ZERO, lyrics = lyrics), cached = false)
        val lyrics = WorkLyrics(listOf("https://genius.com/x"))
        assertEquals(MusicBrainzAnswer.Looking, MusicBrainzAnswer.of(null))
        assertEquals(MusicBrainzAnswer.Looking, MusicBrainzAnswer.of(SourceState.Pending))
        assertEquals(MusicBrainzAnswer.Off, MusicBrainzAnswer.of(SourceState.Pending, enabled = false))
        assertEquals(MusicBrainzAnswer.Off, MusicBrainzAnswer.of(done(Outcome.SourceOff)))
        assertEquals(MusicBrainzAnswer.Unreachable, MusicBrainzAnswer.of(done(Outcome.Unreachable)))
        assertEquals(MusicBrainzAnswer.Unreachable, MusicBrainzAnswer.of(SourceState.Failed("IllegalStateException")))
        assertEquals(MusicBrainzAnswer.NotFound, MusicBrainzAnswer.of(done(Outcome.None)))
        assertEquals(MusicBrainzAnswer.NotFound, MusicBrainzAnswer.of(done(Outcome.Rejected("another artist"))))
        assertEquals(MusicBrainzAnswer.Found(lyrics), MusicBrainzAnswer.of(done(Outcome.SongArticle, lyrics)))
        assertEquals(MusicBrainzAnswer.Found(null), MusicBrainzAnswer.of(done(Outcome.SongArticle)))

        // From the track's state, as Home reads it.
        val state =
            TrackState(
                nowPlaying =
                    NowPlaying(
                        title = "Right Down the Line",
                        artists = "Gerry Rafferty",
                        player = "com.spotify.music",
                    ),
                normalized = rafferty,
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to
                            done(
                                Outcome.SongArticle,
                                lyrics.copy(links = listOf("https://genius.com/Gerry-rafferty-x")),
                            ),
                    ),
            )
        assertEquals(
            LyricsLinks.Links(listOf(LyricsLink(LyricsSite.GENIUS, "https://genius.com/Gerry-rafferty-x"))),
            lyricsLinks(state, search = true, accentFolder = JvmAccentFolder),
        )
        assertEquals(
            LyricsLinks.NoLink(NoLinkReason.MusicBrainzOff, null),
            lyricsLinks(state, search = false, accentFolder = JvmAccentFolder, musicBrainzOn = false),
        )
    }
}
