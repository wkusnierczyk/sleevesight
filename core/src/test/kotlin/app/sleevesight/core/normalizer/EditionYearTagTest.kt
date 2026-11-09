package app.sleevesight.core.normalizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The year a release's name gives its edition, a remaster's or an edition's (#1116). */
class EditionYearTagTest {
    @Test
    fun `a bracketed year next to a remaster or before an edition is the edition's`() {
        val names =
            mapOf(
                "Material Girl (2024 Remaster)" to YearTag("2024", true, "Material Girl"),
                "Actually: Further Listening 1987 - 1988 (2018 Remaster)" to
                    YearTag("2018", true, "Actually: Further Listening 1987 - 1988"),
                "Abbey Road (Remastered 2019)" to YearTag("2019", true, "Abbey Road"),
                "Sundown (2011 Digital Remaster)" to YearTag("2011", true, "Sundown"),
                "Sundown [2011 Remastered Version]" to YearTag("2011", true, "Sundown"),
                "Rumours (2004 Edition)" to YearTag("2004", false, "Rumours"),
                // An edition a word or two qualify, its year before or after it.
                "Sundown (2011 Anniversary Edition)" to YearTag("2011", false, "Sundown"),
                "Sundown (2011 Super Deluxe Edition)" to YearTag("2011", false, "Sundown"),
                "Sundown (Deluxe Edition 2011)" to YearTag("2011", false, "Sundown"),
                "Sundown [Legacy Edition 2011]" to YearTag("2011", false, "Sundown"),
                // Another edition after it is kept in the name; one before it too.
                "Abbey Road (Remastered 2019) [Super Deluxe]" to YearTag("2019", true, "Abbey Road [Super Deluxe]"),
                "Abbey Road [Super Deluxe] (2019 Remaster)" to YearTag("2019", true, "Abbey Road [Super Deluxe]"),
            )
        for ((name, tag) in names) assertEquals(tag, EditionYear.of(name), name)
    }

    @Test
    fun `an unbracketed year at the end is the edition's, with any dash before it`() {
        val names =
            mapOf(
                "Abbey Road - 2019 Remaster" to YearTag("2019", true, "Abbey Road"),
                "Abbey Road — Remastered 2009" to YearTag("2009", true, "Abbey Road"),
                "Abbey Road 2019 Digital Remaster" to YearTag("2019", true, "Abbey Road"),
                "Sundown 2011 Remastered Version" to YearTag("2011", true, "Sundown"),
                "Rumours 2004 Edition" to YearTag("2004", false, "Rumours"),
                "Abbey Road - 2019 Remaster (Deluxe)" to YearTag("2019", true, "Abbey Road (Deluxe)"),
                "Sundown Deluxe Edition 2011" to YearTag("2011", false, "Sundown"),
                "Abbey Road - Super Deluxe Edition 2019" to YearTag("2019", false, "Abbey Road"),
            )
        for ((name, tag) in names) assertEquals(tag, EditionYear.of(name), name)
    }

    @Test
    fun `a remaster's own year beside a year range is still read`() {
        val names =
            mapOf(
                "Actually: Further Listening 1987–1988 (2018 Remaster)" to
                    YearTag("2018", true, "Actually: Further Listening 1987–1988"),
                "Actually: Further Listening 1987 - 1988 - 2018 Remaster" to
                    YearTag("2018", true, "Actually: Further Listening 1987 - 1988"),
                "Hits 1987-1988 (Deluxe Edition 2011)" to YearTag("2011", false, "Hits 1987-1988"),
            )
        for ((name, tag) in names) assertEquals(tag, EditionYear.of(name), name)
    }

