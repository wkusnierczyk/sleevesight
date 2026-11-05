package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album's own lines in *Sources* (#522), and the artist's web lines (#912): mixed in by source,
 * numbered on from the track's.
 */
class SourcesAlbumTest {
    private val track =
        EvidenceSet.of(
            listOf(
                draft(SourceId.MUSICBRAINZ, "Sundown was recorded in 1973."),
                draft(SourceId.WIKIPEDIA, "Sundown is a song."),
                draft(SourceId.WIKIPEDIA, "It topped the charts."),
            ),
            JvmHasher,
        )

    // The album's lines as its insight numbers them, on their own.
    private val album =
        EvidenceSet.of(
            listOf(
                draft(SourceId.MUSICBRAINZ, "“Sundown” is an album by Gordon Lightfoot.", scope = Scope.RELEASE),
                draft(
                    SourceId.WIKIPEDIA,
                    "Sundown is his tenth album.",
                    "https://en.wikipedia.org/wiki/S",
                    Scope.RELEASE,
                ),
                draft(SourceId.WIKIDATA, "“Sundown” was released on Reprise Records.", scope = Scope.RELEASE),
                draft(SourceId.WIKIDATA, "“Sundown” was produced by Lenny Waronker.", scope = Scope.RELEASE),
            ),
            JvmHasher,
        )

    @Test
    fun theAlbumsLinesGoWithTheirSourcesNumberedOnFromTheTracks() {
        val shown = withAlbum(track, album)
        val ids = checkNotNull(shown.evidence).lines.map { it.id to it.text }
        assertEquals(
            listOf(
                "MB1" to "Sundown was recorded in 1973.",
                "MB2" to "“Sundown” is an album by Gordon Lightfoot.",
                "W1" to "Sundown is a song.",
                "W2" to "It topped the charts.",
                "W3" to "Sundown is his tenth album.",
                "WD1" to "“Sundown” was released on Reprise Records.",
                "WD2" to "“Sundown” was produced by Lenny Waronker.",
            ),
            ids,
        )
        // Wikidata's tag is `WD`: its lines are WD1 and WD2 when the track has none.
        assertEquals(setOf("MB2", "W3", "WD1", "WD2"), shown.album)
    }

    @Test
    fun theArtistsWebLinesAreMixedInByTheirSourceAboutTheArtistNotTheAlbum() {
        // The web search's lines for an artist the sources miss (#912), about the artist.
        val artist =
            EvidenceSet.of(
                listOf(
                    draft(SourceId.WEB, "Gordon Lightfoot was a folk singer.", "https://example.com/gl", Scope.ARTIST),
                ),
                JvmHasher,
            )
        val shown = withAlbum(track, album, artist)
        val lines = checkNotNull(shown.evidence)
        assertEquals(listOf("MB1", "MB2", "W1", "W2", "W3", "WD1", "WD2", "S1"), lines.ids)
        assertEquals(Scope.ARTIST, lines["S1"]?.scope)
        // Said to be about the artist, as its scope says: not among the album's lines.
        assertEquals(setOf("MB2", "W3", "WD1", "WD2"), shown.album)
        assertEquals("S1", shownId("S1", artist, lines))
        // With no album gathered yet, the artist's line alone.
        val alone = withAlbum(track, null, artist)
        assertEquals(listOf("MB1", "W1", "W2", "S1"), checkNotNull(alone.evidence).ids)
        assertTrue(alone.album.isEmpty())
    }

    @Test
    fun theArtistsLinesTheTrackHasAlreadyAreNotListedTwice() {
        val bio = draft(SourceId.WIKIPEDIA, "It topped the charts.")
        val artist = EvidenceSet.of(listOf(bio), JvmHasher)
        assertSame(track, withAlbum(track, null, artist).evidence)
    }

    @Test
    fun theTracksLinesKeepTheirIds() {
        val shown = checkNotNull(withAlbum(track, album).evidence)
        for (line in track.lines) assertEquals(line.text, shown[line.id]?.text)
    }

    @Test
    fun beforeTheAlbumsEvidenceIsGatheredThereAreNoAlbumLines() {
        val shown = withAlbum(track, null)
        assertSame(track, shown.evidence)
        assertTrue(shown.album.isEmpty())
    }

    @Test
    fun aCitationOfTheAlbumsLineGoesToTheIdSourcesShowsItUnder() {
        val shown = withAlbum(track, album).evidence
        // The album's insight cites its own W1: Sources shows it as W3.
        assertEquals("W3", shownId("W1", album, shown))
    }

    @Test
    fun lastFmsAndTheWebsAlbumLinesAreMixedInByTheirSourcesNumberedOnFromTheTracks() {
        // The track's own web line (its search's), and the album's Last.fm and web lines (#885).
        val track =
            EvidenceSet.of(
                listOf(
                    draft(
                        SourceId.LASTFM,
                        "The Last.fm biography of Gordon Lightfoot says: \"A folk icon.\"",
                        scope = Scope.ARTIST,
                    ),
                    draft(SourceId.WEB, "Sundown was his only number one.", "https://example.com/song", Scope.WEB),
                ),
                JvmHasher,
            )
        val album =
            EvidenceSet.of(
                listOf(
                    draft(
                        SourceId.LASTFM,
                        "The Last.fm wiki of “Sundown” says: \"Sundown is his tenth album.\"",
                        "https://www.last.fm/music/Gordon+Lightfoot/Sundown",
                        Scope.RELEASE,
                    ),
                    draft(SourceId.WEB, "Sundown topped the Billboard 200.", "https://example.org/album", Scope.WEB),
                    // A page both searches found, with the same line: the track's, not listed twice.
                    draft(SourceId.WEB, "Sundown was his only number one.", "https://example.com/song", Scope.WEB),
                ),
                JvmHasher,
            )
        val shown = withAlbum(track, album)
        assertEquals(
            listOf("L1", "L2", "S1", "S2"),
            checkNotNull(shown.evidence).lines.map { it.id },
        )
        assertEquals(setOf("L2", "S2"), shown.album)
        assertEquals("S1", shownId("S2", album, shown.evidence))
    }
}
