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

    @Test
    fun theAlbumIsKeyedByTheReleaseGroupItsNameNames() {
        assertEquals(group, albumGroup(resolved(), sundown))
        assertNull(albumGroup(resolved(), sundown.copy(album = "Gord's Gold")))
        assertNull(albumGroup(resolved(), sundown.copy(album = null)))
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
