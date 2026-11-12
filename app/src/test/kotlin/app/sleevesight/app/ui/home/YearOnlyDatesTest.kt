package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.resolution.MbGroupFirst
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

    // The releases as the resolution gives them from MusicBrainz's search
    // (`fixtures/http/musicbrainz-1203/`), one per title and group at its earliest date: *A/B*
    // collapsed to its Icelandic digital release of 1 January 2016, said as the year since its others
    // are of 10 June 2016 (`PlaceholderDay`), the 2026 deluxe edition under its own title, and the single.
    private val ab =
        listOf(
            ReleaseDate("A/B", "2016", "g-ab", "Album"),
            ReleaseDate("A/B (Deluxe Edition)", "2026-06-10", "g-ab", "Album"),
            ReleaseDate("Way down We Go", "2015-08-07", "g-single", "Single"),
        )

    @Test
    fun theSinglesFirstReleaseIsSaidBesideTheAlbums() {
        val single = FirstRelease("2015-08-07", "Way down We Go", "g-single", "r-single", "Single")
        val state = track(wayDown).copy(resolution = Resolution(firstRelease = single, releaseDates = ab))
        assertEquals(
            listOf(
                DateSentence.Album("2016"),
                DateSentence.First("2015-08-07", "Way down We Go", ReleaseKind.SINGLE),
            ),
            homeDates(state, wayDown),
        )
    }

    @Test
    fun `the deluxe edition's original is said as the year, as the album's own sentence says it`() {
        // MusicBrainz's lookup of the group dates its first release `2016-01-01`, the Icelandic one's
        // day, which the track's releases of the group say as 2016: "The album A/B was first released
        // in 2016.", never on the later release's 10 June.
        val deluxe = wayDown.copy(album = "A/B (Deluxe Edition)", year = 2026)
        val single = FirstRelease("2015-08-07", "Way down We Go", "g-single", "r-single", "Single")
        val state = track(deluxe).copy(resolution = Resolution(firstRelease = single, releaseDates = ab))
        val firsts = mapOf("g-ab" to MbGroupFirst("g-ab", "A/B", "2016-01-01"))
        assertEquals(
            DateSentence.Edition("2016", "A/B", EditionPart.Same("2026-06-10")),
            homeDates(state, deluxe, firsts).first(),
        )
    }

    @Test
    fun `a group's January 1 keeps its day when the track's releases of the group say the day`() {
        // A group truly first released on 1 January: nothing in its year said it as a placeholder.
        val dates =
            listOf(
                ReleaseDate("New Year", "2000-01-01", "g-ny", "Album"),
                ReleaseDate("New Year (Deluxe Edition)", "2020-05-04", "g-ny", "Album"),
            )
        val deluxe = wayDown.copy(album = "New Year (Deluxe Edition)", year = 2020)
        val state = track(deluxe).copy(resolution = Resolution(releaseDates = dates))
        val firsts = mapOf("g-ny" to MbGroupFirst("g-ny", "New Year", "2000-01-01"))
        assertEquals(
            DateSentence.Edition("2000-01-01", "New Year", EditionPart.Same("2020-05-04")),
            homeDates(state, deluxe, firsts).first(),
        )
    }

    @Test
    fun aRecordingWithNoDatedReleaseListedIsSaidByItsOwnDate() {
        // *FIFA 16 Soundtrack*'s recording: MusicBrainz dates it `2015`, its release undated.
        val state = track(wayDown).copy(resolution = Resolution(recordingFirstReleased = "2015", releaseDates = ab))
        assertEquals(
            listOf(DateSentence.Album("2016"), DateSentence.First("2015", null, null)),
            homeDates(state, wayDown),
        )
    }
}