    @Test
    fun `a name with no year an edition takes gives none`() {
        val names =
            listOf(
                "Thriller 25 Super Deluxe Edition",
                "Rumours (Remastered)",
                "Sundown (2011)",
                "Sundown (Live 2011 Remaster)",
                "Actually: Further Listening 1987 - 1988",
                "The 1975 Deluxe Edition",
                // Without brackets, a year before a qualified edition may be the name's own.
                "The 1975 Super Deluxe Edition",
                "Paris 1919",
                "Paris 1919 Deluxe Edition",
                "1999",
                "1999 Deluxe Edition",
                "2001: A Space Odyssey",
                "2001: A Space Odyssey (Special Edition)",
                "Sundown (25th Anniversary Edition)",
                "Sundown (2011 Live Edition)",
                // A year range's endpoints are no edition's year: hyphen, en dash, em dash, spaced.
                "X (Deluxe Edition 2011–2012)",
                "X (Deluxe Edition 2011-2012)",
                "X (Deluxe Edition 2011 - 2012)",
                "X (1987–1988 Deluxe Edition)",
                "X (1987-1988 Edition)",
                "X (1987 — 1988 Super Deluxe Edition)",
                "X (2011-12 Edition)",
                "X 1987 - 1988 Edition",
                "X Deluxe Edition 2011 – 2012",
                // No-break spaces around the dash, which ICU's `\s` takes and the JVM's does not.
                "X (Deluxe Edition 2011\u00A0–\u00A02012)",
                "X 1987\u00A0-\u00A01988 Edition",
                "1999 Remastered",
                "(2011 Remaster)",
                "Edition 2004",
                "",
            )
        for (name in names) assertNull(EditionYear.of(name), name)
    }

    // Names of about 250k characters and four times that (#1147): plain words, year ranges, and
    // bracketed editions, each read in time that grows with the name's length, not its square.
    private fun words(n: Int) = (1..n).joinToString(" ") { "Word$it" }

    private fun ranges(n: Int) = List(n) { "1987 - 1988" }.joinToString(" ")

    private fun deluxes(n: Int) = List(n) { "(Deluxe)" }.joinToString(" ")

    // [EditionYear.of] the name [name] builds from a run of [size] parts, and of four times that,
    // each well under a second however slow the machine (a cost that grows with the square of the
    // length takes seconds); [tag] is what it should give, from the run.
    private fun linear(
        size: Int,
        run: (Int) -> String,
        name: (String) -> String,
        tag: (String) -> YearTag?,
    ) {
        EditionYear.of(name(run(size / 16))) // Warms the code up, so the first timing is not the compiler's.
        for (n in listOf(size, 4 * size)) {
            val parts = run(n)
            val given = name(parts)
            val read: YearTag?
            val took = measureTime { read = EditionYear.of(given) }
            assertTrue(took < 1.seconds, "${given.length} characters took $took")
            assertEquals(tag(parts), read, "${given.length} characters")
        }
    }

    @Test
    fun `a very long name of plain words is read in linear time`() {
        // "Word1" to "Word30000" is about 289k characters.
        linear(30_000, ::words, { "$it 2011 Remaster" }) { YearTag("2011", true, it) }
        linear(30_000, ::words, { "$it Road - 2011 Remaster" }) { YearTag("2011", true, "$it Road") }
        linear(30_000, ::words, { it }) { null }
    }

    @Test
    fun `a very long name of year ranges is read in linear time`() {
        // "1987 - 1988" 21000 times is about 252k characters, every word in a range.
        linear(21_000, ::ranges, { "$it 2011 Remaster" }) { YearTag("2011", true, it) }
        linear(21_000, ::ranges, { "$it Edition" }) { null }
    }

    @Test
    fun `a very long run of bracketed editions is read in linear time`() {
        // "(Deluxe)" 28000 times is about 252k characters, each an edition with no year.
        linear(28_000, ::deluxes, { "Abbey Road 2019 Remaster $it" }) { YearTag("2019", true, "Abbey Road $it") }
        linear(28_000, ::deluxes, { "Abbey Road (2019 Remaster) $it" }) { YearTag("2019", true, "Abbey Road $it") }
        linear(28_000, ::deluxes, { "Abbey Road $it" }) { null }
    }
}
