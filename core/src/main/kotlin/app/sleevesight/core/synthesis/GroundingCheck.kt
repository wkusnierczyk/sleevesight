package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import app.sleevesight.core.text.isJsWhitespace
import app.sleevesight.core.text.trimJs

/** How the grounding check classifies one sentence (`design/design.md`, *Synthesis*). */
enum class SentenceStatus {
    /** Cites only evidence that exists. */
    OK,

    /** Cites the model's own knowledge, `[M]`, which is allowed: unverified, and shown so. */
    MODEL,

    /** Uncited, and states a documentation gap, which may go uncited. */
    HEDGE,

    /** No citation, and not a stated gap. */
    UNCITED,

    /** Cites an id that is not in the evidence, or `[M]` while model knowledge is off. */
    UNKNOWN,
}

/** One sentence of a reply, its citations in order (`W1`, `M`), and its [status]. */
data class CheckedSentence(
    val text: String,
    val cites: List<String>,
    val status: SentenceStatus,
)

/** The count of sentences in each [SentenceStatus], for the log and the card's footer. */
data class GroundingCounts(
    val ok: Int,
    val model: Int,
    val hedge: Int,
    val uncited: Int,
    val unknown: Int,
) {
    val total: Int get() = ok + model + hedge + uncited + unknown
}

/**
 * A written reply, checked: its paragraphs, each a list of sentences, none empty, and whether the
 * model's own knowledge (`[M]`) was allowed when it was checked ([allowModel]). The card reads its
 * `[M]` citations by that, not by the setting now, and not by whether any sentence came out
 * [SentenceStatus.MODEL]: a sentence citing `[M]` beside a line that does not exist is
 * [SentenceStatus.UNKNOWN] either way.
 */
data class GroundedText(
    val text: String,
    val paragraphs: List<List<CheckedSentence>>,
    val allowModel: Boolean,
) {
    val counts: GroundingCounts by lazy {
        val all = paragraphs.flatten().groupingBy { it.status }.eachCount()
        GroundingCounts(
            ok = all[SentenceStatus.OK] ?: 0,
            model = all[SentenceStatus.MODEL] ?: 0,
            hedge = all[SentenceStatus.HEDGE] ?: 0,
            uncited = all[SentenceStatus.UNCITED] ?: 0,
            unknown = all[SentenceStatus.UNKNOWN] ?: 0,
        )
    }
}

/** What the model replied, as the check reads it. */
sealed interface InsightReply {
    /** The model said there is no usable evidence (`NO_EVIDENCE`). */
    data object NoEvidence : InsightReply

    data class Written(
        val grounded: GroundedText,
    ) : InsightReply
}

/**
 * The grounding check, run in code on every reply before it is shown (`design/architecture.md`,
 * *Synthesis and grounding*). A port of the simulator's `groundCheck` and `splitSentences`, and of
 * its `NO_EVIDENCE` test, rule for rule but for two deviations from the Phase 0.5 gate (#46): an
 * abbreviation does not end a sentence ([splitSentences]), and prompt version 2's fixed sentence
 * for a gap, [GAP_SENTENCE], is a hedge. The tests compare it with the simulator's own code on the same
 * replies (`core/src/test/resources/grounding/`).
 *
 * It cannot tell whether a cited line supports its sentence: a plausible false sentence with a
 * valid citation passes. Hedge detection is English only, as the prompts are. Patterns are portable
 * (no lookbehind, D2): the simulator's sentence split, which uses one, is spelled out as a scan.
 */
object GroundingCheck {
    /** The model's id for its own knowledge. */
    const val MODEL_ID: String = "M"

    /**
     * The sentence insight prompt version 2 asked a reply to end with, uncited, to state that the
     * evidence covers only part of what it would say (#46). Version 3 asks for no gap sentence
     * (#193), but the check still reads this one as a hedge, so a reply written under version 2 (a
     * recorded one, or a card kept under its version 2 key) reads as it did, and a version 3 reply
     * that writes it anyway is not flagged as uncited. A hedge when it is the whole sentence,
     * as written; other wordings of a gap are hedges only as the simulator's patterns allow.
     */
    const val GAP_SENTENCE: String = "Little else is documented."

    /** The reply that says there is no usable evidence. */
    const val NO_EVIDENCE: String = "NO_EVIDENCE"

