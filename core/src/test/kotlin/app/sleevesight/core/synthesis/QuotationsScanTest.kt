package app.sleevesight.core.synthesis

import org.junit.jupiter.api.Timeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Quotations read in one forward scan (#1258): what [Quotations] reads is what the scan before it read,
 * which looked for a single quotation's closing mark again from each mark that might open one, and
 * [SelfCorrection.of] read the text again from its start for each line; a reply of many lines that each
 * hold an unclosed `'` took time that grew with the cube of its length.
 */
class QuotationsScanTest {
    @Test
    fun `quotations read as the scan before did, at every end of varied texts`() {
        val random = Random(SEED)
        repeat(TEXTS) {
            val text = String(CharArray(random.nextInt(MAX_LENGTH + 1)) { MARKS[random.nextInt(MARKS.length)] })
            val reader = Quotations.Reader(text)
            for (end in 0..text.length) {
                val expected = Before.of(text, end)
                val read = Quotations.of(text, end)
                assertEquals(expected.outside, read.outside, "$text to $end")
                assertEquals(expected.open, read.open, "$text to $end")
                assertEquals(expected.stray, read.stray, "$text to $end")
                assertContentEquals(expected.sources, read.sources, "$text to $end")
                // Read on from where it was, as SelfCorrection does from line to line.
                reader.readTo(end)
                assertEquals(expected.open, reader.open, "$text, read on to $end")
            }
            // With no mark in it that may open a single quotation, what follows a text changes nothing
            // read in it: SelfCorrection reads a line without the text after it then.
            if (text.none { it in "'‘" }) {
                val ahead = String(CharArray(random.nextInt(MAX_LENGTH + 1)) { MARKS[random.nextInt(MARKS.length)] })
                val read = Quotations.of(text + ahead, text.length)
                assertEquals(Before.of(text).outside to Before.of(text).open, read.outside to read.open, text + ahead)
            }
        }
    }

    @Test
    fun `quotations read as before in prose with apostrophes, plurals and paragraphs`() {
        val texts =
            listOf(
                "‘For the lovers’ sake, it ends’ and then 'Round Midnight' plays.",
                "rock 'n roll, 'em and '90s, I'll say 'yes' and 12\" records.\n\n'open\nstill 'here'",
                "\"Hey Jude\"'s coda, S.'s note, “a ”stray” and 'one\n  \t\nparagraph' later.",
                "(‘quoted’) -'dash' /'slash' [‘bracket’] the singers' voices' 'tones' end",
            )
        for (text in texts) {
            for (end in 0..text.length) {
                val expected = Before.of(text, end)
                val read = Quotations.of(text, end)
                assertEquals(expected.outside, read.outside, "$text to $end")
                assertEquals(expected.open to expected.stray, read.open to read.stray, "$text to $end")
                assertContentEquals(expected.sources, read.sources, "$text to $end")
            }
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `many lines that each hold an unclosed quotation are read in linear time`() {
        // 30k characters of ` 'a` lines: each line's `'` may open a quotation that nothing closes. The
        // scan before took time growing with the cube of the length, far past this test's timeout.
        val reply = "The song [W1].\n" + " 'a\n".repeat(LINES)
        assertNull(SelfCorrection.of(reply))
        assertEquals(reply, Quotations.of(reply).outside)
        // Lines that each end a sentence, before a line that cites: each was weighed as a comment
        // against all the text before and after it, which took about half a minute at this length.
        assertNull(SelfCorrection.of("The song [W1].\n" + "x.\n".repeat(LINES) + "The song [W1]."))
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `one long line of unclosed quotations is read in linear time`() {
        // ` 'a 'a …` on one line: the scan before looked for a closing mark to the end from each `'`,
        // time growing with the square of the length, far past this test's timeout at this length.
        val line = " 'a".repeat(LONG_LINE)
        assertEquals(line, Quotations.of(line).outside)
        assertNull(SelfCorrection.of("The song [W1].\n$line\nThe song [W1]."))
    }

    /** The scan before #1258, kept as written to check the one that replaced it against. */
    private object Before {
        fun of(
            text: String,
            end: Int = text.length,
        ): Quotations {
            val out = StringBuilder()
            val sources = IntArray(end)
            var close: Char? = null
            var stray = false
            for (i in 0 until end) {
                val c = text[i]
                sources[out.length] = i
                when {
                    close == SINGLE && blankLineAt(text, i) -> {
                        close = null
                        out.append(c)
                    }
                    close == SINGLE -> if (endsSingle(text, i)) close = null
                    close != null -> if (c == close && isQuoteMark(text, i)) close = null
                    c == '”' -> stray = true
                    isQuoteMark(text, i) || startsSingle(text, i) -> {
                        close = partner(c)
                        out.append('…')
                    }
                    else -> out.append(c)
                }
            }
            return Quotations(out.toString(), open = close != null, stray = stray, sources.copyOf(out.length))
        }

        private fun partner(opening: Char): Char =
            when (opening) {
                '“' -> '”'
                '"' -> '"'
                else -> SINGLE
            }

        private fun isQuoteMark(
            text: String,
            i: Int,
        ): Boolean =
            when (text[i]) {
                '“', '”' -> true
                '"' -> i == 0 || !text[i - 1].isDigit()
                else -> false
            }

        private fun opensSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'‘" &&
                (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in SINGLE_AFTER) &&
                text.getOrNull(i + 1)?.isLetter() == true

        private fun closesSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'’" &&
                i > 0 &&
                !text[i - 1].isWhitespace() &&
                text.getOrNull(i + 1)?.isLetterOrDigit() != true

        private fun startsSingle(
            text: String,
            i: Int,
        ): Boolean = opensSingle(text, i) && closer(text, i + 1) != null

        private fun endsSingle(
            text: String,
            i: Int,
        ): Boolean = closesSingle(text, i) && !(plural(text, i) && closer(text, i + 1) != null)

        private fun plural(
            text: String,
            i: Int,
        ): Boolean =
            text[i - 1] in "sS" &&
                text.getOrNull(i + 1) == ' ' &&
                text.getOrNull(i + 2)?.isLetter() == true

        private fun closer(
            text: String,
            from: Int,
        ): Int? {
            val paragraphEnd = (from until text.length).firstOrNull { blankLineAt(text, it) } ?: text.length
            return (from until paragraphEnd).firstOrNull { closesSingle(text, it) }
        }

        private fun blankLineAt(
            text: String,
            i: Int,
        ): Boolean {
            if (text[i] != '\n') return false
            var j = i + 1
            while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
            return j < text.length && text[j] == '\n'
        }

        private const val SINGLE = '\''
        private const val SINGLE_AFTER = "([{—–-/"
    }

    private companion object {
        /** What the varied texts are made of: every mark the scan weighs, and what it weighs them by. */
        const val MARKS = "''''‘’’\"“”asS S  \n\n\t1(-.x"
        const val SEED = 1258
        const val TEXTS = 4000
        const val MAX_LENGTH = 24

        /** 30k characters of ` 'a` lines. */
        const val LINES = 7_500

        /** 240k characters of ` 'a`. */
        const val LONG_LINE = 80_000
    }
}
