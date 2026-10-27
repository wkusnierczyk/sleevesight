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
         * own text is never cut: a line is taken for a comment only when what follows it is a
         * revision of what came before it, and the line itself says it corrects the reply.
         *
         * **A revision.** Text comes before the line, the draft, and the text after it, the fresh
         * version, cites at least one line, every one of them cited in the draft already, and its
         * first sentence restates the draft: at least three quarters of its words, each counted
         * once, are the draft's ([revises]). A correction drops the draft's unsupported sentences and
         * restates the rest with the same evidence. Prose that goes on with evidence of its own
         * (`[W2]` after `[W1]`) is never cut, whatever its words; nor is prose that goes on to say
         * something new from the same line, as it does on a track documented by one line, where
         * every paragraph cites it.
         *
         * **A comment.** A line of its own that holds whole sentences: the text before it ends a
         * sentence (a stop, a colon or a citation) and the line ends one too, so a sentence wrapped
         * over two lines is never one. It sits inside no quotation, is not wrapped in emphasis
         * (`*...*`, a lyric's or a title's), and cites nothing; what it quotes (`"..."`, `“...”`,
         * `'...'`, `‘...’`; not an apostrophe, inside a word, as in `I'll`, or before a digit, as in
         * `'90s`, nor a `"` after a digit, as in `12"`) is not read. It then says it corrects the
         * reply in one of three ways:
         *
         * - it opens as only an aside does ([ASIDE]: `Wait,`, `Oops,`, `Hmm,`, `On second thought`,
         *   `Correction`) and speaks of the reply's making ([META]: a sentence, the evidence, a
         *   citation, the rules, the reply, the insight or the draft) or withdraws ([WITHDRAWS]);
         * - it opens as an aside may ([OPENER]: `Actually`, `Let me`, `I'll`, `I should`, `So`, ...)
         *   and withdraws or corrects, in the first person, a part of the reply ([WITHDRAWS]: "I'll
         *   drop it", "let me correct that", "I should correct the second sentence");
         * - a sentence of it, the line's last, introduces the corrected version, and is nothing else
         *   ([INTRO]: "Here is the corrected insight:", "Corrected reply:", `**Corrected insight:**`).
         *
         * The last such comment counts, so a version corrected twice keeps its last.
         *
         * The cost: a comment that shares a line with the fresh version, says none of this ("That
         * second sentence is not supported."), or comes before a version that cites a line the
         * draft did not or opens with a sentence of new words, is not found, and the reply shows as
         * written, the comment flagged as uncited by the check.
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
                if (before.isNotEmpty() && revises(after, before) && isComment(line, before)) {
                    found = SelfCorrection(kept = unquoted(after), withdrawn = text.substring(0, lineEnd).trimJs())
                }
                lineStart = lineEnd + 1
            }
            return found
        }

        // Whether [version] revises [draft]: it cites at least one line, and only lines the draft
        // cited, and its first sentence restates the draft, at least three quarters of its words
        // (each counted once) being the draft's.
        private fun revises(
            version: String,
            draft: String,
        ): Boolean {
            val cited = CITATION.findAll(version).map { it.value }.toSet()
            val drafted = CITATION.findAll(draft).map { it.value }.toSet()
            if (cited.isEmpty() || !drafted.containsAll(cited)) return false
            val first = SENTENCE_BREAK.find(version)?.let { version.substring(0, it.range.first + 1) } ?: version
            val restated = words(first)
            val draftWords = words(draft)
            return restated.isNotEmpty() && restated.count { it in draftWords } >= restated.size * RESTATED
        }

        // The words of [text], lower case, each once, without citations or a possessive's `'s`.
        private fun words(text: String): Set<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { word -> POSSESSIVE.replace(word.value.lowercase(), "") }
                .toSet()

        // Whether [line], after [before], is the model's comment on its own reply.
        private fun isComment(
            line: String,
            before: String,
        ): Boolean {
            val l = line.trimJs()
            val bare = l.trimStart('(', '*', '_', ' ')
            val whole = SENTENCE_END.containsMatchIn(before) && LINE_END.containsMatchIn(bare)
            val marked = (l.firstOrNull() ?: ' ') in QUOTE_MARKS || (l.lastOrNull() ?: ' ') in QUOTE_MARKS
            val read = unquotedText(bare)
            val apart = !marked && !quoteOpen(before) && !CITATION.containsMatchIn(l)
            if (!whole || !apart || read == null) return false
            return INTRO.containsMatchIn(read.trimEnd('*', '_', ')', ' ')) || (!EMPHASIZED.matches(l) && says(read))
        }

        // Whether [read], a line with its quotations taken out, says it corrects the reply as an
        // aside or in the first person.
        private fun says(read: String): Boolean {
            val withdraws = WITHDRAWS.containsMatchIn(read)
            val aside = ASIDE.containsMatchIn(read) && (withdraws || META.containsMatchIn(read))
            return aside || (OPENER.containsMatchIn(read) && withdraws)
        }

        // [line] with what it quotes taken out, each quotation as an ellipsis; null when a quotation
        // opens in it and does not close, or a closing `”` comes with none open.
        private fun unquotedText(line: String): String? = Quotations.of(line).takeIf { !it.open && !it.stray }?.outside

        // Whether [text] leaves a quotation open, its marks read as [unquotedText] reads them.
        private fun quoteOpen(text: String): Boolean = Quotations.of(text).open

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
            Regex("^(?:Actually|Let me|I'll|I’ll|I will|I should|I need to|I must|So)\\b")

        /** What an aside speaks of: the reply's making, not the music. */
        private val META = Regex("\\b(?:sentences?|evidence|citations?|cited|uncited|rules?|reply|insight|draft)\\b")

        /** The first person withdrawing or correcting a part of what it wrote. */
        private val WITHDRAWS =
            Regex(
                "\\b(?:I|I'll|I’ll|I will|I should|I need to|I must|I'm going to|I’m going to|[Ll]et me)\\s+" +
                    "(?:\\w+\\s+)?(?:drop|remove|correct|revise|rewrite|fix|redo|cut)\\s+" +
                    "(?:it|that|this|the\\s+(?:(?:first|second|third|fourth|last|final|other|uncited|" +
                    "unsupported)\\s+)?(?:sentence|claim|line))\\b",
            )

        /** A sentence that only introduces the corrected version, at the end of its line. */
        private val INTRO =
            Regex(
                "(?:^|[.!?]\\s+)(?:Corrected (?:insight|reply|version)|Revised (?:insight|reply)|" +
                    "Here(?: is|'s|’s) the corrected (?:insight|reply|version)|" +
                    "Here(?: is|'s|’s) the (?:insight|reply)):$",
            )

        /** A line wrapped in emphasis, `*...*` or `_..._`, as a lyric or a title is. */
        private val EMPHASIZED = Regex("^[*_].*[*_][.!?:)]*$")

        /** The end of a sentence, at the end of the text: a stop or colon, or a citation, then any closing marks. */
        private val SENTENCE_END = Regex("(?:[.!?:]|\\])[)\"”'’*_]*$")

        /** The end of a comment's line: a stop or colon, then any closing bracket or emphasis. */
        private val LINE_END = Regex("[.!?:][)*_]*$")

        private val QUOTE_MARKS = setOf('"', '“', '”')

        /** The share of a version's first sentence's words that must be the draft's ([revises]). */
        private const val RESTATED = 0.75

        /** A sentence's end in the text, before the space after it: a stop, then any closing marks. */
        private val SENTENCE_BREAK = Regex("[.!?][)\"”'’*_]*\\s")

        /** A possessive's ending, `'s` or `’s`, taken off a word in [revises]. */
        private val POSSESSIVE = Regex("['’]s$")

        /** A word: letters and digits, with any apostrophes inside it (`I'll`, `Rosseel's`). */
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}

