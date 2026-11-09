package app.sleevesight.core.normalizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
                "1999 Remastered",
                "(2011 Remaster)",
                "Edition 2004",
                "",
            )
        for (name in names) assertNull(EditionYear.of(name), name)
    }
}
