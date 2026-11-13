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
         * insight's own text is never cut. Only the reply's end is read, and a note is taken out
         * only when text (a letter or a digit, not marks alone) is left before it, so a reply that
         * is a note alone is left as written. Taken out, again and again while one is found at the
         * new end:
         *
         * - **A bracketed note** that ends the reply: square brackets that are no citation, no
         *   section's cue ([SECTION]: `[Drop]`, `[Chorus 2]`), and hold a working note's words
         *   ([NOTE_WORDS]: omit, not usable, uncited, unsupported, no evidence; drop, skip or
         *   exclude only with what they act on, `drop it`). Emphasis around the note alone
         *   (`**[omit]**`) goes with it; closing marks after it with none before it close the
         *   prose's emphasis and are kept (`*… quote it. [Not usable; omit]*`).
         * - **A last sentence**, split as the grounding check splits a reply, that cites nothing, is
         *   no stated gap ([isHedge]: "Little else is documented.", the fixed
         *   sentence of a song whose sources only list it), sits in no quotation opened before it,
         *   and, read without what it quotes (`"..."`, `“...”`, single quotations as [Quotations]
         *   reads them) and without emphasised titles or lyrics (an emphasis with words of the
         *   sentence around it, whatever its punctuation; one that is the whole sentence is read),
         *   holds a bracketed note, says the evidence lacks something ([EVIDENCE_TALK]: "not in the
         *   evidence", followed by the clause's end or its going on, not by a noun, as in "the
         *   evidence locker"; "the evidence doesn't say", "the sources don't say", "my citations"),
         *   or speaks of its own work in the first person ([FIRST_PERSON]: "I can't quote", "I
         *   can't cite", "I'll omit"). The words are matched with their case where a title would
         *   capitalise them (Monk's *Evidence*, *I Can't Quote It*).
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
        // when no text (a letter or a digit, not marks alone) would be left.
        private fun noteAtEnd(text: String): Pair<String, String>? =
            (withoutBracketNote(text) ?: withoutLastSentence(text))?.takeIf { CONTENT.containsMatchIn(it.first) }

        // [text] without a bracketed note at its end, and the note; null for none. Emphasis that wraps
        // the note alone (`**[omit]**`) goes with it; closing marks after it with none opening before
        // it close the prose's emphasis and are kept (`*… quote it. [omit]*`); other marks are left
        // as written, and so is the note.
        private fun withoutBracketNote(text: String): Pair<String, String>? {
            val match = TRAILING_BRACKET.find(text) ?: return null
            val (opening, inside, closing) = match.destructured
            val kept =
                when {
                    !isNote(inside) -> null
                    opening.isEmpty() -> closing
                    closing.startsWith(opening) -> closing.removePrefix(opening)
                    else -> null
                }
            return kept?.let {
                val before = text.substring(0, match.range.first).trimJs()
                (before + it).trimJs() to "$opening[$inside]$opening"
            }
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
        // emphasised titles or lyrics; null when a quotation in it is left open, or closes with none
        // open. An emphasis is read only when it is the whole sentence, bracketed notes and
        // punctuation aside (`*One site … so I can't quote it.*`); one with words of the sentence
        // around it is a title or a lyric, whatever its punctuation (`repeats *the evidence shows
        // you lied...* over two chords`).
        private fun readOf(sentence: String): String? {
            val read = Quotations.of(sentence).takeIf { !it.open && !it.stray }?.outside
            return read?.let {
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

        /** The evidence or the sources spoken of as what the reply is written from. */
        private val EVIDENCE_TALK =
            Regex(
                "(?:\\bnot|n't|n’t)\\s+(?:in|among|part of|found in|given in|supported by|backed by|" +
                    "covered by)\\s+(?:the|my)\\s+evidence$EVIDENCE_ENDS|" +
                    "\\b[Tt]he evidence\\s+(?:does(?:n't|n’t| not)|did(?:n't|n’t| not))\\s+" +
                    "(?:say|mention|give|state|cover|include|support|confirm|name|quote|show)\\b|" +
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

        /** A bracketed note at the text's end, with any emphasis marks just before and after it. */
        private val TRAILING_BRACKET = Regex("([*_]*)\\[([^\\[\\]\\n]*)\\]([*_]*)$")

        /** Square brackets and what they hold, on one line. */
        private val BRACKET = Regex("\\[([^\\[\\]\\n]*)\\]")

        /** An emphasis, `*...*`, `**...**`, `_..._` or `__...__`, on one line. */
        private val EMPHASIS_SPAN = Regex("(\\*{1,2}|_{1,2})([^*_\\n]+?)\\1")

        /** A letter or a digit: text, not marks alone. */
        private val CONTENT = Regex("[\\p{L}\\p{N}]")

        /**
         * What follows "the evidence" when it is the reply's evidence: the sentence's punctuation or
         * end, a bracket, or a word that goes on with the clause (`…, so I can't quote it`); not a
         * noun it qualifies (`the evidence locker`).
         */
        private const val EVIDENCE_ENDS =
            "(?=[\\s*_)]*(?:[,.;:!?\\[]|$)|\\s+(?:so|and|but|which|either|here)\\b)"

        private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n")
        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
        private val CITATION_INSIDE = Regex("[A-Z]{1,2}[0-9]*")
    }
}
