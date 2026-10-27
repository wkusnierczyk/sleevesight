package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId

/**
 * What a line says, as *Look again* compares lines (#617): its source, its scope and its text with
 * runs of whitespace made one space and the ends trimmed. Not its id, its place in the evidence, its
 * link or when it was retrieved, so evidence found again in another order, or spaced otherwise, says
 * nothing new. The evidence's fingerprint, which keys the synthesis cache, is not this: it stays as
 * it is.
 */
data class LineContent(
    val source: SourceId,
    val scope: Scope,
    val text: String,
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** What [draft] says. */
        fun of(draft: EvidenceDraft): LineContent = LineContent(draft.source, draft.scope, said(draft.text))

        /** What [line] says. */
        fun of(line: EvidenceLine): LineContent = LineContent(line.source, line.scope, said(line.text))

        /**
         * Whether [found] holds a line that says what none of [earlier] says. Lines of [earlier]
         * missing from [found] never count: a lookup that failed, in whole or in part, finds nothing
         * new.
         */
        fun bringsNew(
            found: Iterable<LineContent>,
            earlier: Iterable<LineContent>,
        ): Boolean {
            val known = earlier.toSet()
            return found.any { it !in known }
        }

        private fun said(text: String): String = WHITESPACE.replace(text.trim(), " ")
    }
}

/** What each of this set's lines says ([LineContent]). */
val EvidenceSet.contents: List<LineContent> get() = lines.map(LineContent::of)

/**
 * Whether this evidence holds a line that says what nothing in [earlier] says (#617,
 * [LineContent.bringsNew]): found again in another order or spacing, or with fewer lines, it does not.
 */
fun EvidenceSet.bringsNewSince(earlier: EvidenceSet): Boolean = LineContent.bringsNew(contents, earlier.contents)
