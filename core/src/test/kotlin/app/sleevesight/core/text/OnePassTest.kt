package app.sleevesight.core.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OnePassTest {
    @Test
    fun `the matches found in one pass are those findAll finds, and a replace makes the same text`() {
        for (regex in REGEXES) {
            for (text in texts()) {
                val expected = regex.findAll(text).map { it.range }.toList()
                assertEquals(expected, regex.rangesIn(text), "$regex in ${text.quoted()}")
                assertEquals(regex.findAll(text).map { it.value }.toList(), regex.valuesIn(text))
                assertEquals(
                    regex.replace(text) { "<${it.range.first}:${it.value}>" },
                    replaceOnce(text, regex) { "<${it.first}:${text.substring(it.first, it.last + 1)}>" },
                    "$regex in ${text.quoted()}",
                )
            }
        }
    }

    @Test
    fun `a text that holds the first mark is marked with another`() {
        val text = "\u0001**a**\u0002 _b_ \u0001\$0\\ **c**"
        val runs = Regex("\\*+|_+")
        assertEquals(runs.findAll(text).map { it.range }.toList(), runs.rangesIn(text))
        // Every character but the marks a replacement reads as its own: the regex's own search.
        val all = buildString { for (c in 1..Char.MAX_VALUE.code) if (c.toChar() !in "$\\") append(c.toChar()) }
        val everyOther = Regex("[a-z]")
        assertEquals(everyOther.findAll(all).map { it.range }.toList(), everyOther.rangesIn(all))
    }

    private fun String.quoted() = replace("\n", "\\n").replace("\r", "\\r")

    // Varied texts of the pieces the regexes below weigh, as `GroundingScanTest`'s are made.
    private fun texts(): List<String> {
        val random = Random(1351)
        return List(TEXTS) {
            buildString { repeat(random.nextInt(0, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } }
        }
    }

    private companion object {
        const val TEXTS = 400
        const val MAX_PIECES = 30

        val PIECES =
            listOf(
                "a",
                "Word",
                " ",
                "\n",
                "\n\n",
                " \n \n",
                "\t",
                "\r\n",
                "\u2028",
                "*",
                "**",
                "_",
                "__",
                "[W1]",
                "[x]",
                "NO_EVIDENCE",
                ".",
                "!",
                "\u0001",
                "$",
                "\\",
                "rock",
                "isn't",
                "’s",
                "—",
                "(",
                ")",
                "Ω",
                "\uD83C\uDFB5",
            )

        // The regexes `SelfRevision`, `MetaNote`, `GroundingCheck` and `GenreDispute` read in one pass,
        // and some whose matches may be empty, look behind, or hold anchors.
        val REGEXES =
            listOf(
                Regex("\\n[ \\n]*\\n"),
                Regex("\\n$JS_WHITESPACE_CLASS*\\n"),
                Regex("\\*+|_+"),
                Regex("\\*+"),
                Regex("_+"),
                Regex("(\\*{1,2}|_{1,2})([^*_\\n]+?)\\1"),
                Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*"),
                Regex("(?:^|\\n)[ \\t]*NO_EVIDENCE\\.?[ \\t]*(?:\\n|$)|[\\s.!?]NO_EVIDENCE\\.?$"),
                Regex("""[\p{L}\p{N}]+|[,()—–]"""),
                Regex("""[\p{L}\p{N}]+"""),
                Regex("x*"),
                Regex("(?<=a)\\*"),
                Regex("^\\*|\\*$"),
            )
    }
}
