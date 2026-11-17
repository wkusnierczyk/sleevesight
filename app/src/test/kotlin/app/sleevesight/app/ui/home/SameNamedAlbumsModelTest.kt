package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.AlbumsKey
import app.sleevesight.app.pipeline.NamedAlbum
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbMedium
import app.sleevesight.core.resolution.MbReleaseGroup
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Several albums of one name on the back of Home's cover (#1080): the albums the name names, the
 * listener's choice among them shown in place of the pick and said to be theirs, the pick by date
 * said to be so, and the list of the albums, each with what tells it apart, in its order.
 */
class SameNamedAlbumsModelTest {
    // *Eyes Without A Face* (#1062), on three compilations named *Greatest Hits* and on *Rebel Yell*.
    private val idol =
        NowPlaying(title = "Eyes Without a Face", artists = "Billy Idol", album = "Greatest Hits", player = "spotify")

    private fun releasedOn(
        recording: String?,
        vararg releases: ReleaseDate,
    ): TrackState =
        track(idol).copy(
            resolution =
                Resolution(outcome = Outcome.SongArticle, recordingMbid = recording, releaseDates = releases.toList()),
        )

    private val idolTrack =
        releasedOn(
            "rec-eyes",
            ReleaseDate("Greatest Hits", "2006-03", "g-2006", "Album"),
            ReleaseDate("Greatest Hits", "2001-04-24", "g-2001", "Album"),
            ReleaseDate("Greatest Hits", null, "g-undated", "Album"),
            ReleaseDate("Rebel Yell", "1983-11-10", "g-rebel", "Album"),
        )

    private val name = choiceName("Greatest Hits", "Billy Idol")
    private val groups = listOf("g-2006", "g-2001", "g-undated")

    private fun tracklist(
        release: String,
        group: String?,
        vararg titles: String,
    ) = MbTracklist(
        release,
        listOf(MbMedium(1, titles.mapIndexed { i, title -> MbTrack(i + 1, title, null, "rec-${title.lowercase()}") })),
        group = group?.let { MbReleaseGroup(it, "Greatest Hits", "Compilation") },
    )

    @Test
    fun theAlbumsTheNameNamesEachOnceWithTheChoiceWhenItIsOneOfThem() {
        assertEquals(SameName(name, "Greatest Hits", groups, "rec-eyes"), sameName(idolTrack, idol))
        assertEquals("g-2006", sameName(idolTrack, idol, mapOf(name to AlbumChoice("g-2006", groups)))?.chosen)
        // Another artist's choice of the name is theirs.
        val other = mapOf(choiceName("Greatest Hits", "Queen") to AlbumChoice("g-2006", groups))
        assertNull(sameName(idolTrack, idol, other)?.chosen)
        assertEquals(AlbumsKey(groups, "rec-eyes"), sameName(idolTrack, idol)?.key)
        // One album of the name: nothing to choose among.
        assertNull(sameName(releasedOn("rec-eyes", ReleaseDate("Greatest Hits", "2001", "g-2001", "Album")), idol))
        // The choice is kept by the names as they are compared, however the player writes them.
        assertEquals(name, choiceName(" greatest  hits ", "BILLY IDOL"))
    }

    @Test
    fun theAlbumChosenIsAskedForAndShownSaidToBeTheListeners() {
        val choices = mapOf(name to AlbumChoice("g-2006", groups))
        val key = TracklistKey("g-2006", "rec-eyes")
        assertEquals(key, tracklistKey(idolTrack, idol, choices))
        val states =
            mapOf(
                key to TracklistState.Found(tracklist("r-2006", "g-2006", "Rebel Yell", "Eyes Without a Face")),
            )
        val back = coverBack(idolTrack, idol, true, states, choices) as CoverBack.Found
        assertEquals(PickedBy.LISTENER, back.pickedBy)
        assertEquals("g-2006", back.albums?.chosen)
        assertEquals(TrackAt(0, 1), back.playing)
        // Several not told apart, once chosen, show the choice.
        val tied =
            releasedOn(
                null,
                ReleaseDate("Greatest Hits", "2001", "g-a", "Album"),
                ReleaseDate("Greatest Hits", "2001", "g-b", "Album"),
            )
        assertEquals(
            TracklistKey("g-b", null),
            tracklistKey(
                tied,
                idol,
                mapOf(
                    name to AlbumChoice("g-b", listOf("g-a", "g-b")),
                ),
            ),
        )
    }

