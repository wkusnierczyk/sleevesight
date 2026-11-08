package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbMedium
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the back of Home's cover shows (#984): MusicBrainz off, the track's own MusicBrainz state as
 * *Dates* reads it, then the album's tracklist as looked up, by the album's release group, with the
 * playing song found by its recording, else by its title.
 */
class CoverBackModelTest {
    private val group = "g-sundown"
    private val key = TracklistKey(group, "rec-sundown")

    // [playing] (Sundown), resolved to recording [recording], its album in [group].
    private fun resolved(
        recording: String? = "rec-sundown",
        playing: NowPlaying = sundown,
    ): TrackState =
        track(playing).copy(
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = recording,
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", group, "Album")),
                ),
        )

    private fun tracklist(vararg discs: List<String>) =
        TracklistState.Found(
            MbTracklist(
                "r",
                discs.mapIndexed { d, titles ->
                    val tracks =
                        titles.mapIndexed {
                            i,
                            title,
                            ->
                            MbTrack(i + 1, title, null, "rec-${title.lowercase()}")
                        }
                    MbMedium(d + 1, tracks)
                },
            ),
        )

    // [playing], resolved to [recording], with [releases] by title, date, group and type.
    private fun releasedOn(
        playing: NowPlaying,
        recording: String,
        vararg releases: ReleaseDate,
    ): TrackState =
        track(playing).copy(
            resolution =
                Resolution(outcome = Outcome.SongArticle, recordingMbid = recording, releaseDates = releases.toList()),
        )

    // *Eyes Without A Face* (#1062), its recording on three compilations named *Greatest Hits*: the
    // 2001 and 2006 ones, and one MusicBrainz dates not, each in a release group of its own.
    private val idol =
        NowPlaying(title = "Eyes Without a Face", artists = "Billy Idol", album = "Greatest Hits", player = "spotify")
    private val idolTrack =
        releasedOn(
            idol,
            "rec-eyes",
            ReleaseDate("Greatest Hits", "2006-03", "g-2006", "Album"),
            ReleaseDate("Greatest Hits", "2001-04-24", "g-2001", "Album"),
            ReleaseDate("Greatest Hits", null, "g-undated", "Album"),
            ReleaseDate("Rebel Yell", "1983-11-10", "g-rebel", "Album"),
        )

    @Test
    fun theAlbumIsKeyedByTheReleaseGroupItsNameNames() {
        assertEquals(AlbumPick.Groups(group), albumPick(resolved(), sundown))
        assertNull(albumPick(resolved(), sundown.copy(album = "Gord's Gold")))
        assertNull(albumPick(resolved(), sundown.copy(album = null)))
    }

    @Test
    fun ofSeveralAlbumsOfTheNameTheEarliestIsPickedTheOthersAfterIt() {
        assertEquals(AlbumPick.Groups("g-2001", listOf("g-2006", "g-undated")), albumPick(idolTrack, idol))
        assertEquals(TracklistKey("g-2001", "rec-eyes", listOf("g-2006", "g-undated")), tracklistKey(idolTrack, idol))
    }

    @Test
    fun ofSeveralAlbumsOfTheNameThePlayersYearPicksOne() {
        assertEquals("g-2006", (albumPick(idolTrack, idol.copy(year = 2006)) as AlbumPick.Groups).group)
        // A year none of them is from picks none: the earliest still.
        assertEquals("g-2001", (albumPick(idolTrack, idol.copy(year = 1999)) as AlbumPick.Groups).group)
    }

    @Test
    fun ofAlbumsAndASingleOfTheNameAnAlbumIsPickedTheOthersAlbumsFirst() {
        val track =
            releasedOn(
                idol,
                "rec-eyes",
                ReleaseDate("Greatest Hits", "1999", "g-single", "Single"),
                ReleaseDate("Greatest Hits", "2001", "g-2001", "Album"),
                ReleaseDate("Greatest Hits", "2006-09", "g-late", "Album"),
                ReleaseDate("Greatest Hits", "2006-03", "g-2006", "Album"),
            )
        assertEquals(AlbumPick.Groups("g-2001", listOf("g-2006", "g-late", "g-single")), albumPick(track, idol))
        // The player's year narrows the albums to two, the earlier of them picked.
        assertEquals(
            AlbumPick.Groups("g-2006", listOf("g-2001", "g-late", "g-single")),
            albumPick(track, idol.copy(year = 2006)),
        )
        // Only the single is of the year: the albums are preferred, so the year decides nothing.
        assertEquals("g-2001", (albumPick(track, idol.copy(year = 1999)) as AlbumPick.Groups).group)
    }

    @Test
    fun ofTwoSinglesOfTheNameTheEarliestIsAskedWithTheOther() {
        // *Tom's Diner*: Suzanne Vega's single (1987) and D.N.A.'s (1990), read as one name; the lookup
        // takes the one the playing recording is on (`AlbumTracklists.of`).
        val diner =
            NowPlaying(title = "Tom's Diner", artists = "Suzanne Vega", album = "Tom's Diner", player = "spotify")
        val track =
            releasedOn(
                diner,
                "rec-diner",
                ReleaseDate("Tom’s Diner", "1990-07", "g-dna", "Single"),
                ReleaseDate("Tom's Diner", "1987", "g-vega", "Single"),
            )
        assertEquals(TracklistKey("g-vega", "rec-diner", listOf("g-dna")), tracklistKey(track, diner))
    }

    @Test
    fun severalAlbumsOfTheNameNotToldApartSayItRatherThanNoTracklist() {
        val track =
            releasedOn(
                idol,
                "rec-eyes",
                ReleaseDate("Greatest Hits", "2001", "g-a", "Album"),
                ReleaseDate("Greatest Hits", "2001", "g-b", "Album"),
            )
        assertEquals(AlbumPick.Ambiguous, albumPick(track, idol))
        assertNull(tracklistKey(track, idol))
        assertEquals(CoverBack.Ambiguous, coverBack(track, idol, true, emptyMap()))
        val undated =
            releasedOn(
                idol,
                "rec-eyes",
                ReleaseDate("Greatest Hits", null, "g-a", "Album"),
                ReleaseDate("Greatest Hits", null, "g-b", "Album"),
            )
        assertEquals(CoverBack.Ambiguous, coverBack(undated, idol, true, emptyMap()))
    }

    @Test
    fun theTracksOwnMusicBrainzStateComesFirst() {
        val off = track(sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.SourceOff)))
        assertEquals(CoverBack.Off, coverBack(resolved(), sundown, musicBrainzOn = false, emptyMap()))
        assertEquals(CoverBack.Off, coverBack(off, sundown, musicBrainzOn = true, emptyMap()))
        val pending = track(sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending))
        assertEquals(CoverBack.Looking, coverBack(pending, sundown, true, emptyMap()))
        val unreachable = track(sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.Unreachable)))
        assertEquals(CoverBack.Unreachable(null), coverBack(unreachable, sundown, true, emptyMap()))
        val none = track(sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None)))
        assertEquals(CoverBack.NotFound, coverBack(none, sundown, true, emptyMap()))
    }

    @Test
    fun theAlbumsTracklistAsLookedUp() {
        val track = resolved()
        assertEquals(CoverBack.Looking, coverBack(track, sundown, true, emptyMap()))
        assertEquals(CoverBack.Looking, coverBack(track, sundown, true, mapOf(key to TracklistState.Looking)))
        val failed = mapOf(key to TracklistState.Failed)
        assertEquals(CoverBack.Unreachable(key), coverBack(track, sundown, true, failed))
        assertEquals(CoverBack.NotFound, coverBack(track, sundown, true, mapOf(key to TracklistState.NotFound)))
        assertEquals(CoverBack.NotFound, coverBack(track, sundown.copy(album = "Gord's Gold"), true, emptyMap()))
    }

    @Test
    fun thePlayingSongIsFoundByItsRecordingElseItsTitle() {
        val two =
            mapOf(key to tracklist(listOf("Somewhere USA", "High and Dry"), listOf("Carefree Highway", "Sundown")))
        val byRecording = coverBack(resolved(), sundown, true, two) as CoverBack.Found
        assertEquals(TrackAt(disc = 1, track = 1), byRecording.playing)
        assertEquals(2, byRecording.discs.size)
        // Another recording of it on this release: found by its title.
        val byTitle = coverBack(resolved("rec-other"), sundown, true, two) as CoverBack.Found
        assertEquals(TrackAt(1, 1), byTitle.playing)
        val elsewhere = sundown.copy(title = "Not on it")
        assertNull((coverBack(resolved("rec-other", elsewhere), elsewhere, true, two) as CoverBack.Found).playing)
    }

    @Test
    fun aDiscWithNoTracksIsLeftOutAndNoneLeftIsNotFound() {
        val one = mapOf(key to tracklist(emptyList(), listOf("Sundown")))
        val found = coverBack(resolved(), sundown, true, one) as CoverBack.Found
        assertEquals(listOf(2), found.discs.map { it.position })
        assertEquals(2, found.media)
        val none = mapOf(key to tracklist(emptyList()))
        assertEquals(CoverBack.NotFound, coverBack(resolved(), sundown, true, none))
    }

    @Test
    fun theTracklistIsAskedForTheAlbumAndThePlayingRecording() {
        assertEquals(key, tracklistKey(resolved(), sundown))
        assertEquals(TracklistKey(group, null), tracklistKey(resolved(recording = null), sundown))
        assertNull(tracklistKey(resolved(), sundown.copy(album = "Gord's Gold")))
    }

    @Test
    fun anotherSongStillLookedUpShowsTheAlbumsTracklistFoundLastItsMarkMoved() {
        val found = tracklist(listOf("Somewhere USA", "Carefree Highway", "Sundown"))
        val carefree = HomeFixtures.carefree
        val next = resolved("rec-carefree highway", carefree)
        val states = mapOf(key to found, TracklistKey(group, "rec-carefree highway") to TracklistState.Looking)
        assertEquals(TrackAt(0, 1), (coverBack(next, carefree, true, states) as CoverBack.Found).playing)
    }

    @Test
    fun theBackFollowsTheAlbumByName() {
        assertEquals(albumIdentity(sundown), albumIdentity(sundown.copy(title = "Carefree Highway")))
        assertEquals(albumIdentity(sundown), albumIdentity(sundown.copy(album = " Sundown ")))
        assertNull(albumIdentity(sundown.copy(album = "")))
    }
}
