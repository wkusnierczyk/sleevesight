package app.sleevesight.core.synthesis

/**
 * The Markdown marks that may open a sentence of a reply, as [GroundingCheck.splitSentences] reads
 * them (#574). The prompt asks for plain text, and a reply written in Markdown anyway (#555) is
 * checked as written: a mark is part of the sentence it opens.
 */
internal object MarkdownMarks {
    /** The marks of emphasis, which open and close italic (`*Title*`, `_Title_`) and bold text. */
    const val EMPHASIS: String = "*_"

    /** Whether [t] at [at] is one of [openers], after any opening run of [EMPHASIS] marks. */
    fun opens(
        t: CharSequence,
        at: Int,
        openers: String,
    ): Boolean {
        var k = at
        while (k < t.length && t[k] in EMPHASIS) k++
        return k < t.length && t[k] in openers
    }

    /**
     * Whether a line's mark starts [t] at [at]: a heading's one to six `#`s before a space, a line
     * break or the end, or a list's `-`, `*`, `+` or number (`1.`, `12)`), or a quotation's `>`,
     * before a space. Spaces and tabs have collapsed to one space by then. Read by its characters, as
     * `#{1,6}(?=[ \n]|$)|(?:[-*+>]|[0-9]{1,2}[.)]) ` matched there on the JVM reads it, not with a regex
     * started at each line break (#1318): on Android each start copies the text into ICU.
     */
    fun isLineMark(
        t: CharSequence,
        at: Int,
    ): Boolean {
        val hashes = runOf(t, at) { it == '#' }
        val digits = runOf(t, at) { it in '0'..'9' }
        val bullet = t.getOrNull(at)?.let { it in "-*+>" } == true && t.getOrNull(at + 1) == ' '
        val numbered =
            digits in 1..LIST_DIGITS &&
                t.getOrNull(at + digits)?.let { it in ".)" } == true &&
                t.getOrNull(at + digits + 1) == ' '
        return (hashes in 1..HEADING_LEVELS && endsLine(t, at + hashes)) || bullet || numbered
    }

    // How many characters from [at] in [t] on are each [what].
    private fun runOf(
        t: CharSequence,
        at: Int,
        what: (Char) -> Boolean,
    ): Int {
        var k = at
        while (k < t.length && what(t[k])) k++
        return k - at
    }

    // Whether a heading's `#`s may end at [at] in [t]: a space or a line break follows, or the end, as
    // `(?=[ \n]|$)` reads it, `$` also before a line break that ends the text (`\r\n`, `\r`, U+0085,
    // U+2028, U+2029).
    private fun endsLine(
        t: CharSequence,
        at: Int,
    ): Boolean {
        val c = t.getOrNull(at)
        val last = at == t.length - 1 && c != null && c in "\n\r\u0085\u2028\u2029"
        val crlf = at == t.length - 2 && c == '\r' && t[at + 1] == '\n'
        return c == null || c == ' ' || c == '\n' || last || crlf
    }

    /**
     * Whether the text just before [end] is a list's number at a line's start, `1.`, as a mark, the
     * line indented or not (a nested list's, `  1.`), as [isLineMark] reads it after a line break.
     */
    fun isListNumber(
        t: CharSequence,
        end: Int,
    ): Boolean {
        var start = end - 1
        while (start > 0 && t[start - 1] in '0'..'9') start--
        var indent = start
        while (indent > 0 && t[indent - 1] == ' ') indent--
        val atLineStart = indent == 0 || t[indent - 1] == '\n'
        // `[0-9]{1,2}\.`, read by its characters.
        val digits = end - 1 - start
        return atLineStart &&
            t.getOrNull(end - 1) == '.' &&
            digits in 1..LIST_DIGITS &&
            (start until end - 1).all { t[it] in '0'..'9' }
    }

    /** How many digits a list's number may have. */
    private const val LIST_DIGITS = 2

    /** How many `#`s a heading's mark may have. */
    private const val HEADING_LEVELS = 6
}
