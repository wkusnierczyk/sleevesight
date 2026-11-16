package app.sleevesight.core.synthesis

import app.sleevesight.core.text.isJsWhitespace
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
         * `'...'`, `‘...’`; not an apostrophe, inside a word, as in `I'll`, opening one, as in `'em`,
         * or before a digit, as in `'90s`, nor a `"` after a digit, as in `12"`; [Quotations]) is
         * not read. It then says it corrects the reply in one of three ways:
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
         * The last such comment counts, so a version corrected twice keeps its last. The lines are
         * read once, each on its own, and the comments weighed from the last back, against what was
         * read of the text once for all of them ([Revisions]), so a reply of many lines that read as
         * comments takes time in step with its length (#1318, #1328).
         *
         * The cost: a comment that shares a line with the fresh version, says none of this ("That
         * second sentence is not supported."), or comes before a version that cites a line the
         * draft did not, opens with a sentence of new words, or opens with a sentence longer than
         * [FIRST_SENTENCE] characters, is not found, and the reply shows as written, the comment
         * flagged as uncited by the check.
         */
        fun of(reply: String): SelfCorrection? {
            val text = reply.trimJs()
            // Whether a quotation opened before a line is still open: read over the text as it is,
            // so a blank line before the line ends a single quotation left open; read once, on from
            // one line's start to the next (#1258).
            val quotations = Quotations.Reader(text)
            // What follows a line that is read alone ([isComment]), looked at once for all the lines.
            val following = Quotations.Lookahead(text)
            // The lines that read as comments after text that ends a sentence, as far as each line and
            // the end of the text before it tell; what needs the text before and after them is weighed
            // after, from the last back (#1258, #1318).
            val comments = mutableListOf<Line>()
            var lineStart = 0
            var index = 0
            while (lineStart <= text.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                quotations.readTo(lineStart)
                if (lineStart > 0 &&
                    isComment(line, quotations.open) { following.closes(lineEnd) } &&
                    endsSentence(text, lineStart)
                ) {
                    comments += Line(index, lineStart, lineEnd)
                }
                lineStart = lineEnd + 1
                index++
            }
            // Read only for a reply with a comment.
            val revisions = if (comments.isEmpty()) null else Revisions(text)
            val comment = revisions?.let { read -> comments.lastOrNull { read.revised(it) } } ?: return null
            val after = text.substring(minOf(comment.end + 1, text.length)).trimJs()
            return SelfCorrection(kept = unquoted(after), withdrawn = text.substring(0, comment.end).trimJs())
        }

        // A line of a text: the [index]th, from [start] to [end], the line break after it or the end.
        private class Line(
            val index: Int,
            val start: Int,
            val end: Int,
        )

        // Whether the text before [lineStart], without the whitespace at its end, ends a sentence
        // ([SENTENCE_END]): matched on its last characters, a stop and the closing marks after it,
        // not on all of it again for each line.
        private fun endsSentence(
            text: String,
            lineStart: Int,
        ): Boolean {
            var end = lineStart
            while (end > 0 && isJsWhitespace(text[end - 1])) end--
            if (end == 0) return false
            // `$` also matches before a line break that ends the text, U+0085 among them, which is no
            // whitespace here: what may come before it is read too.
            var from = end - 1
            while (from > 0 && text[from - 1] in SENTENCE_CLOSES) from--
            return SENTENCE_END.containsMatchIn(text.substring(maxOf(0, from - 1), end))
        }

        /**
         * What [revised] weighs, read once for all the comments in [text] (#1318, #1328): where its
         * citations are, the line where each word first comes, and the first sentence of the text after
         * a line, read only as far as it goes.
         */
        private class Revisions(
            private val text: String,
        ) {
            private val citations = CITATION.findAll(text).toList()

            // Where the last citation starts, and the last that cites a line for the first time.
            private val last = citations.lastOrNull()?.range?.first ?: -1
            private val newest =
                citations
                    .distinctBy { it.value }
                    .lastOrNull()
                    ?.range
                    ?.first ?: -1

            // Each word of the text ([words]), with the first line it comes in.
            private val firstLines: Map<String, Int> by lazy {
                val lines = HashMap<String, Int>()
                text.split('\n').forEachIndexed { index, line -> words(line).forEach { lines.putIfAbsent(it, index) } }
                lines
            }

            private val firstSentences = FirstSentences(text, FIRST_SENTENCE)

            /**
             * Whether the text after [comment], the version, revises the text before it, the draft: it
             * cites at least one line, and only lines the draft cited, and its first sentence restates
             * the draft, at least three quarters of its words (each counted once) being the draft's.
             * A comment cites nothing ([isComment]), so the version cites a line the draft did not just
             * when a line is cited for the first time after the comment.
             */
            fun revised(comment: Line): Boolean {
                val cites = last > comment.end && newest < comment.start
                val restated = (if (cites) firstSentences.of(comment.end + 1) else null)?.let(::words).orEmpty()
                val drafted = restated.count { (firstLines[it] ?: Int.MAX_VALUE) < comment.index }
                return restated.isNotEmpty() && drafted >= restated.size * RESTATED
            }
        }

        // The words of [text], lower case, each once, without citations or a possessive's `'s`.
        private fun words(text: String): Set<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { word -> POSSESSIVE.replace(word.value.lowercase(), "") }
                .toSet()

        // Whether [line] is the model's comment on its own reply, as far as the line itself tells
        // (whether the text before it ends a sentence is weighed in [of]): it ends a sentence, sits
        // inside no quotation (none [open] before it), cites nothing, and says it corrects the reply.
        // [ahead]: whether a single quotation that opens in it may close in the lines after it.
        // Internal for `GroundingScanTest`, which weighs comments as they were weighed before #1318.
        internal fun isComment(
            line: String,
            open: Boolean,
            ahead: () -> Boolean,
        ): Boolean {
            val l = line.trimJs()
            val bare = l.trimStart('(', '*', '_', ' ')
            val marked = (l.firstOrNull() ?: ' ') in QUOTE_MARKS || (l.lastOrNull() ?: ' ') in QUOTE_MARKS
            val apart = !open && !marked && !CITATION.containsMatchIn(l)
            val read = if (apart && LINE_END.containsMatchIn(bare)) unquotedText(bare, ahead) else null
            return read != null &&
                (INTRO.containsMatchIn(read.trimEnd('*', '_', ')', ' ')) || (!EMPHASIZED.matches(l) && says(read)))
        }

        // Whether [read], a line with its quotations taken out, says it corrects the reply as an
        // aside or in the first person.
        private fun says(read: String): Boolean {
            val withdraws = WITHDRAWS.containsMatchIn(read)
            val aside = ASIDE.containsMatchIn(read) && (withdraws || META.containsMatchIn(read))
            return aside || (OPENER.containsMatchIn(read) && withdraws)
        }

        // [line] with what it quotes taken out, each quotation as an ellipsis; null when a quotation
        // opens in it and does not close (a single one may close in the lines after it: [ahead]), or
        // a closing `”` comes with none open.
        private fun unquotedText(
            line: String,
            ahead: () -> Boolean,
        ): String? = Quotations.of(line, beyond = ahead).takeIf { !it.open && !it.stray }?.outside

        // [text] without quotation marks around the whole of it: the opening mark's partner is the
        // last character (the text's only two straight double quotes, or its only curly pair), and
        // it comes straight after the last citation, or after a stop that follows it
        // (`"... [MB1]."`). A reply that opens with a quoted title, or quotes a phrase at its end
        // (`"Andrew" is ... "a quiet farewell [W1]."`), keeps its marks. Internal as [isComment] is.
        internal fun unquoted(text: String): String {
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

        /** The closing marks that may end a sentence after its stop ([SENTENCE_END]). */
        private const val SENTENCE_CLOSES = ")\"”'’*_"

        /**
         * How long a version's first sentence may be, in characters, to restate the draft: some five
         * times an insight's long sentence. One longer is read no further, and revises nothing.
         */
        private const val FIRST_SENTENCE = 1_000

        /** The end of a comment's line: a stop or colon, then any closing bracket or emphasis. */
        private val LINE_END = Regex("[.!?:][)*_]*$")

        private val QUOTE_MARKS = setOf('"', '“', '”')

        /** The share of a version's first sentence's words that must be the draft's ([revises]). */
        private const val RESTATED = 0.75

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
 * [sources] gives, for each character of [outside], where in the text it came from: an ellipsis,
 * where its quotation opened (#798, so a sentence read without its quotations is found in the text).
 */
internal class Quotations(
    val outside: String,
    val open: Boolean,
    val stray: Boolean,
    val sources: IntArray = IntArray(0),
) {
    companion object {
        /**
         * The first [end] characters of [text] read for its quotations: double (`"..."`, `“...”`)
         * or single (`'...'`, `‘...’`), the marks of one inside another not read as marks. A single
         * mark is also an apostrophe, so a single quotation is read conservatively: it opens only
         * where a mark that closes it follows (looked for in the whole of [text]), so an
         * apostrophe that opens a word (`'Round Midnight`, `'em`, `rock 'n roll`) opens nothing;
         * it does not close at a plural's apostrophe before a word (`‘For the lovers’ sake, …’`)
         * when another mark follows that closes it; and it ends at a blank line at the latest, so
         * a stray apostrophe hides nothing after its paragraph.
         *
         * [beyond], when given, stands for lines after [text], as if they followed it from a line
         * break: whether a mark that may close a single quotation comes in them, before a blank line
         * (a line read alone, as if followed by the lines after it, in `SelfCorrection.of`). It is
         * asked only for a quotation that does not close in [text] itself.
         */
        fun of(
            text: String,
            end: Int = text.length,
            beyond: (() -> Boolean)? = null,
        ): Quotations = Reader(text, end, beyond).apply { readTo(end) }.quotations()

        // The mark that closes the quotation [opening] opens.
        private fun partner(opening: Char): Char =
            when (opening) {
                '“' -> '”'
                '"' -> '"'
                else -> SINGLE
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

        // Whether [text] at [i] may open a single quotation: a `'` or `‘` at the start of the text,
        // or after a space or one of [SINGLE_AFTER] (not inside a word, nor after an initial's stop,
        // as in `S.'s`, nor after a closing double quotation mark, as in `"Hey Jude"'s`), before a
        // letter (not a digit, as in `'90s`).
        private fun opensSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'‘" &&
                (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in SINGLE_AFTER) &&
                text.getOrNull(i + 1)?.isLetter() == true

        // Whether [text] at [i] may close a single quotation: a `'` or `’` after a character that is
        // no space, and not inside a word (not `I'll`).
        private fun closesSingle(
            text: String,
            i: Int,
        ): Boolean =
            text[i] in "'’" &&
                i > 0 &&
                !text[i - 1].isWhitespace() &&
                text.getOrNull(i + 1)?.isLetterOrDigit() != true

        // Whether the mark at [i] may be a plural's apostrophe: after an `s`, before a space and a word.
        private fun plural(
            text: String,
            i: Int,
        ): Boolean =
            text[i - 1] in "sS" &&
                text.getOrNull(i + 1) == ' ' &&
                text.getOrNull(i + 2)?.isLetter() == true

        // Whether a blank line starts at [i]: a line break, then only spaces or tabs to the next.
        private fun blankLineAt(
            text: String,
            i: Int,
        ): Boolean {
            if (text[i] != '\n') return false
            var j = i + 1
            while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
            return j < text.length && text[j] == '\n'
        }

        /** What a single quotation, opened by `'` or `‘`, waits for in [of]: `'` or `’`. */
        private const val SINGLE = '\''

        /** What a single quotation may open after, besides a space: a bracket, a dash, a slash. */
        private const val SINGLE_AFTER = "([{—–-/"
    }

    /**
     * [text] read for its quotations as [Quotations.of] reads it, one character after another up to
     * [end], and asked on the way whether a quotation is [open]: a text asked so at the start of each
     * of its lines (`SelfCorrection.of`) is read once, not again from its start for each (#1258). The
     * mark that may close a single quotation and the blank line ahead are each looked for once
     * ([Lookahead]), not again for each mark that may open one, so the read takes time in step with
     * the text's length. [beyond] as in [Quotations.of].
     */
    internal class Reader(
        private val text: String,
        end: Int = text.length,
        private val beyond: (() -> Boolean)? = null,
    ) {
        private val out = StringBuilder()
        private val sources = IntArray(end)
        private var close: Char? = null
        private var stray = false
        private var at = 0
        private val ahead = Lookahead(text)

        /** Whether a quotation is open where the read has got to. */
        val open: Boolean
            get() = close != null

        /** Reads on up to [to], no further than the [end] it was given. */
        fun readTo(to: Int) {
            require(to <= sources.size) { "$to is past the end, ${sources.size}" }
            while (at < to) step(at++)
        }

        /** What has been read so far. */
        fun quotations(): Quotations = Quotations(out.toString(), open, stray, sources.copyOf(out.length))

        private fun step(i: Int) {
            val c = text[i]
            sources[out.length] = i
            when {
                close == SINGLE && blankLineAt(text, i) -> {
                    close = null
                    out.append(c)
                }
                close == SINGLE -> if (endsSingle(i)) close = null
                close != null -> if (c == close && isQuoteMark(text, i)) close = null
                c == '”' -> stray = true
                isQuoteMark(text, i) || startsSingle(i) -> {
                    close = partner(c)
                    out.append('…')
                }
                else -> out.append(c)
            }
        }

        // Whether [text] at [i] opens a single quotation: a mark that may, with one after it to
        // close it.
        private fun startsSingle(i: Int): Boolean = opensSingle(text, i) && closer(i + 1)

        // Whether [text] at [i] closes the single quotation open: a mark that may, unless it may
        // be a plural's apostrophe and another mark after it may close the quotation.
        private fun endsSingle(i: Int): Boolean = closesSingle(text, i) && !(plural(text, i) && closer(i + 1))

        // Whether a mark that may close a single quotation comes from [from] on, before the next
        // blank line: in [text], else, with no blank line to its end, in what [beyond] stands for.
        // [from] is the character after the one read, so it only grows ([Lookahead]).
        private fun closer(from: Int): Boolean = ahead.closes(from) || (ahead.toEnd && beyond?.invoke() == true)
    }

    /**
     * Whether a mark that may close a single quotation comes in [text] from a place on, before the
     * next blank line ([closes]), asked from places that only grow: what was found ahead of one place
     * holds for the next until it is passed, so each part of the text is looked at once (#1258).
     */
    internal class Lookahead(
        private val text: String,
    ) {
        // The first blank line, and the first mark that may close a single quotation before it,
        // from where [closes] last looked (each the blank line's place when there is none); -1
        // before it first looks.
        private var blank = -1
        private var closing = -1
        private var last = 0

        /** Whether no blank line comes between where [closes] last looked and the end of [text]. */
        val toEnd: Boolean
            get() = blank == text.length

        /** Whether a mark that may close a single quotation comes from [from] on, before a blank line. */
        fun closes(from: Int): Boolean {
            require(from >= last) { "$from is before $last, where it last looked" }
            last = from
            if (blank < from) {
                var k = from
                while (k < text.length && !blankLineAt(text, k)) k++
                blank = k
            }
            if (closing < from) {
                var k = from
                while (k < blank && !closesSingle(text, k)) k++
                closing = k
            }
            return closing < blank
        }
    }
}
