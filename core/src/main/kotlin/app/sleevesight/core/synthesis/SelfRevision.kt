package app.sleevesight.core.synthesis

import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import app.sleevesight.core.text.trimJs

/**
 * A sign that a reply still revises itself in the text a card would show (#798): the model's
 * draft, its comment on it, and the insight again, left as written because [SelfCorrection] could
 * not take the draft out (the comment cites a line, or shares its line with the text). Such a reply
 * is not shown as written: when what follows the last sign is usable ([salvage]), that alone is
 * shown and kept, the rest taken out as [SelfCorrection]'s draft is; else `InsightWriter` asks once
 * more, and shows the error state if the second reply has no usable part either. Read on what the
 * card would show, after [SelfCorrection], so a correction that code takes out costs nothing more.
 *
 * Narrow on purpose, so an insight's own text never fails: what the text quotes (`"..."`, `“...”`,
 * single quotations as [Quotations] reads them) or sets in emphasis (`*...*`, `_..._`, a title's or
 * a lyric's) is not read for the first three signs, and the words they look for are lower case
 * where a title would capitalise them (`I Will Survive`, Monk's `Evidence`).
 */
enum class SelfRevision(
    /** The sign's name in the diagnostics log. */
    val label: String,
) {
    /** A sentence opens as only an aside does: `Wait,`, `Oops,`, `Hmm,`, `On second thought`, `Correction:`. */
    ASIDE("aside"),

    /** The first person corrects or withdraws its own text: "I need to correct that", "I'll keep only". */
    FIRST_PERSON("first person"),

    /** The text speaks of the evidence as such: "the evidence says", "supported by the evidence". */
    EVIDENCE("talk of the evidence"),

    /** A later sentence restates the opening one: the insight written a second time. */
    REPEATED_OPENING("opening repeated"),
    ;

    /**
     * A revision found in a text: its [sign] (the strongest found: an aside before the first person,
     * before talk of the evidence, before a repeated opening); what comes up to the end of the last
     * sentence with a sign, or its header, or before the opening's second occurrence ([withdrawn]);
     * and what follows it ([rest]), each trimmed, either one possibly empty.
     */
    data class Found(
        val sign: SelfRevision,
        val withdrawn: String,
        val rest: String,
    )

    // A sentence's sign, and where what is kept after it starts.
    private class Mark(
        val sign: SelfRevision,
        val cut: Int,
    )

    companion object {
        /**
         * The first sign of revision in [text], what a card would show ([GroundedText.text]), or
         * null when it has none.
         *
         * - [ASIDE]: a sentence, any opening bracket or space aside, starts with one of [ASIDES],
         *   or with one set as a header in emphasis ([HEADER]: `**Correction:**`, `*Wait,*`,
         *   `_Update:_`; read before emphasis is taken out, and only when the emphasis holds the
         *   aside alone, so `*Wait, Let Me Fix It*`, a title, is none), or breaks off for one
         *   ([BREAK_OFF]: "It is the opening song on... no, that is not ...").
         * - [FIRST_PERSON]: a sentence has `I`, `I'll`, `I need to`, `let me` and so on before a word
         *   of revision ([FIRST_PERSON_REVISES]: correct, revise, rewrite, fix, drop, remove, cut,
         *   keep only, stick to), or speaks of its own earlier draft, reply or answer.
         * - [EVIDENCE]: a sentence speaks of the evidence as what the reply is checked against
         *   ([EVIDENCE_TALK]: "the evidence says that", "not supported by the evidence", "the
         *   evidence lines", "my citations"). A stated gap ("The evidence doesn't say more about this
         *   recording.") is no sign: the prompt asks against it, but it reads as prose, and a
         *   second call for it would often end in the error state instead of a fair insight.
         * - [REPEATED_OPENING]: a later sentence, of at least [REPEAT_MIN_WORDS] words, is the
         *   opening sentence again: the same words in the same order, case, punctuation and
         *   citations aside. Quotations are read here too: the insight written twice repeats its
         *   quoted title. Only the same words: two sentences that share most of theirs may state two
         *   facts ("The studio version was recorded at Abbey Road in 1965. The live version was
         *   recorded at Abbey Road in 1966."), and a restatement with words changed is mostly found
         *   by the comment before it.
         *
         * The cost: a lyric or a title written without quotation marks or emphasis that opens with
         * `Wait,` or says "let me fix", and an insight that writes its opening sentence twice, are
         * read as revision; the reply is then cut, or asked for once more ([find], [salvage]).
         */
        fun of(text: String): SelfRevision? = find(text)?.sign

        /**
         * The revision in [text] ([of]), with what follows the last sentence that shows a sign
         * ([Found.rest]): after an aside's, a first-person correction's or the evidence talk's
         * sentence, after a header (`**Correction:**`, not the sentence it opens), and from the
         * opening sentence's second occurrence on. Null when [text] has no sign.
         */
        fun find(text: String): Found? {
            val whole = text.trimJs()
            val quotations = Quotations.of(whole)
            // Emphasis set aside character for character, so the positions of what is read hold.
            val read = EMPHASIS.replace(quotations.outside) { "…".repeat(it.value.length) }
            // Not found as split (none seen): every sign still counts, with nothing to keep after it.
            val spans = spans(whole) ?: listOf(whole.indices)
            val opening = words(spans.firstOrNull()?.let { whole.slice(it) }.orEmpty())
            val marks =
                spans.mapIndexedNotNull { index, span ->
                    val repeats = index > 0 && opening.size >= REPEAT_MIN_WORDS && words(whole.slice(span)) == opening
                    mark(whole, span, read, quotations, repeats)
                }
            return marks.lastOrNull()?.let { last ->
                val sign = marks.minBy { it.sign.ordinal }.sign
                Found(sign, whole.substring(0, last.cut).trimJs(), whole.substring(last.cut).trimJs())
            }
        }

        // The sign [span] of [whole] shows, read as [read] (quotations and emphasis set aside), with
        // where what is kept after it starts; null for none. [repeats] when it is the opening again.
        private fun mark(
            whole: String,
            span: IntRange,
            read: String,
            quotations: Quotations,
            repeats: Boolean,
        ): Mark? {
            val raw = whole.slice(span)
            val bare = part(read, quotations.sources, span).lead()
            val header = HEADER.containsMatchIn(part(quotations.outside, quotations.sources, span).lead())
            val sign =
                when {
                    header || ASIDES.containsMatchIn(bare) || BREAK_OFF.containsMatchIn(bare) -> ASIDE
                    FIRST_PERSON_REVISES.containsMatchIn(bare) -> FIRST_PERSON
                    EVIDENCE_TALK.containsMatchIn(bare) -> EVIDENCE
                    repeats -> REPEATED_OPENING
                    else -> null
                }
            val cut =
                when {
                    sign == REPEATED_OPENING -> span.first
                    header -> headerEnd(raw)?.let { span.first + it } ?: (span.last + 1)
                    else -> span.last + 1
                }
            return sign?.let { Mark(it, cut) }
        }

        /**
         * [grounded] cut down to [found]'s [Found.rest], checked against [ids]: what the card shows
         * of a reply that revised itself, with the cut part added to what was [GroundedText.withdrawn].
         * Null when nothing usable is left: no text, a correction or a sign of revision in it still,
         * or a sentence the grounding check finds uncited or citing a line that is not there.
         */
        fun salvage(
            grounded: GroundedText,
            found: Found,
            ids: Set<String>,
        ): GroundedText? =
            found.rest
                .takeIf { it.isNotEmpty() && SelfCorrection.of(it) == null && find(it) == null }
                ?.let { GroundingCheck.check(it, ids, grounded.allowModel) as? InsightReply.Written }
                ?.grounded
                ?.takeIf { it.paragraphs.isNotEmpty() && it.counts.uncited == 0 && it.counts.unknown == 0 }
                ?.copy(withdrawn = listOfNotNull(grounded.withdrawn, found.withdrawn).joinToString("\n\n"))

        // The characters of [read] that came from [span] of the text ([sources], `Quotations.sources`).
        private fun part(
            read: String,
            sources: IntArray,
            span: IntRange,
        ): String {
            // [sources] rises with each character, so the part is one run of [read].
            fun at(position: Int): Int =
                sources.binarySearch(position, 0, read.length).let { if (it >= 0) it else -it - 1 }
            return read.substring(at(span.first), at(span.last + 1))
        }

        // Where, in [raw], its header ends; null when the text does not hold it as read.
        private fun headerEnd(raw: String): Int? {
            val lead = raw.length - raw.lead().length
            return HEADER.find(raw.substring(lead))?.let { lead + it.range.last + 1 }
        }

        // [this] without what a sentence may open with before its aside: brackets and spaces.
        private fun String.lead(): String = trimStart { it in "([ " }

        // The words of [text], lower case, in order, without citations.
        private fun words(text: String): List<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { it.value.lowercase() }
                .toList()

        /** How only an aside opens a sentence, with its case. */
        private val ASIDES =
            Regex(
                "^(?:(?:Wait|Oops|Whoops|Hmm+)\\s*[,.!:—–-]|On second thought\\b|" +
                    "Correction\\s*[:,—–-]|(?:Update|Edit)\\s*:|Scratch that\\b)",
            )

        /**
         * An aside set as a header in emphasis, the emphasis holding it alone: its stop inside the
         * marks (`*Wait,*`, `**Correction:**`) or a colon or dash after them (`**Correction**:`).
         */
        private val HEADER =
            Regex(
                "^(?:\\*{1,2}|_{1,2})(?:Wait|Oops|Whoops|Hmm+|Correction|Corrected|Update|Edit|Revision|" +
                    "Revised|On second thought|Scratch that)\\s*" +
                    "(?:[,.:!—–-]\\s*(?:\\*{1,2}|_{1,2})|(?:\\*{1,2}|_{1,2})\\s*[:—–-])",
            )

        /** An aside that breaks off a sentence: three stops, then `no,`, `wait,` or `oops,`. */
        private val BREAK_OFF = Regex("\\.\\.\\.\\s*(?:[Nn]o|[Ww]ait|[Oo]ops)\\s*[,.!—–-]")

        /** The first person correcting or withdrawing what it wrote, or speaking of its own draft. */
        private val FIRST_PERSON_REVISES =
            Regex(
                "\\b(?:(?:I|I'll|I’ll|I will|I should|I need to|I must|I have to|I'm going to|I’m going to|" +
                    "[Ll]et me)\\s+(?:\\w+\\s+)?(?:correct|revise|rewrite|fix|redo|drop|remove|cut|" +
                    "keep only|stick to|retract)\\b|" +
                    "[Mm]y (?:previous|earlier|first|last) (?:draft|reply|answer|version|sentence)s?\\b)",
            )

        /**
         * The evidence or the sources spoken of as what the reply was checked against, as a
         * correction does: what they say, what they do not support. Not a stated gap ("the evidence
         * doesn't say more"), which the prompt asks against but which reads as prose.
         */
        private val EVIDENCE_TALK =
            Regex(
                "\\b[Tt]he (?:evidence|sources?) (?:says?|shows?|states?) (?:that|the)\\b|" +
                    "(?:\\bnot|n't|n’t|\\bun)\\s*supported by the (?:evidence|sources)\\b|" +
                    "\\bevidence lines?\\b|\\b[Mm]y citations?\\b",
            )

        /** A span in emphasis, a title's or a lyric's. */
        private val EMPHASIS = Regex("\\*[^*\\n]+\\*|_[^_\\n]+_")

        /** The fewest words a sentence needs to count as the opening written again. */
        private const val REPEAT_MIN_WORDS = 6

        /** A word: letters and digits, with any apostrophes inside it. */
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}

// Where each sentence of [text] is, paragraph by paragraph, as the grounding check splits
// them; null when a sentence is not found as split.
private fun spans(text: String): List<IntRange>? {
    val spans = mutableListOf<IntRange>()
    var start = 0
    for (paragraphEnd in PARAGRAPH_BREAK.findAll(text).map { it.range.first } + text.length) {
        var cursor = start
        for (sentence in GroundingCheck.splitSentences(text.substring(start, paragraphEnd))) {
            val span = locate(text, sentence, cursor, paragraphEnd) ?: return null
            spans += span
            cursor = span.last + 1
        }
        start = PARAGRAPH_BREAK
            .find(text, paragraphEnd)
            ?.range
            ?.last
            ?.plus(1) ?: text.length
    }
    return spans
}

// Where [sentence], split with its spaces and tabs as one space, is in [text] from [from] on,
// before [end]; null when it is not.
private fun locate(
    text: String,
    sentence: String,
    from: Int,
    end: Int,
): IntRange? {
    var i = from
    while (i < end && text[i].isWhitespace()) i++
    val first = i
    for (c in sentence) {
        i =
            when {
                c == ' ' && blankAt(text, i, end) -> blanksAfter(text, i, end)
                i < end && text[i] == c -> i + 1
                else -> return null
            }
    }
    return (first until i).takeIf { i > first }
}

// Whether [text] has a space or a tab at [i], before [end].
private fun blankAt(
    text: String,
    i: Int,
    end: Int,
): Boolean = i < end && (text[i] == ' ' || text[i] == '\t')

// Where the spaces and tabs of [text] from [i] on end, at [end] at the latest.
private fun blanksAfter(
    text: String,
    i: Int,
    end: Int,
): Int {
    var j = i
    while (blankAt(text, j, end)) j++
    return j
}

/** A blank line between paragraphs, as the grounding check splits them. */
private val PARAGRAPH_BREAK = Regex("\\n${JS_WHITESPACE_CLASS}*\\n")
