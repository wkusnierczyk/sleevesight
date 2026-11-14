package app.sleevesight.core.resolution

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ComposedDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When the music was composed, as a work's Wikipedia infobox says it (#1273): the field `composed`,
 * else `year`, and of its value only a date's shapes. The shapes are those of the 20 works sampled on
 * 2026-11-14; `WikipediaInfoboxSampleTest` in `:sources` reads their recorded articles.
 */
class InfoboxDateTest {
    // An article's opening section, its infobox holding [fields].
    private fun article(vararg fields: String): String =
        "{{Short description|Musical composition}}\n{{Infobox musical composition\n| name = Work\n" +
            fields.joinToString("") { "| $it\n" } + "}}\nThe '''Work''' is a work."

    private fun years(vararg fields: String): Pair<Int, Int>? = InfoboxDate.years(article(*fields))

    @Test
    fun `a year, a start date template, and a span with an en dash or a hyphen`() {
        assertEquals(1801 to 1801, years("composed = 1801"))
        assertEquals(1788 to 1788, years("composed = {{start date|1788}}"))
        assertEquals(1978 to 1978, years("composed = {{Start date|1978}}"))
        assertEquals(1804 to 1808, years("composed = {{start date|1804|05|df=y}}–1808"))
        assertEquals(1890 to 1905, years("composed = 1890–1905"))
        assertEquals(1890 to 1905, years("composed = 1890 - 1905"))
        assertEquals(1804 to 1808, years("composed = {{start date|1804}}{{ndash}}{{end date|1808}}"))
        assertEquals(1804 to 1808, years("composed = 1804&ndash;1808"))
    }

    @Test
    fun `a span's end shortened to two digits is in the start's century, or the next`() {
        assertEquals(1717 to 1723, years("composed = 1717–23"))
        assertEquals(1899 to 1905, years("composed = 1899–05"))
        assertEquals(1804 to 1808, years("composed = 1804{{ndash}}08"))
        // After a hyphen, two digits are a month, as ISO 8601 writes one (May 1801), not a span's end.
        assertNull(years("composed = 1801-05"))
    }

    @Test
    fun `circa is read as the year it qualifies`() {
        assertEquals(1720 to 1720, years("composed = c. 1720"))
        assertEquals(1720 to 1720, years("composed = circa 1720"))
        assertEquals(1720 to 1720, years("composed = {{circa|1720}}"))
        assertEquals(1680 to 1706, years("composed = c. 1680 – c. 1706"))
    }

    @Test
    fun `references, comments, footnotes and links around the date are dropped`() {
        assertEquals(1936 to 1936, years("composed = 1936<ref name=\"h\">{{cite book|year=1990|title=A}}</ref>"))
        assertEquals(1936 to 1936, years("composed = 1936<ref name=\"h\" />"))
        assertEquals(1936 to 1936, years("composed = 1936 <!-- per the score -->"))
        assertEquals(1936 to 1936, years("composed = 1936{{sfn|Heyman|1992|p=4}}"))
        assertEquals(1936 to 1936, years("composed = [[1936 in music|1936]]"))
    }

    @Test
    fun `composed comes before year, and year is read when composed is missing or says no date`() {
        assertEquals(1936 to 1936, years("year = {{Start date|1936}}"))
        assertEquals(1801 to 1801, years("composed = 1801", "year = 1802"))
        assertEquals(1802 to 1802, years("composed = 1800s", "year = 1802"))
    }

    @Test
    fun `anything other than a date's shapes dates nothing`() {
        val values =
            listOf("1830s", "May 1801", "1801, revised 1803", "1801<br />1803", "Romantic", "", "{{unknown|1801}}")
        for (value in values) {
            assertNull(years("composed = $value"), value)
        }
        // A year before 1000, a span that ends before it begins, or longer than a working life.
        assertNull(years("composed = 950"))
        assertNull(years("composed = 1808–1804"))
        assertNull(years("composed = 1804–2004"))
    }

