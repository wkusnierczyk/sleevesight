package app.sleevesight.core.synthesis

import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import app.sleevesight.core.text.trimJs
import org.junit.jupiter.api.Timeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reply's comment lines weighed, and its sentences split, in time that grows in step with its length
 * (#1318, #1328). [SelfCorrection.of] weighed each line that read as a comment against all the text
 * before and after it, and the sentence split read the rest of the text again at each sentence's end, so
 * a reply of many such lines, or of many sentences, took time that grew with the square of its length.
 */
class GroundingScanTest {
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `many comment lines between two cited lines are weighed in linear time`() {
        // 256k characters of `Wait, the evidence.` lines: each was weighed against all the text before
        // and after it, about 3.6 s at 64k characters and far past this test's timeout at this length.
        val reply = "The song [W1].\n" + "Wait, the evidence.\n".repeat(LINES) + "The song [W1]."
        val correction = assertNotNull(SelfCorrection.of(reply))
        assertEquals("The song [W1].", correction.kept)
        assertEquals(reply.substringBeforeLast("\n"), correction.withdrawn)
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `many introductions with no sentence's end between them are weighed in linear time`() {
        // `Corrected insight:` lines end no sentence, so what follows each is one sentence to the reply's
        // end, read whole for each line; the last line's words are new, so no version restates its draft.
        val reply = "The song [W1].\n" + "Corrected insight:\n".repeat(LINES) + "Other words [W1]."
        assertNull(SelfCorrection.of(reply))
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a paragraph of many sentences is split in linear time`() {
        // 2M characters of `It is. `: at each sentence's end the rest of the paragraph was copied to see
        // whether it was a Roman numeral and its citations alone, time growing with the square of it.
        // The `Ω` at the end, no sentence of its own, makes each copy two bytes a character.
        val paragraph = "It is. ".repeat(SENTENCES) + "Ω."
        assertEquals(SENTENCES, GroundingCheck.splitSentences(paragraph).size)
    }

    @Test
    fun `a Roman numeral and its citations alone are found as the regex before found them, at every place`() {
        val random = Random(SEED)
        var found = 0
        repeat(TEXTS) {
            val text = pieces(random, NUMERAL_PIECES, MAX_PIECES)
            for (at in 0..text.length) {
                val expected = Before.numeralCited(text, at)
                assertEquals(expected, GroundingCheck.numeralCited(text, at), "$text at $at")
                if (expected) found++
            }
        }
        // The varied texts hold numerals and their citations alone, not only what is none.
        assertTrue(found > ENOUGH, "$found found")
    }

    @Test
    fun `a line's mark is found as the regex on the rest of the text found it, at every place`() {
        val random = Random(SEED)
        var found = 0
        repeat(TEXTS) {
            val text = pieces(random, LINE_MARK_PIECES, MAX_PIECES)
            for (at in 0..text.length) {
                val expected = Before.LINE_MARK.matchesAt(text, at)
                assertEquals(expected, MarkdownMarks.isLineMark(text, at), "$text at $at")
                if (expected) found++
            }
        }
        assertTrue(found > ENOUGH, "$found found")
    }

    @Test
    fun `the first sentence after each line is the one the whole split found, or none past its length`() {
        val random = Random(SEED)
        var nulls = 0
        var cut = 0
        repeat(TEXTS) {
            val text = pieces(random, SENTENCE_PIECES, MAX_SENTENCE_PIECES).trimJs()
            val firstSentences = FirstSentences(text, WITHIN)
            for (from in (0..text.length).filter { it == 0 || text[it - 1] == '\n' }) {
                val version = text.substring(from).trimJs()
                val expected =
                    (GroundingCheck.splitSentences(version).firstOrNull() ?: version).takeIf {
                        it.length <=
                            WITHIN
                    }
                assertEquals(expected, firstSentences.of(from), "$text from $from")
                if (expected == null) {
                    nulls++
                } else if (expected != version) {
                    cut++
                }
            }
        }
        // Sentences that end before the text does, and that run on past their length.
        assertTrue(nulls > ENOUGH && cut > ENOUGH, "$nulls none, $cut ended")
    }

    @Test
    fun `comments are weighed as before in replies of varied lines`() {
        val random = Random(SEED)
        var corrected = 0
        repeat(TEXTS) {
            val reply =
                List(
                    random.nextInt(1, MAX_LINES + 1),
                ) { REPLY_LINES[random.nextInt(REPLY_LINES.size)] }.joinToString("\n")
            val expected = Before.of(reply)
            val weighed = SelfCorrection.of(reply)
            assertEquals(expected, weighed, reply)
            if (expected != null) corrected++
        }
        assertTrue(corrected > ENOUGH, "$corrected corrected")
    }

    // A text of [count] pieces at most, each one of [pieces].
    private fun pieces(
        random: Random,
        pieces: List<String>,
        count: Int,
    ): String = List(random.nextInt(count + 1)) { pieces[random.nextInt(pieces.size)] }.joinToString("")

    /** What #1318 and #1328 replaced, kept as written to check what replaced it against. */
    private object Before {
        private const val CITES = "(?:$JS_WHITESPACE_CLASS*\\[[A-Z]{1,2}[0-9]*\\])+"
        private const val REST = "(?:$JS_WHITESPACE_CLASS[\\s\\S]*)?"
        private const val ROMAN_PATTERN = "(?=[IVX])X{0,3}(?:IX|IV|V?I{0,3})"
        private val NUMERAL_CITED =
            Regex("[*_]*$ROMAN_PATTERN[*_\"”'’)]*(?:[.!?]?$CITES[.!?]$REST|[.!?]$CITES$REST|$CITES)")
        val LINE_MARK = Regex("#{1,6}(?=[ \\n]|$)|(?:[-*+>]|[0-9]{1,2}[.)]) ")
        private val SENTENCE_END = Regex("(?:[.!?:]|\\])[)\"”'’*_]*$")
        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
        private val POSSESSIVE = Regex("['’]s$")

        fun numeralCited(
            t: String,
            at: Int,
        ): Boolean = NUMERAL_CITED.matches(t.substring(at))

        fun of(reply: String): SelfCorrection? {
            val text = reply.trimJs()
            val quotations = Quotations.Reader(text)
            val following = Quotations.Lookahead(text)
            var lineStart = 0
            var found: SelfCorrection? = null
            while (lineStart <= text.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                quotations.readTo(lineStart)
                if (lineStart > 0 && SelfCorrection.isComment(line, quotations.open) { following.closes(lineEnd) }) {
                    val before = text.substring(0, lineStart).trimJs()
                    val after = text.substring(minOf(lineEnd + 1, text.length)).trimJs()
                    val whole = before.isNotEmpty() && SENTENCE_END.containsMatchIn(before)
                    if (whole && revises(after, before)) {
                        val withdrawn = text.substring(0, lineEnd).trimJs()
                        found = SelfCorrection(kept = SelfCorrection.unquoted(after), withdrawn = withdrawn)
                    }
                }
                lineStart = lineEnd + 1
            }
            return found
        }

        private fun revises(
            version: String,
            draft: String,
        ): Boolean {
            val cited = CITATION.findAll(version).map { it.value }.toSet()
            val drafted = CITATION.findAll(draft).map { it.value }.toSet()
            if (cited.isEmpty() || !drafted.containsAll(cited)) return false
            val first = GroundingCheck.splitSentences(version).firstOrNull() ?: version
            val restated = words(first)
            val draftWords = words(draft)
            return restated.isNotEmpty() && restated.count { it in draftWords } >= restated.size * 0.75
        }

        private fun words(text: String): Set<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { word -> POSSESSIVE.replace(word.value.lowercase(), "") }
                .toSet()
    }

    private companion object {
        /** About 250k characters of 19- and 20-character lines. */
        const val LINES = 12_800

        /** 2M characters of 7-character sentences. */
        const val SENTENCES = 300_000

        const val SEED = 1318

        /** How many of the varied texts at least must hold what a test looks for. */
        const val ENOUGH = 100
        const val TEXTS = 3000
        const val MAX_PIECES = 16
        const val MAX_LINES = 14

        /** Enough for some texts to run past [WITHIN] and what is read after it. */
        const val MAX_SENTENCE_PIECES = 160

        /** A first sentence's length, short, so that varied texts run past it as well. */
        const val WITHIN = 40

        /** What the numeral's texts are made of: numerals, their marks, citations and what breaks them. */
        val NUMERAL_PIECES =
            listOf(
                "I",
                "V",
                "X",
                "II",
                "IV",
                "XXXVIII",
                "IIII",
                "*",
                "_",
                "\"",
                "”",
                "'",
                "’",
                ")",
                ".",
                "!",
                "?",
                " ",
                "  ",
                "\n",
                "\u00A0",
                "\u0085",
                "[W1]",
                "[MB12]",
                "[AI]",
                "[ABC]",
                "[a1]",
                "[W",
                "]",
                "x",
                "It",
            )

        /** What the line marks' texts are made of: the marks and what may follow them. */
        val LINE_MARK_PIECES =
            listOf(
                "#",
                "###",
                "######",
                " ",
                "\n",
                "\r\n",
                "\u0085",
                "-",
                "*",
                "+",
                ">",
                "1",
                "12",
                ".",
                ")",
                "x",
                "\t",
            )

        /** What the sentences' texts are made of: stops, openers, abbreviations, citations, marks. */
        val SENTENCE_PIECES =
            listOf(
                "It is",
                "the U.S.",
                "Vol.",
                "Dr.",
                "Jr.",
                "I",
                "II",
                "*",
                "_",
                "\"",
                "“",
                "(",
                ")",
                "’",
                ".",
                "!",
                "?",
                ":",
                " ",
                "\t ",
                "\n",
                "\n\n",
                "\u00A0",
                "[W1]",
                "[MB2]",
                "- ",
                "1. ",
                "# ",
                "> ",
                "word",
                "Also",
            )

        /** The lines of the varied replies: comments of each kind, drafts and versions, and the rest. */
        val REPLY_LINES =
            listOf(
                "The song came out in 2022 [W1].",
                "The song came out [W1] [MB2].",
                "It charted in the U.S. [MB2].",
                "Other words entirely [W1].",
                "\"The song came out in 2022 [W1].\"",
                "A new line [W3].",
                "The song.",
                "Wait, the evidence.",
                "Wait, that second sentence is not supported by the evidence.",
                "(Wait, the evidence.)",
                "*Wait, the citation.*",
                "Hmm, the draft.",
                "Actually, I'll drop it.",
                "Let me correct that.",
                "Corrected insight:",
                "Here is the corrected insight:",
                "**Corrected insight:**",
                "Wait, 'the evidence.",
                "it said. Corrected insight:",
                "Vol. I [W1].",
                "1. The song [W1].",
                "",
                "  ",
                "\tThe  song\tcame out [W1].",
                "x.",
                "came out in",
                // `$` matches before a final U+0085, which is no whitespace to trim.
                "The song came out [W1].\u0085",
                "Wait, the evidence.)\u0085",
            )
    }
}
