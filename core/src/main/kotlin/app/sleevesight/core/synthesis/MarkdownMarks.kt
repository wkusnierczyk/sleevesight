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
        t: String,
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
     * before a space. Spaces and tabs have collapsed to one space by then.
     */
    fun isLineMark(
        t: String,
        at: Int,
    ): Boolean = LINE_MARK.matchesAt(t, at)

    /** Whether the text just before [end] is a list's number at a line's start, `1.`, as a mark. */
    fun isListNumber(
        t: String,
        end: Int,
    ): Boolean {
        var start = end - 1
        while (start > 0 && t[start - 1] in '0'..'9') start--
        val atLineStart = start == 0 || t[start - 1] == '\n'
        return atLineStart && LIST_NUMBER.matches(t.substring(start, end))
    }

    private val LINE_MARK = Regex("#{1,6}(?=[ \\n]|$)|(?:[-*+>]|[0-9]{1,2}[.)]) ")
    private val LIST_NUMBER = Regex("[0-9]{1,2}\\.")
}
