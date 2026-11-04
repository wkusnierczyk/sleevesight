package app.sleevesight.core.lyrics

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * *Lyrics*' first step of *Ask AI* (#801): a lyrics page among the track's web search results is
 * shown with no AI call, in the search's place, chosen as MusicBrainz's are; none without one, nor
 * from a site of the whitelist that does not exist to host lyrics, nor an artist's or an album's
 * page (#824).
 */
class WebLyricsTest {
    private val at = Instant.parse("2026-11-01T10:00:00Z")
    private val playing = NowPlaying(title = "The Black Oak", artists = "Emily Jane White", player = "p")

    private fun track(vararg urls: String): TrackState {
        val evidence = urls.map { EvidenceDraft(SourceId.WEB, Scope.WEB, "A line.", it, at) }
        val web =
            SourceState.Done(
                SourceResult(Outcome.SongArticle, evidence, Duration.ZERO, trackOnly = true),
                cached = false,
            )
        val notFound = SourceState.Done(SourceResult(Outcome.None, emptyList(), Duration.ZERO), cached = false)
        return TrackState(
            playing,
            Normalizer(JvmAccentFolder).normalize(playing),
            sources = mapOf(SourceId.WEB to web, SourceId.MUSICBRAINZ to notFound),
        )
    }

    @Test
    fun aLyricsPageAmongTheWebResultsIsTheNoLinksWebPage() {
        val genius = "https://genius.com/Emily-jane-white-the-black-oak-lyrics"
        val links =
            lyricsLinks(track("https://example.org/review", genius), search = true, accentFolder = JvmAccentFolder)
        assertEquals(
            LyricsLinks.NoLink(
                NoLinkReason.NotFound,
                LyricsLink(LyricsSite.GENIUS, searchUrl(Normalizer(JvmAccentFolder).normalize(playing))),
                web = listOf(LyricsLink(LyricsSite.GENIUS, genius)),
            ),
            links,
        )
    }

    @Test
    fun aLyricsPageWhoseTextWasAllBoilerplateIsStillTheNoLinksWebPage() {
        // A web search result kept as about the recording, whose highlight was only its title (#905).
        val genius = "https://genius.com/Emily-jane-white-the-black-oak-lyrics"
        val web =
            SourceState.Done(
                SourceResult(Outcome.None, emptyList(), Duration.ZERO, trackOnly = true, pages = listOf(genius)),
                cached = false,
            )
        val track = track().let { it.copy(sources = it.sources + (SourceId.WEB to web)) }
        val links = lyricsLinks(track, search = false, accentFolder = JvmAccentFolder)
        assertEquals(listOf(LyricsLink(LyricsSite.GENIUS, genius)), (links as LyricsLinks.NoLink).web)
    }

    @Test
    fun withNoneThereIsNoWebPage() {
        val links = lyricsLinks(track("https://example.org/review"), search = false, accentFolder = JvmAccentFolder)
        assertEquals(LyricsLinks.NoLink(NoLinkReason.NotFound, null), links)
    }

    @Test
    fun aStorePageIsNotALyricsPage() {
        val bandcamp = "https://emilyjanewhite.bandcamp.com/track/the-black-oak"
        val wikisource = "https://en.wikisource.org/wiki/The_Black_Oak"
        val links = lyricsLinks(track(bandcamp, wikisource), search = true, accentFolder = JvmAccentFolder)
        assertEquals(
            LyricsLinks.NoLink(
                NoLinkReason.NotFound,
                LyricsLink(LyricsSite.GENIUS, searchUrl(Normalizer(JvmAccentFolder).normalize(playing))),
            ),
            links,
        )
    }

    @Test
    fun besideAStorePageALyricsSiteIsKept() {
        val genius = "https://genius.com/Emily-jane-white-the-black-oak-lyrics"
        val bandcamp = "https://emilyjanewhite.bandcamp.com/track/the-black-oak"
        val links = lyricsLinks(track(bandcamp, genius), search = false, accentFolder = JvmAccentFolder)
        assertEquals(
            LyricsLinks.NoLink(NoLinkReason.NotFound, null, web = listOf(LyricsLink(LyricsSite.GENIUS, genius))),
            links,
        )
    }

    @Test
    fun anArtistsOrAnAlbumsPageIsNotALyricsPage() {
        val pages =
            listOf(
                "https://genius.com/artists/Emily-jane-white",
                "https://genius.com/albums/Emily-jane-white/They-moved-in-shadow-all-together",
                "https://www.musixmatch.com/artist/Emily-Jane-White",
                "https://www.musixmatch.com/album/Emily-Jane-White/They-Moved-in-Shadow-All-Together",
            )
        val links = lyricsLinks(track(*pages.toTypedArray()), search = false, accentFolder = JvmAccentFolder)
        assertEquals(LyricsLinks.NoLink(NoLinkReason.NotFound, null), links)
    }

    @Test
    fun aSongsPageIsChosenOverAnAlbumsOnTheSameSite() {
        val album = "https://genius.com/albums/Emily-jane-white/They-moved-in-shadow-all-together"
        val genius = "https://genius.com/Emily-jane-white-the-black-oak-lyrics"
        val musixmatch = "https://www.musixmatch.com/lyrics/Emily-Jane-White/The-Black-Oak"
        val links = lyricsLinks(track(album, genius, musixmatch), search = false, accentFolder = JvmAccentFolder)
        assertEquals(
            LyricsLinks.NoLink(
                NoLinkReason.NotFound,
                null,
                web = listOf(LyricsLink(LyricsSite.GENIUS, genius), LyricsLink(LyricsSite.MUSIXMATCH, musixmatch)),
            ),
            links,
        )
    }
}
