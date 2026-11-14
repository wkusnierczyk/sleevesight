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
     * Markdown heading's `#`s before it, emphasis marks (`*`, `_`) around it, or a colon, a stop or a
     * dash after it, in any order (`**The music:**`, `**The music**:`, `## __This recording__`, `The
     * music —`), which the prompt asks for none of; null for any other line.
     */
    fun heading(line: String): String? {
        var bare = line.trimJs()
        while (true) {
            val next = bare.trimStart('#', '*', '_').trimEnd { it in TRAILING }.trimJs()
            if (next == bare) break
            bare = next
        }
        return HEADINGS.firstOrNull { it.equals(bare, ignoreCase = true) }
    }

    /**
     * [text] with each line that is a heading ([heading]) a paragraph of its own, written as the
     * heading itself, one blank line between paragraphs, and a heading with nothing of its part after it
     * left out ([withoutBare]): so a heading the reply wrote straight above its part, with no blank
     * line, is still one, and none stands over nothing. Itself when no line is a heading.
     */
    fun separated(text: String): String {
        val lines = text.split('\n')
        if (lines.none { heading(it) != null }) return text
        val paragraphs =
            lines
                .joinToString("\n") { line -> heading(line)?.let { "\n\n$it\n\n" } ?: line }
                .split(BLANK_LINE)
                .map { it.trimJs() }
                .filter { it.isNotEmpty() }
        return withoutBare(paragraphs) { heading(it) != null }.joinToString("\n\n")
    }

    /**
     * [paragraphs] without each heading ([isHeading]) that nothing of its part follows: another
     * heading next, or the end. What the grounding check reads ([separated]) and what is left once
     * sentences citing lines gone are taken out (`withoutCiting`) both keep no heading over nothing.
     */
    fun <T> withoutBare(
        paragraphs: List<T>,
        isHeading: (T) -> Boolean,
    ): List<T> =
        paragraphs.filterIndexed { i, paragraph ->
            !isHeading(paragraph) || paragraphs.getOrNull(i + 1)?.let(isHeading) == false
        }

    // What may follow a heading the prompt does not ask for: emphasis, a colon, a stop, a dash.
    private const val TRAILING = "*_:.\u2014\u2013-"

    // A blank line, perhaps with spaces on it, between two paragraphs.
    private val BLANK_LINE = Regex("\n[ \t]*\n")
}
