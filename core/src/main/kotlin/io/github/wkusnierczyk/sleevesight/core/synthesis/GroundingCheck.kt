package io.github.wkusnierczyk.sleevesight.core.synthesis

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet
import io.github.wkusnierczyk.sleevesight.core.text.JS_WHITESPACE_CLASS
import io.github.wkusnierczyk.sleevesight.core.text.isJsWhitespace
import io.github.wkusnierczyk.sleevesight.core.text.trimJs

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

/** A written reply, checked: its paragraphs, each a list of sentences, none empty. */
data class GroundedText(
    val text: String,
    val paragraphs: List<List<CheckedSentence>>,
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
 * its `NO_EVIDENCE` test, rule for rule; the tests compare it with the simulator's own code on the
 * same replies (`core/src/test/resources/grounding/`).
 *
 * It cannot tell whether a cited line supports its sentence: a plausible false sentence with a
 * valid citation passes. Hedge detection is English only, as the prompts are. Patterns are portable
 * (no lookbehind, D2): the simulator's sentence split, which uses one, is spelled out as a scan.
 */
object GroundingCheck {
    /** The model's id for its own knowledge. */
    const val MODEL_ID: String = "M"

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
        return InsightReply.Written(GroundedText(text, paragraphs))
    }

    /**
     * Splits [text] into sentences, as the simulator does: spaces and tabs collapse to one space,
     * and a sentence ends at whitespace that follows `.`, `!` or `?` (and any citations after it,
     * like `. [W1][G2]`) and comes before a capital letter, `"`, `“` or `(`. So `Dr. Smith` splits
     * too, as it does in the simulator.
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
                HEDGE.containsMatchIn(sentence.asciiLowercase()) -> SentenceStatus.HEDGE
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
                    ends = true
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
    private val SPACES_AND_TABS = Regex("[ \\t]+")
    private val PARAGRAPH_BREAK = Regex("\\n$JS_WHITESPACE_CLASS*\\n")
    private val CITATION = Regex("\\[([A-Z]{1,2}[0-9]*)\\]")
    private val HEDGE =
        Regex(
            "little is (known|documented)|not much is (known|documented)|no (further )?(details|information)|" +
                "isn't much|is not much|nothing (else )?is (known|documented)",
        )
}