    @Test
    fun `only the infobox's own fields are read, not a citation's year or a nested template's`() {
        // Beethoven's Fourteenth, as sampled: `year=2004` is a citation's, after the infobox.
        val sonata =
            article("composed = 1801") +
                "<ref>{{cite book\n| year = 2004\n| title = Beethoven}}</ref>"
        assertEquals(1801 to 1801, InfoboxDate.years(sonata))
        val cited = article("premiere = {{cite web|year=1890}}") + "\n| year = 2004\n"
        assertNull(InfoboxDate.years(cited))
        // A field's value holding a list with pipes of its own does not cut the next field.
        val dedication = "dedication = {{plainlist|\n* J. F. M. Lobkowitz\n* [[Andrey Razumovsky|A. Rasumovsky]]}}"
        assertEquals(1804 to 1808, years(dedication, "composed = 1804–1808"))
    }

    @Test
    fun `an article without an infobox, or with one not closed, dates nothing`() {
        assertNull(InfoboxDate.years("The '''Work''' was composed in 1801."))
        assertNull(InfoboxDate.years("{{Infobox musical composition\n| composed = 1801\n"))
    }

    @Test
    fun `the date is Wikipedia's, linked to the article`() {
        val url = "https://en.wikipedia.org/wiki/Cello_Suites_(Bach)"
        assertEquals(
            ComposedDate("1717", "1723", SourceId.WIKIPEDIA, url),
            ComposedDates.ofInfobox(article("composed = 1717–23"), url),
        )
        assertNull(ComposedDates.ofInfobox(article("period = Romantic"), url))
    }

    @Test
    fun `year is read only from an infobox where it is the year of composition`() {
        // A song's, a single's or an album's `year` is a release's, and `composed` there is no composition's.
        for (template in listOf("Infobox song", "Infobox single", "Infobox album")) {
            assertNull(InfoboxDate.years("{{$template\n| name = Song\n| year = 1987\n}}"), template)
            assertNull(InfoboxDate.years("{{$template\n| composed = 1987\n}}"), template)
        }
        // Barber's Adagio for Strings, as sampled: `Infobox musical composition`'s `year`.
        assertEquals(1936 to 1936, InfoboxDate.years("{{Infobox musical composition\n| year = {{Start date|1936}}\n}}"))
        // Bach's Cello Suites, as sampled: `Infobox Bach composition`'s `composed`, but not its `year`.
        assertEquals(1717 to 1723, InfoboxDate.years("{{Infobox Bach composition\n| composed = 1717–23\n}}"))
        assertNull(InfoboxDate.years("{{Infobox Bach composition\n| year = 1720\n}}"))
    }

    @Test
    fun `a circa template over two years is a span, checked as one`() {
        assertEquals(1800 to 1810, years("composed = {{circa|1800|1810}}"))
        assertNull(years("composed = {{circa|1810|1800}}"))
        assertNull(years("composed = {{circa|1700|1800}}"))
        assertNull(years("composed = {{circa|1700|1710|1720}}"))
    }

    @Test
    fun `an infobox in a comment is not the article's`() {
        val wikitext =
            "<!--{{Infobox musical composition|composed=1999}}-->\n" +
                "{{Infobox musical composition\n| composed = 1801\n}}"
        assertEquals(1801 to 1801, InfoboxDate.years(wikitext))
    }

    @Test
    fun `a reference's own pipes make no fields`() {
        val wikitext = "{{Infobox musical composition|premiere=1938<ref>Author|year=1990|title=Title</ref>}}"
        assertNull(InfoboxDate.years(wikitext))
        val dated = "{{Infobox musical composition|composed=1936<ref>Author|composed=1990</ref>|year=1990}}"
        assertEquals(1936 to 1936, InfoboxDate.years(dated))
        assertEquals(1936 to 1936, InfoboxDate.years("{{Infobox musical composition|composed=1936<ref name=\"a\" />}}"))
    }
}
