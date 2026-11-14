package app.sleevesight.core.synthesis

import app.sleevesight.core.text.trimJs

/**
 * The two parts of the song's insight on a track whose work is told apart from its performers (#1133,
 * the D-Classical mock-up's pick; `design/classical.md`, section 5): [MUSIC], on the work itself, and
 * [RECORDING], on this performance, each under its heading, in one reply ([ItemPrompt.PARTS_RULE]).
 * The reply writes each heading on a line of its own; the grounding check reads such a line as a
 * heading ([GroundingCheck.check] with `headings`), not as an uncited sentence, and a card shows it as
 * one.
 */
object SongParts {
    /** The heading of the part on the work: when and why it was composed, what it is. */
    const val MUSIC: String = "The music"

    /** The heading of the part on this performance: who plays it, its recording and release. */
    const val RECORDING: String = "This recording"

    /** The two headings, in the order the parts come. */
    val HEADINGS: List<String> = listOf(MUSIC, RECORDING)

    /**
     * The heading [line] is, as written: [MUSIC] or [RECORDING] whatever its case, perhaps with a
     * Markdown heading's `#`s before it, emphasis marks (`*`, `_`) around it or a colon after it, in
     * any order (`**The music:**`, `**The music**:`, `## __This recording__`), which the prompt asks
     * for none of; null for any other line.
     */
    fun heading(line: String): String? {
        var bare = line.trimJs()
        while (true) {
            val next = bare.trimStart('#', '*', '_').trimEnd('*', '_', ':').trimJs()
            if (next == bare) break
            bare = next
        }
        return HEADINGS.firstOrNull { it.equals(bare, ignoreCase = true) }
    }

    /**
     * [text] with each line that is a heading ([heading]) a paragraph of its own, written as the
     * heading itself: so a heading the reply wrote straight above its part, with no blank line, is
     * still one. Itself when no line is.
     */
    fun separated(text: String): String {
        val lines = text.split('\n')
        if (lines.none { heading(it) != null }) return text
        return lines
            .joinToString("\n") { line -> heading(line)?.let { "\n$it\n" } ?: line }
            .trimJs()
    }
}
