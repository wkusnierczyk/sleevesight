package app.sleevesight.core.synthesis

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
         * insight's own text is never cut. Only the reply's end is read, and only what the reply
         * still has before it is kept, so a reply that is a note alone is left as written. Taken
         * out, again and again while one is found at the new end:
         *
         * - **A bracketed note** that ends the reply, any closing emphasis marks after it kept
         *   (`… quote it. [Not usable; omit]*`): square brackets that are no citation and hold a
         *   word of the model's work ([NOTE_WORDS]: omit, not usable, drop, skip, uncited,
         *   unsupported, no evidence).
         * - **A last sentence**, split as the grounding check splits a reply, that cites nothing, is
         *   no stated gap ([isHedge]: "Little else is documented.", the fixed
         *   sentence of a song whose sources only list it), sits in no quotation opened before it,
         *   and, read without what it quotes (`"..."`, `“...”`, single quotations as [Quotations]
         *   reads them) and without emphasised titles or lyrics (`*...*` that ends no sentence),
         *   holds a bracketed note, talks of the evidence as such ([EVIDENCE_TALK]: "not in the
         *   evidence", "the evidence doesn't say", "the sources don't say", "my citations"), or of
         *   its own work in the first person ([FIRST_PERSON]: "I can't quote", "I can't cite",
         *   "I'll omit"). The words are matched with their case where a title would capitalise
         *   them (Monk's *Evidence*, *I Can't Quote It*).
         *
         * The cost: such a note in the middle of the reply, or worded otherwise, stays, flagged as
         * uncited; a lyric set in emphasis as a whole sentence of its own (`*I can't quote it.*`),
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
                next = noteAtEnd(text)
            }
            return if (notes.isEmpty()) null else MetaNote(kept = text, withdrawn = notes.joinToString(" "))
        }

        // [text] without the note at its end, a bracketed one first, and the note; null for none, or
        // when nothing would be left.
        private fun noteAtEnd(text: String): Pair<String, String>? =
            (withoutBracketNote(text) ?: withoutLastSentence(text))?.takeIf { it.first.isNotEmpty() }

        // [text] without a bracketed note at its end, any emphasis marks after it kept, and the note;
        // null for none.
        private fun withoutBracketNote(text: String): Pair<String, String>? =
            TRAILING_BRACKET.find(text)?.takeIf { isNote(it.groupValues[1]) }?.let { match ->
                val before = text.substring(0, match.range.first).trimJs()
                (before + match.groupValues[2]).trimJs() to "[${match.groupValues[1]}]"
            }

        // [text] without its last sentence when that is a note, and the sentence; null when it is not.
        private fun withoutLastSentence(text: String): Pair<String, String>? {
            val start =
                PARAGRAPH_BREAK
                    .findAll(text)
                    .lastOrNull()
                    ?.range
                    ?.last
                    ?.plus(1) ?: 0
            val paragraph = text.substring(start)
            val last = sentenceSpans(paragraph).lastOrNull() ?: return null
            val at = start + last.first
            val sentence = text.substring(at, start + last.last + 1)
            val apart = !Quotations.of(text, end = at).open && !CITATION.containsMatchIn(sentence) && !isHedge(sentence)
            val read = if (apart) readOf(sentence) else null
            val says =
                read != null &&
                    (
                        BRACKET.findAll(read).any { isNote(it.groupValues[1]) } ||
                            EVIDENCE_TALK.containsMatchIn(read) ||
                            FIRST_PERSON.containsMatchIn(read)
                    )
            return if (says) text.substring(0, at).trimJs() to sentence.trimJs() else null
        }

        // [sentence] as read: without what it quotes, each quotation an ellipsis, and without its
        // emphasised titles or lyrics (an emphasis that ends no sentence); null when a quotation in
        // it is left open, or closes with none open.
        private fun readOf(sentence: String): String? {
            val quotations = Quotations.of(sentence).takeIf { !it.open && !it.stray }
            return quotations?.let {
                EMPHASIS_SPAN.replace(it.outside) { span ->
                    if (STOP.containsMatchIn(span.groupValues[2])) span.value else "…"
                }
            }
        }

        // Whether [inside], what square brackets hold, is a working note: no citation, a word of one.
        private fun isNote(inside: String): Boolean =
            !CITATION_INSIDE.matches(inside) && NOTE_WORDS.containsMatchIn(inside)

        /** A working note's words, in any case: what the model does with a line, or what it lacks. */
        private val NOTE_WORDS =
            Regex(
                "\\b(?:omit(?:ted)?|not usable|unusable|not used|unused|drop(?:ped)?|skip(?:ped)?|" +
                    "exclude(?:d)?|not cited|uncited|unsupported|not supported|no evidence|not in evidence)\\b",
                RegexOption.IGNORE_CASE,
            )

        /** The evidence or the sources spoken of as what the reply is written from. */
        private val EVIDENCE_TALK =
            Regex(
                "\\b(?:in|from|by|among|within|beyond)\\s+(?:the|my|this)\\s+evidence\\b|" +
                    "\\b[Tt]he evidence\\s+(?:does(?:n't|n’t| not)|did(?:n't|n’t| not)|has(?:n't|n’t| not)|" +
                    "lacks|says|shows|gives|contains|includes|supports)\\b|" +
                    "\\bevidence lines?\\b|" +
                    "\\b[Tt]he sources?\\s+(?:do(?:es)?(?:n't|n’t| not)|did(?:n't|n’t| not))\\s+" +
                    "(?:say|mention|give|state|cover|include|support|confirm|name|quote)\\b|" +
                    "(?:\\bnot|n't|n’t)\\s+(?:supported by|backed by|in|among|found in|given in)\\s+" +
                    "(?:the|my)\\s+sources\\b|" +
                    "\\b[Mm]y (?:citations?|sources|evidence)\\b",
            )

        /** The first person on what it can or will do with a line: quote, cite, verify, omit. */
        private val FIRST_PERSON =
            Regex(
                "\\bI\\s+(?:can't|can’t|cannot|can not|couldn't|couldn’t|could not|won't|won’t|will not|" +
                    "am unable to|didn't|didn’t|did not|don't|don’t|do not)\\s+(?:quote|cite|verify|confirm)\\b|" +
                    "\\bI(?:'m|’m) unable to\\s+(?:quote|cite|verify|confirm)\\b|" +
                    "\\bI(?:'ll|’ll| will|'m|’m| am)\\s+(?:omit|omitting|leave out|leaving out|skip|skipping)\\b",
            )

        /** A bracketed note at the text's end, then any closing emphasis marks. */
        private val TRAILING_BRACKET = Regex("\\[([^\\[\\]\\n]*)\\]([*_]*)$")

        /** Square brackets and what they hold, on one line. */
        private val BRACKET = Regex("\\[([^\\[\\]\\n]*)\\]")

        /** An emphasis, `*...*`, `**...**`, `_..._` or `__...__`, on one line. */
        private val EMPHASIS_SPAN = Regex("(\\*{1,2}|_{1,2})([^*_\\n]+?)\\1")

        /** A stop in an emphasis, which then holds a sentence and is read. */
        private val STOP = Regex("[.!?]")

        private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n")
        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
        private val CITATION_INSIDE = Regex("[A-Z]{1,2}[0-9]*")
    }
}
