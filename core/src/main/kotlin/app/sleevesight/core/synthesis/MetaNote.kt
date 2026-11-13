package app.sleevesight.core.synthesis

import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import app.sleevesight.core.text.isJsWhitespace
import app.sleevesight.core.text.trimJs

/**
 * A note the model left at the end of its reply about its own evidence or work, not about the
 * music (#1207): a sentence such as "One lyrics site reads it as a farewell, but the page's wording
 * is not in the evidence, so I can't quote it.", or a bracketed working note, "[Not usable; omit]".
 * The prompt asks for the insight text only, yet a model that decides not to use a line sometimes
 * says so after the insight. [kept] is the reply without it, what is shown and checked; the note is
 * [withdrawn], for the developer details and the reports, as a correction taken out is
 * ([SelfCorrection], #597). Taken out in code, with no further call: what is left is the insight as
 * written, and the note was uncited, so the check would have flagged it anyway.
 */
data class MetaNote(
    val kept: String,
    val withdrawn: String,
) {
    companion object {
        /**
         * The note at the end of [reply], or null when it ends with none. Narrow on purpose, so an
         * insight's own text is never cut: when in doubt, the text stays. Only the reply's end is
         * read; a note is never taken out from inside an emphasis or a quotation that opened before
         * it (read over the whole paragraph, so a lyric in emphasis over two sentences is one), and
         * only when text (a letter or a digit, not marks alone) is left before it, so a reply that
         * is a note alone is left as written. Taken out, again and again while one is found at the
         * new end:
         *
         * - **A bracketed note** that ends the reply: square brackets that are no citation, no
         *   section's cue ([SECTION]: `[Drop]`, `[Chorus 2]`), and hold a working note's words
         *   ([NOTE_WORDS]: omit, not usable, uncited, unsupported, no evidence; drop, skip or
         *   exclude only with what they act on, `drop it`). Emphasis around the note alone
         *   (`**[omit]**`) goes with it; with any other marks around it (`[omit]*`), it stays.
         * - **A last sentence**, split as the grounding check splits a reply, that cites nothing, is
         *   no stated gap ([isHedge], or [STATED_GAP]: "Little else is documented in my sources."),
         *   and, read without what it quotes (`"..."`, `“...”`, single quotations as [Quotations]
         *   reads them) and without emphasised titles or lyrics inside it (an emphasis with words of
         *   the sentence around it; one that is the whole sentence is read), is both in the first
         *   person ([FIRST_PERSON]: I'm, I'll, I can't, I have, my; the pronoun with its usual
         *   company, so a Roman numeral or a title, `Part I`, `My Way`, is none) and names the
         *   model's own material ([MATERIAL]: evidence, sources, citation, cite, quote), as the
         *   phone's "…, so I can't quote it." does. An insight is written in the third person;
         *   the first person in it is a lyric's, which is quoted or set in emphasis, or the
         *   model's own.
         *
         * The cost: a note in the middle of the reply, or one not in the first person ("The
         * sources don't say who produced it."), stays, flagged as uncited; a lyric in the first
         * person that names a quote or the evidence, written without quotation marks or emphasis,
         * uncited, at the reply's very end, would be taken out.
         */
        fun of(reply: String): MetaNote? {
            val whole = reply.trimJs()
            var text = whole
            val notes = ArrayDeque<String>()
            var next = noteAtEnd(text)
            while (next != null) {
                text = next.first
                notes.addFirst(next.second)
                next = if (notes.size < MAX_NOTES) noteAtEnd(text) else null
            }
            return if (notes.isEmpty()) null else MetaNote(kept = text, withdrawn = notes.joinToString(" "))
        }

        // [text] without the note at its end, a bracketed one first, and the note; null for none, or
        // when no text (a letter or a digit, not marks alone) would be left.
        private fun noteAtEnd(text: String): Pair<String, String>? =
            (withoutBracketNote(text) ?: withoutLastSentence(text))?.takeIf { CONTENT.containsMatchIn(it.first) }

        // [text] without a bracketed note at its end, and the note; null for none. Emphasis that wraps
        // the note alone (`**[omit]**`) goes with it; with any other marks around it (closing marks
        // with none opening before it, `[omit]*`), the note is left as written. Never from inside an
        // emphasis or a quotation opened before it.
        private fun withoutBracketNote(text: String): Pair<String, String>? {
            val bracket = trailingBracket(text) ?: return null
            val (opening, inside, closing) = bracket
            val at = bracket.at
            val kept =
                when {
                    !isNote(inside) || enclosed(text, at) -> null
                    opening.isEmpty() -> closing.takeIf { it.isEmpty() }
                    closing.startsWith(opening) -> closing.removePrefix(opening)
                    else -> null
                }
            return kept?.let {
                val before = text.substring(0, at).trimJs()
                (before + it).trimJs() to "$opening[$inside]$opening"
            }
        }

        // The bracketed note at [text]'s end, with any emphasis marks just before and after it; null for
        // none. Read back from the very end (unlike a pattern's `$`, which also matches before a last line
        // break) rather than by a pattern, which took time growing with the square of a long run of `*`
        // or `_` (#1247).
        private fun trailingBracket(text: String): TrailingBracket? {
            val end = text.trimEnd('*', '_').length
            val open = if (text.getOrNull(end - 1) == ']') text.lastIndexOf('[', end - 2) else -1
            val inside = if (open < 0) null else text.substring(open + 1, end - 1)
            return inside?.takeUnless { held -> held.any { it == ']' || it == '\n' } }?.let {
                val at = text.substring(0, open).trimEnd('*', '_').length
                TrailingBracket(text.substring(at, open), it, text.substring(end), at)
            }
        }

        // A bracketed note's marks before it, what it holds, its marks after it, and where it starts.
        private data class TrailingBracket(
            val opening: String,
            val inside: String,
            val closing: String,
            val at: Int,
        )

        // [text] without its last sentence when that is a note, and the sentence; null when it is not.
        private fun withoutLastSentence(text: String): Pair<String, String>? {
            val start = paragraphStart(text, text.length)
            val last = sentenceSpans(text.substring(start)).lastOrNull() ?: return null
            val at = start + last.first
            val sentence = text.substring(at, start + last.last + 1)
            val apart =
                !enclosed(text, at) &&
                    !CITATION.containsMatchIn(sentence) &&
                    !isHedge(sentence) &&
                    !STATED_GAP.containsMatchIn(sentence)
            val read = if (apart) readOf(sentence) else null
            val says = read != null && FIRST_PERSON.containsMatchIn(read) && MATERIAL.containsMatchIn(read)
            return if (says) text.substring(0, at).trimJs() to sentence.trimJs() else null
        }

        // Whether [at] in [text] is inside a quotation or an emphasis opened before it in its
        // paragraph, and still open there.
        private fun enclosed(
            text: String,
            at: Int,
        ): Boolean = Quotations.of(text, end = at).open || emphasisOpen(text, paragraphStart(text, at), at)

        // Where the paragraph that holds [at] in [text] starts: after the last blank line before it.
        private fun paragraphStart(
            text: String,
            at: Int,
        ): Int =
            PARAGRAPH_BREAK
                .findAll(text.substring(0, at))
                .lastOrNull()
                ?.range
                ?.last
                ?.plus(1) ?: 0

        // Whether an emphasis opened in [text] from [from] is still open at [end]: each run of `*` or
        // `_` that is a mark (not inside a word, as in `snake_case`, nor alone between spaces) closes
        // the last one opened with the same run, else opens one.
        private fun emphasisOpen(
            text: String,
            from: Int,
            end: Int,
        ): Boolean {
            val open = ArrayDeque<String>()
            for (run in EMPHASIS_RUN.findAll(text.substring(0, end), from)) {
                val before = text.getOrNull(run.range.first - 1)
                val after = text.getOrNull(run.range.last + 1)
                val inWord = before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
                val alone = (before == null || isJsWhitespace(before)) && (after == null || isJsWhitespace(after))
                when {
                    inWord || alone -> Unit
                    open.lastOrNull() == run.value -> open.removeLast()
                    else -> open.addLast(run.value)
                }
            }
            return open.isNotEmpty()
        }

        // [sentence] as read: without what it quotes, each quotation an ellipsis, and without its
        // emphasised titles or lyrics; null when a quotation in it is left open, or closes with none
        // open. An emphasis is read only when it is the whole sentence, bracketed notes and
        // punctuation aside (`*One site … so I can't quote it.*`); one with words of the sentence
        // around it is a title or a lyric, whatever its punctuation. Null too past [MAX_SPANS]
        // emphases, as each is weighed against the whole sentence (#1247): no note's sentence has so many.
        private fun readOf(sentence: String): String? {
            val read = Quotations.of(sentence).takeIf { !it.open && !it.stray }?.outside
            return read?.takeIf { EMPHASIS_SPAN.findAll(it).take(MAX_SPANS + 1).count() <= MAX_SPANS }?.let {
                EMPHASIS_SPAN.replace(it) { span ->
                    val around = it.removeRange(span.range).replace(BRACKET, " ")
                    if (CONTENT.containsMatchIn(around)) "…" else span.value
                }
            }
        }

        // Whether [inside], what square brackets hold, is a working note: no citation, no section's
        // cue (`[Drop]`, `[Chorus 2]`), and a working note's words.
        private fun isNote(inside: String): Boolean =
            !CITATION_INSIDE.matches(inside) && !SECTION.matches(inside.trim()) && NOTE_WORDS.containsMatchIn(inside)

        /**
         * A working note's words, in any case: what the model does with a line, or what it lacks.
         * Words that also name a song's part or a move in it (drop, skip) count only with what they
         * act on (`drop it`, `skip this`, `exclude the line`).
         */
        private val NOTE_WORDS =
            Regex(
                "\\b(?:omit(?:ted)?|not usable|unusable|not used|unused|not cited|uncited|unsupported|" +
                    "not supported|no evidence|not in (?:the )?evidence|do not use|don't use|" +
                    "(?:drop|skip|exclude)(?:ped|d)?\\s+(?:it|this|that|the (?:line|source|claim|sentence)))\\b",
                RegexOption.IGNORE_CASE,
            )

        /** A section's cue, as lyrics and track listings mark them: `[Drop]`, `[Chorus 2]`, `[Verse: Jay]`. */
        private val SECTION =
            Regex(
                "(?:intro|verse|pre-chorus|chorus|post-chorus|refrain|hook|bridge|drop|build(?:-up)?|break|" +
                    "breakdown|interlude|solo|instrumental|outro|coda|skit)(?:\\s*\\d+)?(?:\\s*:.*)?",
                RegexOption.IGNORE_CASE,
            )

        /**
         * The first person, the model speaking of itself: `I` with a contraction (`I'm`, `I'll`,
         * `I've`, `I'd`) or before a verb it opens a clause with (`I can't`, `I could`, `I will`,
         * `I have`, `I did`, `I am`), or `my` in lower case; not `I` alone, a Roman numeral as often
         * as a pronoun (`Part I`, `Led Zeppelin I`), nor `My` or `I` opening a title (`My Way`,
         * `I Wanna Be Your Dog`).
         */
        private val FIRST_PERSON =
            Regex(
                "\\bI['’](?:m|ll|ve|d)\\b|" +
                    "\\bI (?:can(?:no|['’])t|could(?:n['’]t)?|will|won['’]t|have|had|did(?:n['’]t)?|" +
                    "do(?:n['’]t)?|am|was)\\b|\\bmy\\b",
            )

        /**
         * A stated gap in the first person, which stays as the grounding check's hedges do: "Little
         * else is documented in my sources.", "I found no further details in the evidence."
         */
        private val STATED_GAP =
            Regex(
                "\\b(?:little|nothing|not much)(?: else| more| further)? is (?:known|documented)\\b|" +
                    "\\bno (?:further |other |more )?(?:details|information)\\b",
                RegexOption.IGNORE_CASE,
            )

        /**
         * The model's own material, lower case where a title would capitalise it (Monk's
         * *Evidence*): its evidence, its sources and their lines, and citing or quoting them.
         */
        private val MATERIAL =
            Regex("\\b(?:evidence|sources?|source lines?|citations?|cite[sd]?|citing|quote[sd]?|quoting)\\b")

        /**
         * The most notes taken out of one reply, and the most emphases a note's sentence is read with:
         * each costs a pass over the whole text, so a reply of marks alone would take time growing
         * with the square of its length (#1247). Beyond them, the text stays.
         */
        private const val MAX_NOTES = 8
        private const val MAX_SPANS = 64

        /** A run of one emphasis mark. */
        private val EMPHASIS_RUN = Regex("\\*+|_+")

        /** Square brackets and what they hold, on one line. */
        private val BRACKET = Regex("\\[([^\\[\\]\\n]*)\\]")

        /** An emphasis, `*...*`, `**...**`, `_..._` or `__...__`, on one line. */
        private val EMPHASIS_SPAN = Regex("(\\*{1,2}|_{1,2})([^*_\\n]+?)\\1")

        /** A letter or a digit: text, not marks alone. */
        private val CONTENT = Regex("[\\p{L}\\p{N}]")

        private val PARAGRAPH_BREAK = Regex("\\n$JS_WHITESPACE_CLASS*\\n")
        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
        private val CITATION_INSIDE = Regex("[A-Z]{1,2}[0-9]*")
    }
}
