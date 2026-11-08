package app.sleevesight.core.normalizer

import kotlin.test.Test
import kotlin.test.assertEquals

/** An album's name without the edition its title carries without brackets (#1070). */
class EditionTest {
    @Test
    fun `an unbracketed edition at the end of the name is stripped, with any dash before it`() {
        val names =
            mapOf(
                "Decksandrumsandrockandroll 20th anniversary" to "Decksandrumsandrockandroll",
                "Nevermind 25th Anniversary Edition" to "Nevermind",
                "Rubber Soul Deluxe Edition" to "Rubber Soul",
                "Abbey Road 2019 Remaster" to "Abbey Road",
                "Sundown Remastered" to "Sundown",
                "Abbey Road - 2019 Remaster" to "Abbey Road",
                "Abbey Road – Super Deluxe Edition" to "Abbey Road",
                "Abbey Road — Remastered 2009" to "Abbey Road",
                "Abbey Road - Remastered" to "Abbey Road",
                "Sundown Expanded Edition" to "Sundown",
                "Sundown Anniversary Edition" to "Sundown",
                "Sundown Collector’s Edition" to "Sundown",
                "Sundown 2011 Remastered Version" to "Sundown",
                "Sundown Live 2011 Remaster" to "Sundown Live",
                "1999 Remastered" to "1999",
                "Abbey Road 2019 Digital Remaster" to "Abbey Road",
                "Abbey Road 50th Anniversary Super Deluxe Edition" to "Abbey Road",
                // A year or an ordinal that is not a remaster's or an anniversary's is the name's own.
                "The 1975 Deluxe Edition" to "The 1975",
                "The 1975 10th Anniversary Edition" to "The 1975",
                "The 1975 2011 Remaster" to "The 1975",
                "Sundown 2011 Deluxe Edition" to "Sundown 2011",
                "Sundown 20th Deluxe Edition" to "Sundown 20th",
            )
        for ((name, stripped) in names) assertEquals(stripped, Edition.stripEditionSuffix(name), name)
    }

    @Test
    fun `a name that is only edition words, or carries them as its own, is kept`() {
        val kept =
            listOf(
                "Deluxe",
                "Anniversary",
                "Remastered",
                "Special Edition",
                "Super Deluxe Edition",
                "Deluxe - Remastered",
                "- Remastered",
                "Happy Anniversary",
                "Abbey Road Edition",
                "Sundown Special",
                "Sundown 2011 Version",
                "Sundown 2011 Edition",
                "Sundown 2011 Anniversary",
                "Sundown Expanded",
                "Remastered Sundown",
                "Sundown-Remastered",
                "Sundown (Remastered)",
            )
        for (name in kept) assertEquals(name, Edition.stripEditionSuffix(name), name)
    }

    @Test
    fun `without numbers, every year and ordinal is the name's own`() {
        assertEquals("Paris 1919", Edition.stripEditionSuffix("Paris 1919 Remastered", numbers = false))
        assertEquals("Sundown Live 2011", Edition.stripEditionSuffix("Sundown Live 2011 Remaster", numbers = false))
        assertEquals("Sundown", Edition.stripEditionSuffix("Sundown Deluxe Edition", numbers = false))
        val kept = "Decksandrumsandrockandroll 20th anniversary"
        assertEquals(kept, Edition.stripEditionSuffix(kept, numbers = false))
    }

    @Test
    fun `the names without editions go least stripped first, bracketed and unbracketed in turn`() {
        val names =
            mapOf(
                // A year next to a remaster may be the title's own: kept first, then taken.
                "Paris 1919 Remastered" to listOf("Paris 1919", "Paris"),
                "The 1975 Remastered" to listOf("The 1975", "The"),
                "The 1975 Deluxe Edition" to listOf("The 1975"),
                "The 1975 10th Anniversary Edition" to listOf("The 1975 10th", "The 1975"),
                "Sundown Live 2011 Remaster" to listOf("Sundown Live 2011", "Sundown Live"),
                "Decksandrumsandrockandroll 20th anniversary" to listOf("Decksandrumsandrockandroll"),
                "1999 Remastered" to listOf("1999"),
                "Abbey Road - 2019 Remaster (Deluxe)" to listOf("Abbey Road - 2019", "Abbey Road"),
                " Sundown (Deluxe) - 2011 Remaster " to listOf("Sundown (Deluxe) - 2011", "Sundown"),
                "Sundown (Live) - Remastered" to listOf("Sundown (Live)"),
                "Deluxe (Remastered)" to listOf("Deluxe"),
                // Nothing to strip, or nothing left: no names.
                "Sundown" to emptyList(),
                "Deluxe" to emptyList(),
                "Super Deluxe Edition" to emptyList(),
                " " to emptyList(),
            )
        for ((name, stripped) in names) assertEquals(stripped, Edition.strippedNames(name), name)
        // The bracketed ones only, as before, for the header's year.
        assertEquals("Abbey Road - 2019 Remaster", Edition.stripEditions("Abbey Road - 2019 Remaster (Deluxe)"))
    }
}