/**
 * A text read for its quotations: what is [outside] them, each quotation as an ellipsis; whether a
 * quotation is still [open] at its end; and whether a closing `”` came with none open ([stray]).
 */
private class Quotations(
    val outside: String,
    val open: Boolean,
    val stray: Boolean,
) {
    companion object {
        /**
         * [text] read for its quotations: double (`"..."`, `“...”`) or single (`'...'`, `‘...’`),
         * the marks of one inside another not read as marks.
         */
        fun of(text: String): Quotations {
            val out = StringBuilder()
            var close: Char? = null
            var stray = false
            for ((i, c) in text.withIndex()) {
                when {
                    close == SINGLE -> if (closesSingle(text, i)) close = null
                    close != null -> if (c == close && isQuoteMark(text, i)) close = null
                    c == '”' -> stray = true
                    isQuoteMark(text, i) || opensSingle(text, i) -> {
                        close =
                            when (c) {
                                '“' -> '”'
                                '"' -> '"'
                                else -> SINGLE
                            }
                        out.append('…')
                    }
                    else -> out.append(c)
                }
            }
            return Quotations(out.toString(), open = close != null, stray = stray)
        }

        // Whether [text] at [i] is a quotation mark: `“`, `”`, or a `"` not after a digit (`12"`).
        private fun isQuoteMark(
            text: String,
            i: Int,
        ): Boolean =
            when (text[i]) {
                '“', '”' -> true
                '"' -> i == 0 || !text[i - 1].isDigit()
                else -> false
            }

        // Whether [text] at [i] opens a single quotation: a `'` or `‘` at the start of the text, or
        // after a space or one of [SINGLE_AFTER] (not inside a word, nor after an initial's stop, as
        // in `S.'s`), before a letter (not a digit, as in `'90s`).
        private fun opensSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'‘" &&
                (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in SINGLE_AFTER) &&
                text.getOrNull(i + 1)?.isLetter() == true

        // Whether [text] at [i] closes a single quotation: a `'` or `’` after a character that is no
        // space, and not inside a word (not `I'll`).
        private fun closesSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'’" &&
                i > 0 &&
                !text[i - 1].isWhitespace() &&
                text.getOrNull(i + 1)?.isLetterOrDigit() != true

        /** What a single quotation, opened by `'` or `‘`, waits for in [of]: `'` or `’`. */
        private const val SINGLE = '\''

        /** What a single quotation may open after, besides a space: a bracket, a dash, a slash, a double quote. */
        private const val SINGLE_AFTER = "([{—–-/\"“"
    }
}
