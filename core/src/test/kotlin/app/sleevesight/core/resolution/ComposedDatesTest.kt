package app.sleevesight.core.resolution

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ComposedDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the music was composed (#1132): read from a MusicBrainz work's composer relationships, and from a
 * Wikidata item.
 */
class ComposedDatesTest {
    private val names = NameMatcher(JvmAccentFolder)

    private fun composer(
        name: String,
        begin: String?,
        end: String?,
    ) = MbArtistRelation("composer", "mb-$name", name, emptyList(), begin, end)

    private fun work(vararg relations: MbArtistRelation) =
        MbWork("w-1", "Work", relations.toList(), emptyList(), emptyList())

    @Test
    fun `a composer relationship dated to one year is that year, linked to the work`() {
        assertEquals(
            ComposedDate("1788", "1788", SourceId.MUSICBRAINZ, "https://musicbrainz.org/work/w-1"),
            ComposedDates.ofWork(work(composer("Wolfgang Amadeus Mozart", "1788", "1788")), emptyList(), names),
        )
    }

    @Test
    fun `begin and end dates make a span of years, month aside`() {
        val date = ComposedDates.ofWork(work(composer("Giacomo Puccini", "1921-03", "1924-03")), emptyList(), names)
        assertEquals("1921" to "1924", date?.let { it.from to it.to })
    }

    @Test
    fun `a begin alone, or an end alone, is its year`() {
        assertEquals("1788", ComposedDates.ofWork(work(composer("A", "1788-06-26", null)), emptyList(), names)?.to)
        assertEquals("1805", ComposedDates.ofWork(work(composer("A", null, "1805")), emptyList(), names)?.from)
    }

    @Test
    fun `the credited composer's dates win over another composer relationship's`() {
        // An arranger named as a composer of the work, dated a century later.
        val bach = composer("Johann Sebastian Bach", "1722", "1722")
        val arranged = work(bach, composer("Ferruccio Busoni", "1897", "1897"))
        val date = ComposedDates.ofWork(arranged, listOf("Johann Sebastian Bach"), names)
        assertEquals("1722" to "1722", date?.let { it.from to it.to })
        // With no credited composer (a pop song, its writer singing it), every composer relationship counts.
        assertEquals("1722" to "1897", ComposedDates.ofWork(arranged, emptyList(), names)?.let { it.from to it.to })
    }

    @Test
    fun `a credited composer the work's relationships do not name leaves no date, not an arranger's`() {
        // `Liszt: …` with the title's composer credited, and the work naming only its arranger, dated 1897:
        // no MusicBrainz date, so Wikidata may answer.
        val arrangement = work(composer("Ferruccio Busoni", "1897", "1897"))
        assertNull(ComposedDates.ofWork(arrangement, listOf("Franz Liszt"), names))
    }

    @Test
    fun `no dated composer relationship, or none at all, is no date`() {
        assertNull(ComposedDates.ofWork(work(composer("A", null, null)), emptyList(), names))
        assertNull(ComposedDates.ofWork(work(), emptyList(), names))
        val lyricist = MbArtistRelation("lyricist", "mb-l", "L", emptyList(), "1900", "1900")
        assertNull(ComposedDates.ofWork(work(lyricist), emptyList(), names))
    }

    @Test
    fun `a Wikidata item's earliest inception is its year, not its publication`() {
        val item =
            WdEntity(
                "Q1",
                "Sonata",
                null,
                mapOf(
                    Wd.INCEPTION to listOf("+1790-00-00T00:00:00Z", "+1788-06-26T00:00:00Z"),
                    Wd.PUBLICATION_DATE to listOf("+1805-00-00T00:00:00Z"),
                ),
            )
        assertEquals(
            ComposedDate("1788", "1788", SourceId.WIKIDATA, "https://www.wikidata.org/wiki/Q1"),
            ComposedDates.ofItem(item),
        )
    }

    @Test
    fun `a Wikidata item with no inception, or one before the common era or vaguer than a year, has no date`() {
        val published = WdEntity("Q2", "Song", null, mapOf(Wd.PUBLICATION_DATE to listOf("+2011-07-05T00:00:00Z")))
        assertNull(ComposedDates.ofItem(published))
        // A decade's precision is kept as nothing (`WikidataClient.atPrecision`).
        val vague = WdEntity("Q3", "Hymn", null, mapOf(Wd.INCEPTION to listOf("-0500-00-00T00:00:00Z", "")))
        assertNull(ComposedDates.ofItem(vague))
    }
}
