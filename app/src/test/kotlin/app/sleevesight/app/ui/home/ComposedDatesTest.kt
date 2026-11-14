package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the music was composed, in the *Dates* panel after the release sentences (#1132): which date is
 * said, from which source, and when none is; how it reads is `ComposedDatesScreenTest`'s.
 */
class ComposedDatesTest {
    // Buniatishvili's 2024 recording of K. 545's Andante, as `fixtures/http/musicbrainz-1129/` resolves it.
    private val k545 =
        sundown.copy(
            title = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            artists = "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
            album = "Mozart: Piano Concertos Nos. 20 & 23",
        )

    private val album =
        FirstRelease("2024-10-25", "Mozart: Piano Concertos Nos. 20 & 23", "g-mozart", "r-mozart", "Album")

    private val releases =
        listOf(ReleaseDate("Mozart: Piano Concertos Nos. 20 & 23", "2024-10-25", "g-mozart", "Album"))

    private val work = "https://musicbrainz.org/work/9727cab0-f302-3b71-b937-5ccd416dd36e"

    private val mozart = ComposedDate("1788", "1788", SourceId.MUSICBRAINZ, work)

    private fun resolved(composed: ComposedDate?) =
        track(k545).copy(resolution = Resolution(firstRelease = album, releaseDates = releases, composed = composed))

    @Test
    fun `K 545 says it was composed in 1788, after the 2024 release`() {
        assertEquals(
            listOf(
                DateSentence.Album("2024-10-25"),
                DateSentence.FirstHere(),
                DateSentence.Composed(mozart),
            ),
            homeDates(resolved(mozart), k545),
        )
    }

    @Test
    fun `music composed in the release's year has no line of its own`() {
        // Chuck Berry's *Roll Over Beethoven*: MusicBrainz dates its composition January 1956, its
        // single May 1956 (`fixtures/http/musicbrainz/`).
        val berry = sundown.copy(title = "Roll Over Beethoven", artists = "Chuck Berry", album = "Roll Over Beethoven")
        val single = FirstRelease("1956-05", "Roll Over Beethoven / Drifting Heart", "g-berry", "r-berry", "Single")
        val composed = ComposedDate("1956", "1956", SourceId.MUSICBRAINZ, "https://musicbrainz.org/work/w-berry")
        val state = track(berry).copy(resolution = Resolution(firstRelease = single, composed = composed))
        val sentences = homeDates(state, berry)
        val first = DateSentence.First("1956-05", "Roll Over Beethoven / Drifting Heart", ReleaseKind.SINGLE)
        assertEquals(listOf(first), sentences)
    }

    @Test
    fun `a composition that runs into the release's year has no line either`() {
        val late = mozart.copy(from = "2020", to = "2024")
        assertEquals(2, homeDates(resolved(late), k545).size)
    }

    @Test
    fun `a composition over several years is said as their span`() {
        val turandot = mozart.copy(from = "1921", to = "1924")
        assertEquals(DateSentence.Composed(turandot), homeDates(resolved(turandot), k545).last())
    }

    @Test
    fun `no composition date, no line`() {
        assertEquals(2, homeDates(resolved(null), k545).size)
    }

    @Test
    fun `with no release said, no composition is said either`() {
        // Nothing to tell whether it repeats a release, and the panel says why it has no dates.
        val state = track(k545).copy(resolution = Resolution(composed = mozart))
        assertEquals(emptyList<DateSentence>(), homeDates(state, k545))
    }

    @Test
    fun `MusicBrainz's kept result dates it on a replay, which makes no resolution`() {
        val state =
            track(
                k545,
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to
                            done().let {
                                val kept = it.result.copy(firstRelease = album, releaseDates = releases)
                                it.copy(result = kept.copy(composed = mozart))
                            },
                    ),
            )
        assertEquals(DateSentence.Composed(mozart), homeDates(state, k545).last())
    }

    @Test
    fun `Wikidata dates it when MusicBrainz does not, and MusicBrainz wins when both do`() {
        // A year of its own, so which source is said shows.
        val wikidata = ComposedDate("1790", "1790", SourceId.WIKIDATA, "https://www.wikidata.org/wiki/Q1")
        val wikipedia: SourceState = done().let { it.copy(result = it.result.copy(composed = wikidata)) }
        val without = resolved(null).let { it.copy(sources = it.sources + (SourceId.WIKIPEDIA to wikipedia)) }
        assertEquals(DateSentence.Composed(wikidata), homeDates(without, k545).last())
        val both = resolved(mozart).let { it.copy(sources = it.sources + (SourceId.WIKIPEDIA to wikipedia)) }
        assertEquals(DateSentence.Composed(mozart), homeDates(both, k545).last())
    }
}
