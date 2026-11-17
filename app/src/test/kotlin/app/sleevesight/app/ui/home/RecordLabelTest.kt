package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line under Home's album (#729): the record labels of the release the back of the cover shows,
 * the one the song is matched to or the one chosen among albums of one name, and the album's year;
 * either alone, none when neither is known; a later edition's own year with the album's first in
 * brackets.
 */
class RecordLabelTest {
    private val group = "g-sundown"
    private val key = TracklistKey(group, "rec-sundown")

    private val resolved: TrackState =
        track(sundown).copy(
            resolution =
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec-sundown",
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", group, "Album")),
                ),
        )

    private fun found(
        labels: List<String>,
        date: String? = "1974-01",
    ) = TracklistState.Found(MbTracklist("r", emptyList(), date = date, labels = labels))

    @Test
    fun theLabelsAreThoseOfTheReleaseTheCoversBackShows() {
        assertEquals(
            AlbumLabels.Known(listOf("Reprise"), "1974"),
            albumLabels(resolved, sundown, true, mapOf(key to found(listOf("Reprise")))),
        )
    }

    @Test
    fun whileTheTrackOrItsReleaseIsLookedUpTheLabelsMayYetCome() {
        val pending = track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending))
        assertEquals(AlbumLabels.Looking, albumLabels(pending, sundown, true, emptyMap()))
        assertEquals(AlbumLabels.Looking, albumLabels(null, sundown, true, emptyMap()))
        assertEquals(AlbumLabels.Looking, albumLabels(resolved, sundown, true, emptyMap()))
        assertEquals(AlbumLabels.Looking, albumLabels(resolved, sundown, true, mapOf(key to TracklistState.Looking)))
    }

    @Test
    fun withMusicBrainzOffNotFoundOrNotReachedThereIsNoLabel() {
        val none = AlbumLabels.Known()
        assertEquals(none, albumLabels(resolved, sundown, false, mapOf(key to found(listOf("Reprise")))))
        val notFound = track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None)))
        assertEquals(none, albumLabels(notFound, sundown, true, emptyMap()))
        val failed = track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Failed("offline")))
        assertEquals(none, albumLabels(failed, sundown, true, emptyMap()))
        for (state in listOf(TracklistState.NotFound, TracklistState.Failed, TracklistState.Unclear)) {
            assertEquals(none, albumLabels(resolved, sundown, true, mapOf(key to state)))
        }
        // No album reported: no release group to look up.
        assertEquals(none, albumLabels(resolved, sundown.copy(album = null), true, emptyMap()))
    }

    @Test
    fun ofSeveralAlbumsOfTheNameTheOneChosenGivesTheLabels() {
        val chosen = "g-chosen"
        val choices = mapOf(choiceName("Sundown", "Gordon Lightfoot") to AlbumChoice(chosen, listOf(group, chosen)))
        val states = mapOf(TracklistKey(chosen, "rec-sundown") to found(listOf("Warner")))
        assertEquals(AlbumLabels.Known(listOf("Warner"), "1974"), albumLabels(resolved, sundown, true, states, choices))
    }

    @Test
    fun anotherSongsReleaseOfTheAlbumNeverStandsInForThisOnes() {
        // Two songs of one group on different releases: the second waits for its own.
        val next = TracklistKey(group, "rec-carefree")
        val carefree =
            resolved.copy(resolution = resolved.resolution!!.copy(recordingMbid = "rec-carefree"))
        val states = mapOf(key to found(listOf("Reprise")))
        assertEquals(AlbumLabels.Looking, albumLabels(carefree, sundown, true, states))
        val looking = states + (next to TracklistState.Looking)
        assertEquals(AlbumLabels.Looking, albumLabels(carefree, sundown, true, looking))
        assertEquals(
            AlbumLabels.Known(listOf("Warner"), "1974"),
            albumLabels(carefree, sundown, true, states + (next to found(listOf("Warner")))),
        )
    }

    @Test
    fun theLineHoldsTheLabelsAndTheYearEitherAloneOrNone() {
        val both = AlbumLabels.Known(listOf("Harvest", "Capitol Records"), "1973")
        assertEquals(
            ReleaseLine(listOf("Harvest", "Capitol Records"), "1973"),
            releaseLine("The Dark Side of the Moon", "1973", both),
        )
        assertEquals(ReleaseLine(emptyList(), "1965"), releaseLine("Rubber Soul", "1965", AlbumLabels.Known()))
        assertNull(releaseLine("Rubber Soul", null, AlbumLabels.Known()))
        // Still looked up: the year alone, which Home shows once its wait is over.
        assertEquals(ReleaseLine(emptyList(), "1965"), releaseLine("Rubber Soul", "1965", AlbumLabels.Looking))
    }

    @Test
    fun aLaterEditionShowsItsOwnLabelAndYearTheFirstInBrackets() {
        val later = AlbumLabels.Known(listOf("Apple Records"), "2019")
        assertEquals(ReleaseLine(listOf("Apple Records"), "2019", "1969"), releaseLine("Abbey Road", "1969", later))
        // The same year: no brackets.
        assertEquals(ReleaseLine(listOf("Parlophone"), "1965"), releaseLine("Rubber Soul", "1965", known("Parlophone")))
        // A year and no label: the year alone, with its first.
        assertEquals(
            ReleaseLine(emptyList(), "2019", "1969"),
            releaseLine("Abbey Road", "1969", AlbumLabels.Known(emptyList(), "2019")),
        )
        // No first year known: the release's own alone.
        assertEquals(ReleaseLine(listOf("Apple Records"), "2019"), releaseLine("Abbey Road", null, later))
    }

    @Test
    fun anUndatedReleaseShowsItsLabelAlone() {
        val undated = AlbumLabels.Known(listOf("Apple Records"))
        assertEquals(ReleaseLine(listOf("Apple Records"), null), releaseLine("Abbey Road", "1969", undated))
    }

    @Test
    fun anAlbumNamingItsEditionsYearShowsTheLabelAlone() {
        // #1116: the name gives the year, so the line gives none.
        val remaster = AlbumLabels.Known(listOf("Sire"), "2024")
        assertEquals(ReleaseLine(listOf("Sire"), null), releaseLine("Material Girl (2024 Remaster)", "1985", remaster))
        val original = AlbumLabels.Known(listOf("Sire"), "1985")
        assertEquals(ReleaseLine(listOf("Sire"), null), releaseLine("Material Girl (2024 Remaster)", "1985", original))
        assertNull(releaseLine("Material Girl (2024 Remaster)", "1985", AlbumLabels.Known(emptyList(), "2024")))
    }

    private fun known(label: String) = AlbumLabels.Known(listOf(label), "1965")
}
