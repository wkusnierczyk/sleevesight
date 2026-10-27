package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId

/**
 * What a line says, as *Look again* compares lines (#617): its source, its scope and its text folded
 * so that only the words count: lower case; curly quotes, primes and dashes as their plain forms;
 * every run of white space, the Unicode spaces included, one space; the ends trimmed. A line of
 * counts and lists ([EvidenceDraft.aggregate]: Last.fm's listeners, tags and similar artists) has its
 * numbers masked and its list, after the colon, read as a set, so a count that grew or a list in
 * another order says nothing new. Not its id, its place in the evidence, its link or when it was
 * retrieved. A heuristic, cheap on purpose: a look again that wrongly finds something new only
 * offers *Rewrite insight*, a tap away. The evidence's text, its fingerprint, which keys the
 * synthesis cache, and the caches' keys are not this, and stay as they are.
 */
data class LineContent(
    val source: SourceId,
    val scope: Scope,
    val text: String,
) {
    companion object {
        private val SPACE = Regex("[\\s\\p{Z}]+")
        private val NUMBER = Regex("\\d(?:[\\d.,'\\s\\p{Z}]*\\d)?")
        private val FOLDS =
            mapOf(
                '‘' to "'",
                '’' to "'",
                '‚' to "'",
                '‛' to "'",
                '′' to "'",
                '“' to "\"",
                '”' to "\"",
                '„' to "\"",
                '‟' to "\"",
                '″' to "\"",
                '‐' to "-",
                '‑' to "-",
                '‒' to "-",
                '–' to "-",
                '—' to "-",
                '―' to "-",
                '−' to "-",
                '…' to "...",
            )

        /** What [draft] says. */
        fun of(draft: EvidenceDraft): LineContent =
            LineContent(draft.source, draft.scope, said(draft.text, draft.aggregate))

        /** What [line] says. */
        fun of(line: EvidenceLine): LineContent = LineContent(line.source, line.scope, said(line.text, line.aggregate))

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

        private fun said(
            text: String,
            aggregate: Boolean,
        ): String {
            val folded = buildString { text.lowercase().forEach { append(FOLDS[it] ?: it) } }
            val words = SPACE.replace(folded, " ").trim()
            return if (aggregate) counted(words) else words
        }

        // A line of counts and lists: its numbers masked, the list after its colon sorted.
        private fun counted(words: String): String {
            val masked = NUMBER.replace(words, "#")
            val colon = masked.indexOf(": ")
            if (colon < 0) return masked
            val items =
                masked
                    .substring(colon + 2)
                    .trimEnd('.')
                    .split(", ")
                    .map(String::trim)
                    .sorted()
            return masked.substring(0, colon) + ": " + items.joinToString(", ")
        }
    }
}

/** What each of this set's lines says ([LineContent]). */
val EvidenceSet.contents: List<LineContent> get() = lines.map(LineContent::of)

/**
 * Whether this evidence holds a line that says what nothing in [earlier] says (#617,
 * [LineContent.bringsNew]): found again in another order, spacing, case or typography, or with fewer
 * lines, it does not.
 */
fun EvidenceSet.bringsNewSince(earlier: EvidenceSet): Boolean = LineContent.bringsNew(contents, earlier.contents)
