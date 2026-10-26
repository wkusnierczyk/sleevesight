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
         * own text is never cut. A comment is a line of its own that holds whole sentences: the text
         * before it ends a sentence (a stop, a colon or a citation) and it ends one too, so a
         * sentence wrapped over two lines is never one; it sits inside no quotation, opens with no
         * quotation mark and closes none; and it cites nothing. It then says it corrects the reply
         * in one of three ways:
         *
         * - it opens as only an aside does ([ASIDE]: `Wait,`, `Oops,`, `Hmm,`, `On second thought`,
         *   `Correction`) and speaks of the reply's making ([META]: a sentence, the evidence, a
         *   citation, the rules, the reply, the insight or the draft) or withdraws ([WITHDRAWS]);
         * - it opens as an aside may ([OPENER]: `Actually`, `Let me`, `I'll`, `I should`, `Here is`,
         *   `Corrected`, `Revised`, `So`) and says, in the first person, that it withdraws or corrects
         *   ([WITHDRAWS]: "I'll drop", "let me correct", "I should correct", "so I drop it");
         * - it ends by introducing a corrected version ([INTRO]: "Here is the corrected insight:",
         *   "Corrected reply:").
         *
         * Text must come before it, the draft, and the text after it, the fresh version, must cite
         * at least one line. The last such comment counts, so a version corrected twice keeps its
         * last.
         *
         * The cost: a comment that shares a line with the fresh version, or says none of this
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
                if (before.isNotEmpty() && CITATION.containsMatchIn(after) && isComment(line, before)) {
                    found = SelfCorrection(kept = unquoted(after), withdrawn = text.substring(0, lineEnd).trimJs())
                }
                lineStart = lineEnd + 1
            }
            return found
        }

        // Whether [line], after [before], is the model's comment on its own reply.
        private fun isComment(
            line: String,
            before: String,
        ): Boolean {
            val l = line.trimJs().trimStart('(', '*', '_', ' ')
            val whole = SENTENCE_END.containsMatchIn(before) && LINE_END.containsMatchIn(l)
            val marked = (l.firstOrNull() ?: ' ') in QUOTES || (l.lastOrNull() ?: ' ') in QUOTES
            val quoted = insideQuotation(before) || marked
            if (!whole || quoted || CITATION.containsMatchIn(l)) return false
            return (ASIDE.containsMatchIn(l) && (META.containsMatchIn(l) || WITHDRAWS.containsMatchIn(l))) ||
                (OPENER.containsMatchIn(l) && WITHDRAWS.containsMatchIn(l)) ||
                INTRO.containsMatchIn(l)
        }

        // Whether [text] leaves a quotation open: an odd count of straight double quotes, or more
        // opening curly ones than closing.
        private fun insideQuotation(text: String): Boolean =
            text.count { it == '"' } % 2 == 1 || text.count { it == '“' } > text.count { it == '”' }

        // [text] without quotation marks around the whole of it: the opening mark's partner is the
        // last character (the text's only two straight double quotes, or its only curly pair), and
        // it comes straight after the last citation, or after a stop that follows it
        // (`"... [MB1]."`). A reply that opens with a quoted title, or quotes a phrase at its end
        // (`"Andrew" is ... "a quiet farewell [W1]."`), keeps its marks.
        private fun unquoted(text: String): String {
            if (text.length < 2) return text
            val straight = text.first() == '"' && text.last() == '"' && text.count { it == '"' } == 2
            val ends = text.first() == '“' && text.last() == '”'
            val curly = ends && text.count { it == '“' } == 1 && text.count { it == '”' } == 1
            val body = text.substring(0, text.length - 1).trimEnd()
            val afterCitation = body.endsWith("]") || (body.dropLast(1).endsWith("]") && body.last() in ".!?")
            return if ((straight || curly) && afterCitation) text.substring(1, text.length - 1).trimJs() else text
        }

        /** How only an aside opens, with its case, at the start of its line. */
        private val ASIDE = Regex("^(?:(?:Wait|Oops|Hmm+)\\s*[,.!:—–-]|On second thought\\b|Correction\\b)")

        /** How an aside may open, as other sentences may too, with its case, at the start of its line. */
        private val OPENER =
            Regex(
                "^(?:Actually|Let me|I'll|I’ll|I will|I should|I need to|I must|Here is|Here's|Here’s|Corrected|" +
                    "Revised|So)\\b",
            )

        /** What an aside speaks of: the reply's making, not the music. */
        private val META = Regex("\\b(?:sentences?|evidence|citations?|cited|uncited|rules?|reply|insight|draft)\\b")

        /** The first person withdrawing or correcting what it wrote. */
        private val WITHDRAWS =
            Regex(
                "\\b(?:I|I'll|I’ll|I will|I should|I need to|I must|I'm going to|[Ll]et me)\\s+(?:\\w+\\s+)?" +
                    "(?:drop|remove|correct|revise|rewrite|fix|redo|cut)\\b",
            )

        /** A line that ends by introducing a corrected version. */
        private val INTRO =
            Regex(
                "\\b(?:[Cc]orrected|[Rr]evised|[Rr]ewritten|[Ff]ixed|[Ss]upported) (?:insight|reply|card)\\s*:\\s*$|" +
                    "\\b[Hh]ere(?: is|'s|’s) the (?:insight|reply)\\s*:\\s*$",
            )

        /** The end of a sentence, at the end of the text: a stop or colon, or a citation, then any closing marks. */
        private val SENTENCE_END = Regex("(?:[.!?:]|\\])[)\"”'’*_]*$")

        /** The end of a comment's line: a stop or colon, then any closing bracket or emphasis. */
        private val LINE_END = Regex("[.!?:][)*_]*$")

        private const val QUOTES = "\"“”"

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}
