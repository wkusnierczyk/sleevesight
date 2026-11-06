package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SelfRevision

// A reply that revises itself where a card would show it (#798, `SelfRevision`), read the same way for
// the key insight (`InsightWriter`) and the song's, the artist's and the album's insights (#522,
// `ItemInsights`): while it streams, after it ends, and when it is served from the cache.

/**
 * [text] as a card would show it, checked against [evidence]: the grounding check's reply; or, when
 * that still revises itself, the part after the revision ([SelfRevision.salvage]), the rest
 * withdrawn; null when nothing usable is left. [revised] is told of a revision found, and whether
 * anything was salvaged.
 */
internal fun readReply(
    text: String,
    evidence: EvidenceSet,
    allowModel: Boolean,
    revised: (SelfRevision.Found, Boolean) -> Unit = { _, _ -> },
): InsightReply? {
    val reply = GroundingCheck.check(text, evidence, allowModel)
    val grounded = (reply as? InsightReply.Written)?.grounded
    val found = grounded?.let { SelfRevision.find(it.text) } ?: return reply
    val salvaged = SelfRevision.salvage(checkNotNull(grounded), found, evidence.ids.toSet())
    revised(found, salvaged != null)
    return salvaged?.let { InsightReply.Written(it) }
}

/**
 * [reply] without the sentences that cite any of [ids] (#958: lines the model was given that the
 * evidence it is kept with no longer has), so what is shown and kept reads as written from that
 * evidence: as it came when none does, or it is `NO_EVIDENCE`; empty when no sentence is left.
 */
internal fun withoutCiting(
    reply: String,
    ids: Set<String>,
): String {
    val paragraphs =
        (GroundingCheck.check(reply, ids, allowModel = true) as? InsightReply.Written)?.grounded?.paragraphs
    if (paragraphs == null || paragraphs.flatten().none { sentence -> sentence.cites.any { it in ids } }) {
        return reply
    }
    return paragraphs
        .map { paragraph -> paragraph.filter { sentence -> sentence.cites.none { it in ids } } }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n") { paragraph ->
            paragraph.drop(1).fold(paragraph.first().text) { text, sentence ->
                text + (if (sentence.afterLineBreak) "\n" else " ") + sentence.text
            }
        }
}

/**
 * Whether a sign read while the reply streams stops what the card shows (#798): an aside, the first
 * person or talk of the evidence; not a repeated opening, whose second occurrence is what is kept.
 */
internal fun SelfRevision?.freezes(): Boolean = this != null && this != SelfRevision.REPEATED_OPENING
