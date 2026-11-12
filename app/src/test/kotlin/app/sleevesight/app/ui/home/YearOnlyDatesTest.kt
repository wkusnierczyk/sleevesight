package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Way down We Go* by KALEO on *A/B* (#1203): the track's first release beside the album's, and the
 * track's date alone when MusicBrainz lists none of its recording's releases dated.
 */
class YearOnlyDatesTest {
    private val wayDown = sundown.copy(title = "Way down We Go", artists = "KALEO", album = "A/B", year = 2016)

    // *A/B*'s releases as MusicBrainz's search gives them (`fixtures/http/musicbrainz-1203/`), by
    // title and group: its Icelandic digital release dated 1 January 2016, its others 10 June 2016.
    private val ab =
        listOf(
            ReleaseDate("A/B", "2016-01-01", "g-ab", "Album"),
            ReleaseDate("A/B (Deluxe Edition)", "2026-06-10", "g-ab", "Album"),
            ReleaseDate("Way down We Go", "2015-08-07", "g-single", "Single"),
        )

    @Test
    fun theSinglesFirstReleaseIsSaidBesideTheAlbums() {
        val single = FirstRelease("2015-08-07", "Way down We Go", "g-single", "r-single", "Single")
        val state = track(wayDown).copy(resolution = Resolution(firstRelease = single, releaseDates = ab))
        assertEquals(
            listOf(
                DateSentence.Album("2016-01-01"),
                DateSentence.First("2015-08-07", "Way down We Go", ReleaseKind.SINGLE),
            ),
            homeDates(state, wayDown),
        )
    }

    @Test
    fun aRecordingWithNoDatedReleaseListedIsSaidByItsOwnDate() {
        // *FIFA 16 Soundtrack*'s recording: MusicBrainz dates it `2015`, its release undated.
        val state = track(wayDown).copy(resolution = Resolution(recordingFirstReleased = "2015", releaseDates = ab))
        assertEquals(
            listOf(DateSentence.Album("2016-01-01"), DateSentence.First("2015", null, null)),
            homeDates(state, wayDown),
        )
    }
}
