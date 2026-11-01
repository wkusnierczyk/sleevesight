package app.sleevesight.core.synthesis

import app.sleevesight.core.text.isJsWhitespace
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
 * Read on the reply as plain text ([PlainView]): Markdown's line marks, emphasis marks and backticks
 * set aside, so they hide no sign (`- **Correction:**`, `### Correction:`, a sentence in emphasis).
 * Narrow on purpose, so an insight's own text never fails: what the text quotes (`"..."`, `“...”`,
 * single quotations as [Quotations] reads them) or sets in emphasis as a title or a lyric (no
 * citation, no sentence's end: `*Wait, Let Me Fix It*`) is not read for the first three signs, and
 * the words they look for are lower case where a title would capitalise them (`I Will Survive`,
 * Monk's `Evidence`); a sign the model words otherwise is not found.
 */
enum class SelfRevision(
    /** The sign's name in the diagnostics log. */
    val label: String,
) {
    /**
     * A sentence opens as only an aside does (`Wait,`, `Oops,`, `Hmm,`, `On second thought`,
     * `Correction:`), with a label of one (`**Correction:**`, `Here is the corrected insight:`), or
     * breaks off for one (`... no,`).
     */
    ASIDE("aside"),

    /** The first person corrects or withdraws its own text: "I need to correct that", "I'll keep only". */
    FIRST_PERSON("first person"),

    /** The text speaks of the evidence as such: "the evidence says", "supported by the evidence". */
    EVIDENCE("talk of the evidence"),

    /** A later sentence is the opening one again: the insight written a second time. */
    REPEATED_OPENING("opening repeated"),
    ;

    /**
     * A revision found in a text: its [sign] (the strongest found: an aside before the first person,
     * before talk of the evidence, before a repeated opening); what comes up to the end of the last
     * sentence with a sign, or through its label, or before the opening's second occurrence
     * ([withdrawn]); and what follows it ([rest]), each trimmed, either one possibly empty.
     */
    data class Found(
        val sign: SelfRevision,
        val withdrawn: String,
        val rest: String,
    )

    // A sentence's sign, and where in the plain view what is kept after it starts.
    private class Mark(
        val sign: SelfRevision,
        val cut: Int,
    )

    companion object {
        /**
         * The first sign of revision in [text], what a card would show ([GroundedText.text]), or
         * null when it has none. Read sentence by sentence on its plain view ([PlainView]), split as
         * the grounding check splits a reply:
         *
         * - [ASIDE]: a sentence, any opening bracket or space aside, starts with one of [ASIDES] or
         *   a label ([LABEL]: `Correction:`, `Update:`, `Here is the corrected insight:`, `Corrected
         *   reply:`, the introductions `SelfCorrection` knows and their like), or with an emphasis
         *   that holds an aside alone (`*Wait,*`); or breaks off for one ([BREAK_OFF]: "on... no,").
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
         *   citations aside. Quotations and titles are read here too: the insight written twice
         *   repeats its quoted title. Only the same words: two sentences that share most of theirs
         *   may state two facts ("The studio version was recorded at Abbey Road in 1965. The live
         *   version was recorded at Abbey Road in 1966."), and a restatement with words changed is
         *   mostly found by the comment before it.
         *
         * The cost: a lyric or a title written without quotation marks or emphasis that opens with
         * `Wait,` or says "let me fix", and an insight that writes its opening sentence twice, are
         * read as revision; the reply is then cut, or asked for once more ([find], [salvage]).
         */
        fun of(text: String): SelfRevision? = find(text)?.sign

        /**
         * The sign in [partial], a reply still streaming, as the card would show it once
         * [SelfCorrection] took out what it can; null when it has none, or while a quotation or an
         * emphasis is open at its end, which may yet set aside what it holds.
         */
        fun ofPartial(partial: String): SelfRevision? {
            val shown = (SelfCorrection.of(partial)?.kept ?: partial).trimJs()
            val view = PlainView.of(shown)
            return if (view.open || Quotations.of(view.masked).open) null else find(shown)?.sign
        }

        /**
         * The revision in [text] ([of]), with what follows the last sentence that shows a sign
         * ([Found.rest]): after an aside's, a first-person correction's or the evidence talk's
         * sentence; after a label or an emphasised aside, not the sentence it opens
         * (`**Correction:** It came out in 1975.` keeps its sentence); and from the opening
         * sentence's second occurrence on. Null when [text] has no sign.
         */
        fun find(text: String): Found? {
            val whole = text.trimJs()
            val view = PlainView.of(whole)
            val quotations = Quotations.of(view.masked)
            val spans = view.sentences()
            val opening = words(spans.firstOrNull()?.let { view.text.slice(it) }.orEmpty())
            val marks =
                spans.mapIndexedNotNull { index, span ->
                    val repeats =
                        index > 0 && opening.size >= REPEAT_MIN_WORDS && words(view.text.slice(span)) == opening
                    mark(view, span, part(quotations, span).lead(), repeats)
                }
            return marks.lastOrNull()?.let { last ->
                val sign = marks.minBy { it.sign.ordinal }.sign
                // A cut at a sentence's start keeps the marks that open it (`*Sundown* came out`).
                val cut =
                    if (last.sign == REPEATED_OPENING) view.after(last.cut) else view.source(last.cut, whole.length)
                Found(sign, whole.substring(0, cut).trimJs(), balanced(whole.substring(cut).trimJs()))
            }
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

        // The sign [span] of [view] shows, [bare] being it as read (quotations and titles set aside,
        // its opening brackets and spaces too), with where what is kept after it starts; null for
        // none. [repeats] when it is the opening again.
        private fun mark(
            view: PlainView,
            span: IntRange,
            bare: String,
            repeats: Boolean,
        ): Mark? {
            val label = labelEnd(view, span, bare)
            val sign =
                when {
                    label != null || ASIDES.containsMatchIn(bare) || BREAK_OFF.containsMatchIn(bare) -> ASIDE
                    FIRST_PERSON_REVISES.containsMatchIn(bare) -> FIRST_PERSON
                    EVIDENCE_TALK.containsMatchIn(bare) -> EVIDENCE
                    repeats -> REPEATED_OPENING
                    else -> null
                }
            val cut =
                when {
                    sign == REPEATED_OPENING -> span.first
                    label != null -> label
                    else -> span.last + 1
                }
            return sign?.let { Mark(it, cut) }
        }

        // Where a label, or an aside in emphasis of its own, that opens [span] of [view] ends, [bare]
        // being the sentence as read: cut through it, its sentence kept. Null for none.
        private fun labelEnd(
            view: PlainView,
            span: IntRange,
            bare: String,
        ): Int? {
            val start = span.first + view.text.slice(span).let { it.length - it.lead().length }
            return LABEL.find(bare)?.let { start + it.range.last + 1 }
                ?: view.headers.firstOrNull { it.first == start }?.let { it.last + 1 }
        }

        // What of [span] is read: [quotations]' text from it ([Quotations.sources]).
        private fun part(
            quotations: Quotations,
            span: IntRange,
        ): String {
            val read = quotations.outside

            // The sources rise with each character, so the part is one run of what is read.
            fun at(position: Int): Int =
                quotations.sources.binarySearch(position, 0, read.length).let { if (it >= 0) it else -it - 1 }
            return read.substring(at(span.first), at(span.last + 1))
        }

        // [text] without a run of emphasis marks at its end that closes nothing in it, left by a cut
        // inside an emphasis (`**Correction: It came out in 1975 [MB1].**`).
        private fun balanced(text: String): String {
            var t = text
            for (mark in "*_") {
                val run = Regex("\\$mark+$").find(t) ?: continue
                if (Regex("\\$mark+").findAll(t).count() % 2 == 1) t = t.substring(0, run.range.first).trimJs()
            }
            return t
        }

        // [this] without what a sentence may open with before its aside: brackets and spaces.
        private fun String.lead(): String = trimStart { it in "([ " }

        // The words of [text], lower case, in order, without citations.
        private fun words(text: String): List<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { it.value.lowercase() }
                .toList()

        /** The words of an aside, as it may open a sentence or stand alone in emphasis. */
        internal const val ASIDE_WORDS: String =
            "Wait|Oops|Whoops|Hmm+|Correction|Corrected|Update|Edit|Revision|Revised|On second thought|Scratch that"

        /**
         * A label that opens a corrected version, up to its colon: an aside's word (`Correction:`,
         * `Update:`), or an introduction, those `SelfCorrection` knows ("Here is the corrected
         * insight:", "Corrected reply:") and their like ("Revised version:", "Here's the insight:").
         */
        internal val LABEL =
            Regex(
                "^(?:Correction|Corrected|Update|Edit|Revision|Revised|" +
                    "(?:Here(?: is|'s|’s) )?(?:[Tt]he |[Mm]y )?(?:[Cc]orrected|[Rr]evised|[Uu]pdated|[Ff]ixed) " +
                    "(?:insight|reply|version|answer|text)|" +
                    "Here(?: is|'s|’s) (?:the|my) (?:insight|reply|answer|version))\\s*:",
            )

        /** How only an aside opens a sentence, with its case. */
        private val ASIDES =
            Regex(
                "^(?:(?:Wait|Oops|Whoops|Hmm+)\\s*[,.!:—–-]|On second thought\\b|" +
                    "Correction\\s*[:,—–-]|(?:Update|Edit)\\s*:|Scratch that\\b)",
            )

        /** An aside that breaks off a sentence: three stops, then `no,`, `wait,` or `oops,`. */
        private val BREAK_OFF = Regex("\\.\\.\\.\\s*(?:[Nn]o|[Ww]ait|[Oo]ops)\\s*[,.!—–-]")

        /** The first person correcting or withdrawing what it wrote, or speaking of its own draft. */
        private val FIRST_PERSON_REVISES =
            Regex(
                "\\b(?:(?:I|(?:I'll|I’ll|I will|I should|I need to|I must|I have to|I'm going to|I’m going to|" +
                    "[Ll]et me)\\s+(?:\\w+\\s+)?)\\s*(?:correct|revise|rewrite|fix|redo|drop|remove|cut|" +
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

        /** The fewest words a sentence needs to count as the opening written again. */
        private const val REPEAT_MIN_WORDS = 6

        /** A word: letters and digits, with any apostrophes inside it. */
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}

/**
 * A reply as plain text (#798): its [text] without Markdown's marks (a line's heading, list or
 * quotation mark, `#`, `-`, `*`, `+`, `1.`, `>`; emphasis marks, `*`, `_`, `**`, `__`; backticks),
 * every whitespace character but a line break as a space, as the grounding check reads whitespace;
 * [sources], for each character of it, where in the reply it came from. [masked] is [text] with each
 * emphasised title or lyric set aside, character for character (`*Wait, Let Me Fix It*`), and
 * [headers] where an emphasis holds an aside or a label alone, as a header (`**Correction:**`,
 * `*Wait,*`, `**Corrected insight:**`, or `**Correction**:` with its colon after the marks).
 */
private class PlainView(
    val text: String,
    val masked: String,
    val sources: IntArray,
    val headers: List<IntRange>,
    val open: Boolean = false,
) {
    /** Where in the reply, just after the character before [at], a cut at [at] starts. */
    fun after(at: Int): Int = if (at == 0) 0 else sources[at - 1] + 1

    /** Where in the reply the character at [at] came from; [end] past the last. */
    fun source(
        at: Int,
        end: Int,
    ): Int = if (at < sources.size) sources[at] else end

    /** Where each sentence is, paragraph by paragraph, as the grounding check splits a reply. */
    fun sentences(): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var start = 0
        for (end in PARAGRAPH_BREAK.findAll(text).map { it.range.first } + text.length) {
            if (end > start) {
                val offset = start
                spans +=
                    sentenceSpans(text.substring(start, end)).map { it.first + offset..it.last + offset }
            }
            start = PARAGRAPH_BREAK.find(text, end)?.range?.let { it.last + 1 } ?: text.length
        }
        return spans
    }

    companion object {
        fun of(reply: String): PlainView {
            val reading = Reading(reply)
            reading.read()
            val masked = StringBuilder(reading.out)
            val headers = mutableListOf<IntRange>()
            for (emphasis in reading.emphases) {
                val held = reading.out.substring(emphasis.span.first, emphasis.span.last + 1).trim()
                val header =
                    HELD_ASIDE.matches(held) ||
                        SelfRevision.LABEL.matches(held) ||
                        (BARE_ASIDE.matches(held) && reply.getOrNull(emphasis.after) in LABEL_AFTER)
                when {
                    header -> headers += emphasis.span
                    readsAsTitle(held, reply, emphasis.after) -> for (k in emphasis.span) masked.setCharAt(k, '…')
                    // Else, a sentence or more in emphasis, read as plain text.
                }
            }
            return PlainView(
                reading.out.toString(),
                masked.toString(),
                reading.sources.toIntArray(),
                headers,
                open = reading.unclosed,
            )
        }

        // Whether an emphasis that holds [held], its closing marks ending at [after] in [reply], reads
        // as a title or a lyric, set aside as a quotation is: it holds no citation and ends no
        // sentence, neither inside (a stop before a capital) nor at its close before a citation.
        private fun readsAsTitle(
            held: String,
            reply: String,
            after: Int,
        ): Boolean {
            val stopAtClose = STOP_AT_END.containsMatchIn(held) && CITATION_NEXT.matchesAt(reply, after)
            return !CITATION_IN.containsMatchIn(held) && !STOP_INSIDE.containsMatchIn(held) && !stopAtClose
        }

        private val CITATION_IN = Regex("\\[[A-Z]{1,2}[0-9]*\\]")

        /** A stop that ends a sentence inside an emphasis: before a capital, after any closing marks. */
        private val STOP_INSIDE = Regex("[.!?][\"”'’)]*\\s+[\"“(]?\\p{Lu}")

        private val STOP_AT_END = Regex("[.!?][\"”'’)]*$")

        /** A citation next, after any spaces. */
        private val CITATION_NEXT = Regex("[ \\t]*\\[[A-Z]{1,2}[0-9]*\\]")

        /**
         * An emphasis that holds an aside alone, a comma, colon or dash inside the marks: `*Wait,*`,
         * `**Correction:**`. Not a stop, as a title may end in one (`*Wait!*`, `*Oops.*`).
         */
        private val HELD_ASIDE = Regex("(?:${SelfRevision.ASIDE_WORDS})\\s*[,:—–-]")

        /** An aside's word alone, a header when a colon or a dash follows the marks: `**Correction**:`. */
        private val BARE_ASIDE = Regex("(?:${SelfRevision.ASIDE_WORDS})")

        private val LABEL_AFTER = listOf(':', '—', '–', '-')

        /** A blank line between paragraphs. */
        private val PARAGRAPH_BREAK = Regex("\\n[ \\n]*\\n")
    }
}

// An emphasis read in a reply: its [span] in the plain text, and where in the reply its closing
// marks end ([after]).
private class Emphasis(
    val span: IntRange,
    val after: Int,
)

// A reply read into its plain text ([PlainView]): what is kept ([out]), where each character came
// from ([sources]), and the emphases met ([emphases]).
private class Reading(
    private val reply: String,
) {
    val out = StringBuilder()
    val sources = mutableListOf<Int>()
    val emphases = mutableListOf<Emphasis>()

    // The emphasis marks opened and not yet closed: the mark, how many, where in [out].
    private val open = ArrayDeque<Triple<Char, Int, Int>>()

    /** Whether an emphasis is still open at the end. */
    val unclosed: Boolean get() = open.isNotEmpty()

    fun read() {
        var i = 0
        var lineStart = true
        while (i < reply.length) {
            val c = reply[i]
            i =
                when {
                    lineStart -> afterLineMarks(i).also { lineStart = false }
                    c == '\n' -> keep('\n', i).also { lineStart = true }
                    isJsWhitespace(c) -> keep(' ', i)
                    c == '`' -> i + 1
                    c in EMPHASIS_MARKS && isMark(i) -> emphasis(i)
                    else -> keep(c, i)
                }
        }
    }

    // Keeps [c], from [at]; the next position.
    private fun keep(
        c: Char,
        at: Int,
    ): Int {
        out.append(c)
        sources += at
        return at + 1
    }

    // Where a line's text starts from [from]: its indent and its marks (`### `, `- `, `1. `, `> `)
    // passed over.
    private fun afterLineMarks(from: Int): Int {
        var i = from
        while (true) {
            while (i < reply.length && reply[i] != '\n' && isJsWhitespace(reply[i])) i++
            val mark = LINE_MARK.find(reply, i)?.takeIf { it.range.first == i } ?: return i
            i = mark.range.last + 1
        }
    }

    // Whether the `*` or `_` at [at] is an emphasis mark: not inside a word (`snake_case`), not
    // standing alone between spaces.
    private fun isMark(at: Int): Boolean {
        val end = runEnd(at)
        val before = reply.getOrNull(at - 1)
        val after = reply.getOrNull(end)
        val inWord = before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
        val alone = (before == null || isJsWhitespace(before)) && (after == null || isJsWhitespace(after))
        return !inWord && !alone
    }

    // Reads the run of emphasis marks at [at]: it closes the last one opened with the same marks,
    // else opens one. The next position.
    private fun emphasis(at: Int): Int {
        val end = runEnd(at)
        val count = end - at
        val last = open.lastOrNull()
        if (last != null && last.first == reply[at] && last.second == count) {
            open.removeLast()
            if (out.length > last.third) emphases += Emphasis(last.third until out.length, end)
        } else {
            open.addLast(Triple(reply[at], count, out.length))
        }
        return end
    }

    // Where the run of the mark at [at] ends.
    private fun runEnd(at: Int): Int {
        var end = at
        while (end < reply.length && reply[end] == reply[at]) end++
        return end
    }

    private companion object {
        const val EMPHASIS_MARKS = "*_"

        /** A line's mark: a heading's, a list's, a quotation's, before a space or the line's end. */
        val LINE_MARK = Regex("(?:#{1,6}|[-*+]|[0-9]{1,2}[.)])(?=[ \\t\\n]|$)|>")
    }
}
