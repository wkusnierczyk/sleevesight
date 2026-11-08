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
                "Sundown Expanded",
                "Remastered Sundown",
                "Sundown-Remastered",
                "Sundown (Remastered)",
            )
        for (name in kept) assertEquals(name, Edition.stripEditionSuffix(name), name)
    }

    @Test
    fun `all editions go, bracketed and unbracketed, in turn, while a name is left`() {
        assertEquals("Abbey Road", Edition.stripAllEditions("Abbey Road - 2019 Remaster (Deluxe)"))
        assertEquals("Sundown", Edition.stripAllEditions(" Sundown (Deluxe) - 2011 Remaster "))
        assertEquals("Sundown (Live)", Edition.stripAllEditions("Sundown (Live) - Remastered"))
        assertEquals("Deluxe", Edition.stripAllEditions("Deluxe (Remastered)"))
        assertEquals("Sundown", Edition.stripAllEditions("Sundown"))
        // The bracketed ones only, as before, for the header's year.
        assertEquals("Abbey Road - 2019 Remaster", Edition.stripEditions("Abbey Road - 2019 Remaster (Deluxe)"))
    }
}
