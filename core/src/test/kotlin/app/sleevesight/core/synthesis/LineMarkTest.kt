package app.sleevesight.core.synthesis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LineMarkTest {
    @Test
    fun `a line's mark is read by its characters as the regex at its place read it`() {
        val random = Random(1351)
        repeat(TEXTS) {
            val text =
                buildString { repeat(random.nextInt(0, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } }
            for (at in 0..text.length) {
                val before = LINE_MARK.find(text, at)?.takeIf { it.range.first == at }?.let { it.range.last + 1 }
                assertEquals(before, MarkdownMarks.lineMarkEnd(text, at), "at $at of ${text.replace("\n", "\\n")}")
            }
        }
    }

    private companion object {
        const val TEXTS = 2_000
        const val MAX_PIECES = 12

        // `SelfRevision`'s line mark before #1351.
        val LINE_MARK = Regex("(?:#{1,6}|[-*+]|[0-9]{1,2}[.)])(?=[ \\t\\n]|$)|>")

        val PIECES =
            listOf(
                "#",
                "###",
                "#######",
                "-",
                "*",
                "+",
                ">",
                "1",
                "12",
                "123",
                ".",
                ")",
                " ",
                "\t",
                "\n",
                "\r",
                "\r\n",
                "\u0085",
                " ",
                " ",
                "a",
                "**",
                "_",
                " ",
            )
    }
}
