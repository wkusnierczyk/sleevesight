package app.sleevesight.core.synthesis

import app.sleevesight.core.text.trimJs

/**
 * A sign that a reply still revises itself in the text a card would show (#798): the model's
 * draft, its comment on it, and the insight again, left as written because [SelfCorrection] could
 * not take the draft out (the comment cites a line, or shares its line with the text). Such a reply
 * is not shown: `InsightWriter` asks once more, and shows the error state if the second reply has a
 * sign as well. Read on what the card would show, after [SelfCorrection], so a correction that
 * code takes out still costs no second call.
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

    companion object {
        /**
         * The first sign of revision in [text], what a card would show ([GroundedText.text]), or
         * null when it has none.
         *
         * - [ASIDE]: a sentence, any opening bracket or space aside, starts with one of [ASIDES], or
         *   breaks off for one ([BREAK_OFF]: "It is the opening song on... no, that is not ...").
         * - [FIRST_PERSON]: a sentence has `I`, `I'll`, `I need to`, `let me` and so on before a word
         *   of revision ([FIRST_PERSON_REVISES]: correct, revise, rewrite, fix, drop, remove, cut,
         *   keep only, stick to), or speaks of its own earlier draft, reply or answer.
         * - [EVIDENCE]: a sentence speaks of the evidence as what the reply is checked against
         *   ([EVIDENCE_TALK]: "the evidence says that", "not supported by the evidence", "the
         *   evidence lines", "my citations"). A stated gap ("The evidence doesn't say more about this
         *   recording.") is no sign: the prompt asks against it, but it reads as prose, and a
         *   second call for it would often end in the error state instead of a fair insight.
         * - [REPEATED_OPENING]: a later sentence, of at least [REPEAT_MIN_WORDS] words, shares at
         *   least [REPEAT_SHARE] of its words, each counted once, with the opening sentence, and
         *   the opening one as many of its own with it. Quotations are read here too: the insight
         *   written twice repeats its quoted title.
         *
         * The cost: a lyric or a title written without quotation marks or emphasis that opens with
         * `Wait,` or says "let me fix", and an insight that restates its own opening sentence,
         * fail; the reply is asked for once more.
         */
        fun of(text: String): SelfRevision? {
            val whole = text.trimJs()
            if (whole.isEmpty()) return null
            val read = EMPHASIS.replace(Quotations.of(whole).outside, "…")
            val sentences = sentencesOf(read)
            return when {
                sentences.any { ASIDES.containsMatchIn(it.trimStart('(', '[', ' ')) } -> ASIDE
                BREAK_OFF.containsMatchIn(read) -> ASIDE
                sentences.any { FIRST_PERSON_REVISES.containsMatchIn(it) } -> FIRST_PERSON
                sentences.any { EVIDENCE_TALK.containsMatchIn(it) } -> EVIDENCE
                repeatsOpening(sentencesOf(whole)) -> REPEATED_OPENING
                else -> null
            }
        }

        // The sentences of [text], paragraph by paragraph, as the grounding check splits them.
        private fun sentencesOf(text: String): List<String> =
            text.split(PARAGRAPH_BREAK).flatMap { GroundingCheck.splitSentences(it) }.filter { it.isNotBlank() }

        // Whether a sentence after the first of [sentences] restates it ([of], [REPEATED_OPENING]).
        private fun repeatsOpening(sentences: List<String>): Boolean {
            val opening = words(sentences.firstOrNull().orEmpty())
            return opening.size >= REPEAT_MIN_WORDS &&
                sentences.drop(1).any { sentence ->
                    val later = words(sentence)
                    val shared = later.count { it in opening }
                    later.size >= REPEAT_MIN_WORDS &&
                        shared >= later.size * REPEAT_SHARE &&
                        shared >= opening.size * REPEAT_SHARE
                }
        }

        // The words of [text], lower case, each once, without citations.
        private fun words(text: String): Set<String> =
            WORD
                .findAll(CITATION.replace(text, " "))
                .map { it.value.lowercase() }
                .toSet()

        /** How only an aside opens a sentence, with its case. */
        private val ASIDES =
            Regex(
                "^(?:(?:Wait|Oops|Whoops|Hmm+)\\s*[,.!:—–-]|On second thought\\b|" +
                    "Correction\\s*[:,—–-]|Scratch that\\b)",
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

        private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

        /** The fewest words a sentence needs to count as a restated opening. */
        private const val REPEAT_MIN_WORDS = 6

        /** The share of words two sentences must share to be one written twice. */
        private const val REPEAT_SHARE = 0.8

        /** A word: letters and digits, with any apostrophes inside it. */
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

        private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    }
}