    /** Checks [reply] against [evidence]; `[M]` is allowed only when [allowModel]. */
    fun check(
        reply: String,
        evidence: EvidenceSet,
        allowModel: Boolean,
    ): InsightReply = check(reply, evidence.ids.toSet(), allowModel)

    /** Checks [reply] against the evidence [ids]; `[M]` is allowed only when [allowModel]. */
    fun check(
        reply: String,
        ids: Set<String>,
        allowModel: Boolean,
    ): InsightReply {
        val text = reply.trimJs()
        if (isNoEvidence(text)) return InsightReply.NoEvidence
        val paragraphs =
            text
                .split(PARAGRAPH_BREAK)
                .map { paragraph -> splitSentences(paragraph).map { classify(it, ids, allowModel) } }
                .filter { it.isNotEmpty() }
        return InsightReply.Written(GroundedText(text, paragraphs, allowModel))
    }

    /**
     * Splits [text] into sentences, as the simulator does: spaces and tabs collapse to one space,
     * and a sentence ends at whitespace that follows `.`, `!` or `?` (and any citations after it,
     * like `. [W1][G2]`) and comes before a capital letter, `"`, `“` or `(`.
     *
     * One deviation from the simulator (#46): a `.` that ends an abbreviation, with nothing after it
     * but the whitespace, does not end the sentence. An abbreviation is a run of capital initials
     * (`U.S.`, `R.E.M.`, `J.`) or one of [ABBREVIATIONS] (`Dr.`, `St.`, `feat.`). The simulator split
     * `the U.S. Billboard Hot 100` into two sentences, the first of them uncited. Initials before a
     * word that usually opens a sentence ([SENTENCE_OPENERS]: `... a hit in the U.S. It ...`) still
     * end one. The cost: a sentence that ends in initials before another word (`... toured the U.S.
     * Billboard named ...`), or in one of [ABBREVIATIONS], is joined to the next, and if it was
     * uncited, the next one's citation hides that.
     */
    fun splitSentences(text: String): List<String> {
        val t = text.replace(SPACES_AND_TABS, " ").trimJs()
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < t.length) {
            if (!isJsWhitespace(t[i])) {
                i++
                continue
            }
            var end = i
            while (end < t.length && isJsWhitespace(t[end])) end++
            if (end < t.length && t[end] in OPENERS && endsSentence(t, i)) {
                sentences += t.substring(start, i)
                start = end
            }
            i = end
        }
        sentences += t.substring(start)
        return sentences.filter { it.trimJs().isNotEmpty() }
    }

    private fun classify(
        sentence: String,
        ids: Set<String>,
        allowModel: Boolean,
    ): CheckedSentence {
        val cites = CITATION.findAll(sentence).map { it.groupValues[1] }.toList()
        val bad = cites.any { if (it == MODEL_ID) !allowModel else it !in ids }
        val status =
            when {
                bad -> SentenceStatus.UNKNOWN
                cites.isNotEmpty() -> if (MODEL_ID in cites) SentenceStatus.MODEL else SentenceStatus.OK
                sentence.trimJs() == GAP_SENTENCE || HEDGE.containsMatchIn(sentence.asciiLowercase()) ->
                    SentenceStatus.HEDGE
                else -> SentenceStatus.UNCITED
            }
        return CheckedSentence(sentence, cites, status)
    }

    // The simulator's `/^NO_EVIDENCE\b/`: the marker, then the end or a character that is not a
    // JavaScript word character.
    private fun isNoEvidence(text: String): Boolean =
        text.startsWith(NO_EVIDENCE) &&
            (text.length == NO_EVIDENCE.length || !isWordChar(text[NO_EVIDENCE.length]))

    private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /**
     * Whether the text before [end] ends a sentence: the simulator's lookbehind
     * `(?<=[.!?](?:\s*\[[A-Z]{1,2}\d*\])*)`, read backwards over the citations.
     * An abbreviation's `.` directly before [end] does not end one ([splitSentences]).
     */
    private fun endsSentence(
        t: String,
        end: Int,
    ): Boolean {
        var k = end
        var ends: Boolean? = null
        while (ends == null) {
            val c = t.getOrNull(k - 1)
            val open = citationBefore(t, k)
            when {
                c != null && c in ".!?" -> {
                    // An abbreviation's `.` ends nothing, unless citations follow it (#46).
                    ends = k != end || !endsAbbreviation(t, end)
                }

                open == null -> {
                    ends = false
                }

                else -> {
                    k = open
                    while (k > 0 && isJsWhitespace(t[k - 1])) k--
                }
            }
        }
        return ends
    }

    /**
     * Whether the text just before [end] is an abbreviation and its `.`: the word before [end],
     * after any opening `(`, `"` or `“`, is one of [ABBREVIATIONS], or a run of capital initials
     * not followed by one of [SENTENCE_OPENERS].
     */
    private fun endsAbbreviation(
        t: String,
        end: Int,
    ): Boolean {
        if (t.getOrNull(end - 1) != '.') return false
        var start = end - 1
        while (start > 0 && !isJsWhitespace(t[start - 1])) start--
        while (start < end && t[start] in WORD_OPENERS) start++
        val word = t.substring(start, end)
        // The next word, after the whitespace and any opening `(`, `"` or `“`.
        var next = end
        while (next < t.length && (isJsWhitespace(t[next]) || t[next] in WORD_OPENERS)) next++
        var stop = next
        while (stop < t.length && !isJsWhitespace(t[stop])) stop++
        // A curly apostrophe reads as a straight one: `It’s` opens a sentence as `It's` does.
        val opens = t.substring(next, stop).trimEnd(',', ';', ':').replace('’', '\'') in SENTENCE_OPENERS
        return word.dropLast(1) in ABBREVIATIONS || (INITIALS.matches(word) && !opens)
    }

    /**
     * Words that open a sentence far more often than they follow initials within one: after
     * `the U.S.`, `It reached …` is a new sentence, `Billboard …` most likely not. Matched with
     * their case; stored with straight apostrophes, and a curly one in the next word is read as
     * straight ([endsAbbreviation]).
     */
    private val SENTENCE_OPENERS: Set<String> =
        setOf(
            "A",
            "An",
            "The",
            "It",
            "Its",
            "It's",
            "He",
            "She",
            "They",
            "We",
            "This",
            "That",
            "These",
            "Those",
            "His",
            "Her",
            "Their",
            "There",
            "In",
            "On",
            "At",
            "By",
            "For",
            "From",
            "With",
            "After",
            "Before",
            "When",
            "While",
            "As",
            "But",
            "And",
            "Later",
            "Then",
        )

    /**
     * Words that end in `.` without ending a sentence, as written before a name or a title: the
     * replies name people (`Dr. Dre`), places (`St. John's`) and featured artists (`feat. Van
     * Morrison`). Matched with their case.
     */
    private val ABBREVIATIONS: Set<String> =
        setOf("Dr", "Mr", "Mrs", "Ms", "St", "feat", "Feat", "ft", "Ft", "vs", "Vs")

    /** Where the citation (`[W1]`, `[MB12]`, `[M]`) that ends just before [end] starts, or null. */
    private fun citationBefore(
        t: String,
        end: Int,
    ): Int? {
        if (t.getOrNull(end - 1) != ']') return null
        var p = end - 2
        while (p >= 0 && t[p] in '0'..'9') p--
        var letters = 0
        while (p >= 0 && letters < 2 && t[p] in 'A'..'Z') {
            p--
            letters++
        }
        return p.takeIf { letters > 0 && it >= 0 && t[it] == '[' }
    }

    // The simulator's hedge pattern is matched with `/i` and no `u`, which folds ASCII letters only.
    private fun String.asciiLowercase(): String = map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

    private const val OPENERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\"“("
    private const val WORD_OPENERS = "\"“("
    private val INITIALS = Regex("(?:[A-Z]\\.)+")
    private val SPACES_AND_TABS = Regex("[ \\t]+")
    private val PARAGRAPH_BREAK = Regex("\\n$JS_WHITESPACE_CLASS*\\n")
    private val CITATION = Regex("\\[([A-Z]{1,2}[0-9]*)\\]")

    private val HEDGE =
        Regex(
            "little is (known|documented)|not much is (known|documented)|no (further )?(details|information)|" +
                "isn't much|is not much|nothing (else )?is (known|documented)",
        )
}