    @Test
    fun theYearAndOrderAreTheAlbumsFirstReleaseNotTheEditionShown() {
        // The 2001 album's release shown is a 2010 reissue; the 2006 album's, its first.
        fun album(
            group: String,
            first: String?,
            edition: String,
        ) = NamedAlbum(
            group,
            tracklist("r-$group", null, "Mony Mony")
                .copy(
                    date = edition,
                    group = MbReleaseGroup(group, "Greatest Hits", "Album", firstReleaseDate = first),
                ),
        )
        val rows =
            albumRows(
                listOf(album("g-2006", "2006-03", "2006-03"), album("g-2001", "2001-04-24", "2010")),
                idolTrack,
                idol,
            )
        assertEquals(listOf("g-2001", "g-2006"), rows.map { it.group })
        assertEquals(listOf("2001", "2006"), rows.map { it.year })
        // With no first release date for the group, the edition's.
        assertEquals("2010", albumRows(listOf(album("g", null, "2010")), idolTrack, idol).single().year)
    }

    @Test
    fun aChoiceJustMadeShowsNoOtherGroupsTracklistFoundForThePick() {
        // The pick by date asked for g-2001 with g-2006 besides, and the recording took g-2006's
        // release; the listener then chose g-2001: that entry is not g-2001's tracklist.
        val pickKey = TracklistKey("g-2001", "rec-eyes", listOf("g-2006", "g-undated"))
        val states = mapOf(pickKey to TracklistState.Found(tracklist("r-2006", "g-2006", "Eyes Without a Face")))
        val choices = mapOf(name to AlbumChoice("g-2001", groups))
        assertEquals(CoverBack.Looking, coverBack(idolTrack, idol, true, states, choices))
        // Its own group's is taken while the song's own is looked up.
        val own = mapOf(pickKey to TracklistState.Found(tracklist("r-2001", "g-2001", "Eyes Without a Face")))
        assertEquals(PickedBy.LISTENER, (coverBack(idolTrack, idol, true, own, choices) as CoverBack.Found).pickedBy)
    }

