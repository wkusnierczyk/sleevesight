package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a MusicBrainz recording title as a part of a work (#1289) costs little on a hostile title
 * (#1304): a title longer than a work's is not read, and the scans for the quoted part and what
 * follows it read the title once over. The work is counted in characters read, not timed.
 */
class PartOfWorkTitleScanTest {
    private val names = NameMatcher(JvmAccentFolder)

    // A title that counts the characters read from it.
    private class CountingText(
        private val text: String,
    ) : CharSequence {
        var reads = 0
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = CountingText(text.substring(startIndex, endIndex))

        override fun toString(): String = text
    }

    @Test
    fun `a title longer than a work's title can be is not read as a part of a work`() {
        val part = "Turandot: “Nessun dorma” (Vincerò)"
        assertEquals(1.0, names.pieceSimilarity("Nessun Dorma", part))
        val long = "Turandot: “Nessun dorma” (${"Vincerò ".repeat(40)})"
        assertTrue(long.length > 300, "${long.length}")
        assertNull(names.pieceSimilarity("Nessun Dorma", long))
    }

    @Test
    fun `finding the quoted title reads the title once for each kind of quote, however many open unclosed`() {
        for (open in listOf("“", "\"", "„", "«", "“«")) {
            val text = CountingText(open.repeat(HOSTILE))
            assertNull(firstQuoted(text), open)
            assertTrue(text.reads <= READS_PER_CHARACTER * text.length, "$open: ${text.reads} reads")
        }
    }

    @Test
    fun `checking what follows the quoted title reads it once, however long a run of whitespace`() {
        val spaces = " ".repeat(HOSTILE)
        for (tail in listOf("$spaces x", " ($spaces x", "(".repeat(HOSTILE))) {
            val text = CountingText(tail)
            assertFalse(fitsPieceTail(text))
            assertTrue(text.reads <= READS_PER_CHARACTER * text.length, "${text.reads} reads")
        }
    }

    @Test
    fun `the quoted title is the first with something in it - in English, straight or German quotes, guillemets`() {
        assertEquals(0..2, firstQuoted("“a” “b”"))
        assertEquals(2..4, firstQuoted("“”“a”"))
        assertEquals(1..3, firstQuoted("\"\"a\""))
        // The German opening quote is closed by either English one; the English one only by its own.
        assertEquals(0..2, firstQuoted("„a“"))
        assertEquals(0..2, firstQuoted("„a”"))
        assertEquals(0..4, firstQuoted("“a„b”"))
        assertEquals(0..2, firstQuoted("«a» “b”"))
        assertNull(firstQuoted("“a"))
        assertNull(firstQuoted("a” «"))
        assertNull(firstQuoted(""))
    }

    @Test
    fun `after the quoted title may come whitespace and one parenthetical with no parenthesis inside`() {
        for (fits in listOf("", "  ", " (Vincerò)", "(a) ", " (a)　", " ()")) {
            assertTrue(fitsPieceTail(fits), "'$fits'")
        }
        for (other in listOf(" x", " (a) (b)", " (a(b))", " (a", " a)", " (a) x", ")")) {
            assertFalse(fitsPieceTail(other), "'$other'")
        }
    }

    @Test
    fun `a part of a work's title reads as before - the quoted part, a parenthetical after it, nothing else`() {
        assertNotNull(names.pieceSimilarity("Nessun Dorma", "Turandot: “Nessun dorma” (Vincerò)  "))
        assertNull(names.pieceSimilarity("Nessun Dorma", "Turandot: “Nessun dorma” Vincerò"))
        assertNull(names.pieceSimilarity("Nessun Dorma", "Turandot: “ ” (Vincerò)"))
        assertEquals(1.0, names.pieceSimilarity("Nessun Dorma", "Turandot: «Nessun dorma»"))
        assertEquals(1.0, names.pieceSimilarity("Nessun Dorma", "Turandot: \"Nessun dorma\""))
    }

    private companion object {
        // A hostile title as long as the issue measured; the regexes before #1304 read it on the order of
        // HOSTILE² times.
        const val HOSTILE = 30_000

        // Each character is read once by the scan and at most once more for each kind of quote.
        const val READS_PER_CHARACTER = 6
    }
}
