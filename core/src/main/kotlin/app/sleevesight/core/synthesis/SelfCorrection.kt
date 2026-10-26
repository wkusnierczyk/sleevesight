package app.sleevesight.core.synthesis

import app.sleevesight.core.text.trimJs

/**
 * A reply that corrects itself in the open (#597): a draft, then a line of the model's comment on
 * it, addressed to no one ("Wait, that second sentence is not supported by the evidence, so I'll
 * drop it. Here is the corrected insight:"), then a fresh version. Only the fresh version is shown
 * and checked; the draft and the comment are [withdrawn]. The prompt asks for the insight text
 * only, yet a model that notices an unsupported sentence of its own on a thinly documented track
 * sometimes says so and writes the insight again, rather than ending its reply.
 *
 * [kept] is what follows the last such comment, trimmed, and without quotation marks around the
 * whole of it (`"... [MB1]."`), which a model sometimes adds to set the corrected version apart.
 */
data class SelfCorrection(
    val kept: String,
    val withdrawn: String,
) {
    companion object {
        /**
         * The correction in [reply], or null when it holds none. Narrow on purpose, so an insight's
         * own text is never cut: a comment is a line of its own (after a line break, or the
         * reply's first line), cites nothing, and either opens as the model's aside does
         * ([OPENER]: `Wait`, `Oops`, `Actually`, `Let me`, `I'll`, `Here is`, ...) and speaks of the
         * reply's making ([META]: a sentence, the evidence, a citation, the rules, the reply or the
         * insight, or "I'll drop", "let me correct"), or introduces a corrected version
         * ([INTRO]: "Here is the corrected insight:", "Corrected reply:"). Text must come before
         * it, the draft, and the text after it, the fresh version, must cite at least one line.
         * The last such comment counts, so a version corrected twice keeps its last.
         *
         * The cost: a comment that shares a line with the fresh version, or opens otherwise
         * ("That second sentence is not supported."), is not found, and the reply shows as written,
         * the comment flagged as uncited by the check.
         */
        fun of(reply: String): SelfCorrection? {
            val text = reply.trimJs()
            var lineStart = 0
            var found: SelfCorrection? = null
            while (lineStart <= text.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                val before = text.substring(0, lineStart).trimJs()
                val after = text.substring(minOf(lineEnd + 1, text.length)).trimJs()
                if (before.isNotEmpty() && CITATION.containsMatchIn(after) && isComment(line)) {
                    found = SelfCorrection(kept = unquoted(after), withdrawn = text.substring(0, lineEnd).trimJs())
                }
                lineStart = lineEnd + 1
            }
            return found
        }

        // Whether [line] is the model's comment on its own reply.
        private fun isComment(line: String): Boolean {
            val l = line.trimJs().trimStart('(', '*', '_', ' ')
            if (l.isEmpty() || CITATION.containsMatchIn(l)) return false
            return (OPENER.containsMatchIn(l) && META.containsMatchIn(l)) || INTRO.containsMatchIn(l)
        }

        // [text] without quotation marks around the whole of it: a closing one straight after the
        // last citation, or after a stop that follows it (`... [MB1]."`), so a reply that opens
        // and ends with a quoted title (`"Andrew" ... "Hert."`) keeps its marks.
        private fun unquoted(text: String): String {
            val open = text.firstOrNull()
            val close = text.lastOrNull()
            if (open == null || close == null || text.length < 2) return text
            val paired = (open == '"' && close == '"') || (open == '“' && close == '”')
            val body = text.substring(0, text.length - 1).trimEnd()
            val afterCitation = body.endsWith("]") || (body.dropLast(1).endsWith("]") && body.last() in ".!?")
            return if (paired && afterCitation) text.substring(1, text.length - 1).trimJs() else text
        }

        /** How the model's aside opens, with its case, at the start of its line. */
        private val OPENER =
            Regex(
                "^(Wait|Oops|Hmm+|Actually|On second thought|On reflection|Correction|Let me|I'll|I will|" +
                    "I should|I need to|I must|Here is|Here's|Corrected|Revised|Rewritten)\\b",
            )

        /** What the aside speaks of: the reply's making, not the music. */
        private val META =
            Regex(
                "\\b(sentences?|evidence|citations?|cited|uncited|rules?|reply|insight|draft)\\b|" +
                    "\\b(I|I'll|I will|I should|I need to|I must|let me|Let me)\\s+(\\w+\\s+)?" +
                    "(drop|remove|correct|revise|rewrite|fix|redo|cut)\\b",
            )

        /** A line that introduces a corrected version, ending in a colon. */
        private val INTRO =
            Regex(
                "\\b([Cc]orrected|[Rr]evised|[Rr]ewritten|[Ff]ixed|[Ss]upported) " +
                    "(insight|reply|version|text|card)\\s*:\\s*$|\\b[Hh]ere(?: is|'s|’s) the insight\\s*:\\s*$",
            )

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}