    @Test
    fun theChoiceHoldsForASongOfTheAlbumMusicBrainzDoesNotFind() {
        val choices = mapOf(name to AlbumChoice("g-2006", groups))
        val unresolved = track(idol, sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None)))
        val key = TracklistKey("g-2006", null)
        assertEquals(key, tracklistKey(unresolved, idol, choices))
        val states = mapOf(key to TracklistState.Found(tracklist("r-2006", "g-2006", "Eyes Without a Face")))
        val back = coverBack(unresolved, idol, true, states, choices) as CoverBack.Found
        assertEquals(PickedBy.LISTENER, back.pickedBy)
        // The song found by its title on the album chosen.
        assertEquals(TrackAt(0, 0), back.playing)
        // MusicBrainz switched off, for the app or the track: nothing.
        assertEquals(CoverBack.Off, coverBack(unresolved, idol, false, states, choices))
        val off = track(idol, sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.SourceOff)))
        assertEquals(CoverBack.Off, coverBack(off, idol, true, states, choices))
    }

    @Test
    fun theChoiceHoldsForAnotherSongOfTheAlbumWhateverItsReleasesName() {
        // The choice was made among three; the next song is on the 2006 album only, then on the 2001 and
        // the undated ones only: each still shows the choice, among the three, with Change.
        val choices = mapOf(name to AlbumChoice("g-2006", groups))
        val whiteWedding = idol.copy(title = "White Wedding")
        val onlyChosen =
            track(whiteWedding).copy(
                resolution =
                    Resolution(
                        outcome = Outcome.SongArticle,
                        recordingMbid = "rec-wedding",
                        releaseDates = listOf(ReleaseDate("Greatest Hits", "2006-03", "g-2006", "Album")),
                    ),
            )
        val others =
            onlyChosen.copy(
                resolution =
                    onlyChosen.resolution?.copy(
                        releaseDates =
                            listOf(
                                ReleaseDate("Greatest Hits", "2001-04-24", "g-2001", "Album"),
                                ReleaseDate("Greatest Hits", null, "g-undated", "Album"),
                            ),
                    ),
            )
        for (song in listOf(onlyChosen, others)) {
            val key = TracklistKey("g-2006", "rec-wedding")
            assertEquals(key, tracklistKey(song, whiteWedding, choices))
            val states = mapOf(key to TracklistState.Found(tracklist("r-2006", "g-2006", "White Wedding")))
            val back = coverBack(song, whiteWedding, true, states, choices) as CoverBack.Found
            assertEquals(PickedBy.LISTENER, back.pickedBy)
            assertEquals(groups, back.albums?.groups)
        }
    }

    @Test
    fun thePickByDateIsSaidToBeSoUnlessTheRecordingShowedAnotherOfTheName() {
        val key = TracklistKey("g-2001", "rec-eyes", listOf("g-2006", "g-undated"))
        val picked = mapOf(key to TracklistState.Found(tracklist("r-2001", "g-2001", "Eyes Without a Face")))
        val back = coverBack(idolTrack, idol, true, picked) as CoverBack.Found
        assertEquals(PickedBy.DATE, back.pickedBy)
        assertEquals(3, back.albums?.groups?.size)
        // The recording on none of the 2001 releases, but on the 2006 one's: no pick by date shows.
        val elsewhere = mapOf(key to TracklistState.Found(tracklist("r-2006", "g-2006", "Eyes Without a Face")))
        assertNull((coverBack(idolTrack, idol, true, elsewhere) as CoverBack.Found).pickedBy)
        // An album its name alone names: no line.
        val one = releasedOn("rec-eyes", ReleaseDate("Greatest Hits", "2001", "g-2001", "Album"))
        val alone = mapOf(TracklistKey("g-2001", "rec-eyes") to picked.values.single())
        assertNull((coverBack(one, idol, true, alone) as CoverBack.Found).pickedBy)
    }

    @Test
    fun theListShowsThoseWithTheSongFirstThenTheEarliestUndatedLast() {
        val albums =
            listOf(
                NamedAlbum("g-undated", tracklist("r-u", "g-undated", "Eyes Without a Face")),
                NamedAlbum("g-2006", tracklist("r-6", "g-2006", "Mony Mony").copy(date = "2006-03")),
                NamedAlbum(
                    "g-2001",
                    tracklist("r-1", "g-2001", "Mony Mony", "Eyes Without a Face").copy(date = "2001-04-24"),
                ),
                NamedAlbum("g-1999", tracklist("r-9", "g-1999", "Eyes Without a Face").copy(date = "1999")),
                NamedAlbum("g-empty", MbTracklist("r-e", listOf(MbMedium(1, emptyList())))),
            )
        val rows = albumRows(albums, idolTrack, idol)
        assertEquals(listOf("g-1999", "g-2001", "g-undated", "g-2006"), rows.map { it.group })
        assertEquals(listOf("1", "2", "1", null), rows.map { it.playingAt })
        assertEquals(listOf("1999", "2001", null, "2006"), rows.map { it.year })
    }

    @Test
    fun eachAlbumSaysItsTypeTracksLabelsCountryAndFormatAndWhereTheSongIsOnIt() {
        val two =
            MbTracklist(
                "r",
                listOf(
                    MbMedium(1, listOf(MbTrack(1, "Mony Mony", null, "rec-mony")), "CD"),
                    MbMedium(2, listOf(MbTrack(1, "Rebel Yell", null, null), MbTrack(2, "x", null, "rec-eyes")), "CD"),
                ),
                date = "2001-04-24",
                country = "United States",
                labels = listOf("Capitol", "EMI"),
                group = MbReleaseGroup("g", "Greatest Hits", "Compilation"),
            )
        val row = albumRows(listOf(NamedAlbum("g", two)), idolTrack, idol).single()
        assertEquals("Compilation", row.type)
        assertEquals(3, row.tracks)
        // The group's secondary types after its primary one, as MusicBrainz writes them.
        val live = two.copy(group = MbReleaseGroup("g", "Greatest Hits", "Album", listOf("Compilation", "Live")))
        assertEquals(
            "Album + Compilation + Live",
            albumRows(listOf(NamedAlbum("g", live)), idolTrack, idol).single().type,
        )
        assertEquals(listOf("Capitol", "EMI"), row.labels)
        assertEquals("United States", row.country)
        assertEquals("2×CD", row.format)
        // By its recording, the second track of the second disc.
        assertEquals("2-2", row.playingAt)
        val mixed = two.copy(media = two.media.mapIndexed { i, m -> m.copy(format = listOf("CD", "DVD")[i]) })
        assertEquals("CD + DVD", albumRows(listOf(NamedAlbum("g", mixed)), idolTrack, idol).single().format)
        val three = two.copy(media = two.media + MbMedium(3, listOf(MbTrack(1, "y", null, null)), "DVD"))
        assertEquals("2\u00d7CD + DVD", albumRows(listOf(NamedAlbum("g", three)), idolTrack, idol).single().format)
    }
}
