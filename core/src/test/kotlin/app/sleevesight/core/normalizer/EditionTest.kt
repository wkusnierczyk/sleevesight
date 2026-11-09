package app.sleevesight.core.normalizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                // "Remasters" names a compilation, not an edition.
                "Led Zeppelin Remasters",
                "The Remasters",
                "Sundown 2011 Remasters",
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

    // A name of thousands of words (#1089): the suffix is looked for in one pass from the end, which
    // stops at the first word that cannot stand in one (#1119).
    private val longName = (1..5000).joinToString(" ") { "Word$it" }

    @Test
    fun `a long name with an edition at its end is stripped, and one with an edition far from its end is kept`() {
        assertEquals(longName, Edition.stripEditionSuffix("$longName 25th Anniversary Edition"))
        assertEquals(longName, Edition.stripEditionSuffix("$longName - 2011 Remaster"))
        assertEquals(listOf(longName), Edition.strippedNames("$longName Super Deluxe Edition (Remastered)"))
        val far = "Rubber Soul Deluxe Edition $longName"
        assertEquals(far, Edition.stripEditionSuffix(far))
        val onlyEditionWords = (1..5000).joinToString(" ") { "Deluxe" }
        assertEquals(onlyEditionWords, Edition.stripEditionSuffix(onlyEditionWords))
    }

    // A run of 5000 edition words after a name (#1119).
    private val deluxeRun = List(5000) { "Deluxe" }.joinToString(" ")

    @Test
    fun `a long run of edition words goes in one turn, so the names without editions take a bounded number`() {
        assertEquals("Abbey Road", Edition.stripEditionSuffix("Abbey Road $deluxeRun"))
        var passes = 0
        assertEquals(listOf("Abbey Road"), Edition.strippedNames("Abbey Road $deluxeRun") { passes++ })
        // One turn that takes the run and one that finds nothing left, with and without numbers.
        assertEquals(4, passes)
        // A run that stands for the whole title stays, however long.
        assertEquals(deluxeRun, Edition.stripEditionSuffix(deluxeRun))
        assertEquals(
            "Abbey Road - 2019",
            Edition.stripEditionSuffix("Abbey Road - 2019 Remaster $deluxeRun", numbers = false),
        )
        assertEquals("Abbey Road", Edition.stripEditionSuffix("Abbey Road - 2019 Remaster $deluxeRun"))
    }

    @Test
    fun `a name made of editions in turn, bracketed and not, takes at most a few turns`() {
        val turns = List(5000) { "Deluxe (Deluxe)" }.joinToString(" ")
        var passes = 0
        val names = Edition.strippedNames("Abbey Road $turns") { passes++ }
        assertTrue(passes <= 2 * Edition.MAX_PASSES, "$passes turns")
        assertTrue(names.isNotEmpty() && names.all { "Abbey Road $turns".startsWith(it) }, names.toString())
    }

    @Test
    fun `a long run of bracketed editions goes looking at each character a fixed number of times`() {
        val brackets = List(5000) { "(Deluxe)" }.joinToString(" ")
        val release = Reads("Abbey Road $brackets")
        assertEquals("Abbey Road", Edition.strip(release, Edition::isEdition))
        val reads = release.count[0]
        assertTrue(reads <= 4 * release.length, "$reads reads of ${release.length}")
        var passes = 0
        assertEquals(listOf("Abbey Road"), Edition.strippedNames("Abbey Road $brackets") { passes++ })
        assertEquals(4, passes)
        assertEquals("Abbey Road", Edition.strip("Abbey Road $brackets"))
    }

    // [text], counting in [count] the characters read from it, one at a time or as a copy, and from
    // its copies in turn, so a copy of what is left for each part taken counts too.
    private class Reads(
        private val text: String,
        val count: IntArray = intArrayOf(0),
    ) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            count[0]++
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            count[0] += endIndex - startIndex
            return Reads(text.substring(startIndex, endIndex), count)
        }

        override fun toString(): String {
            count[0] += text.length
            return text
        }
    }
}
