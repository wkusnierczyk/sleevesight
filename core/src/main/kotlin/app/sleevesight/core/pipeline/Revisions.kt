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
 * Whether a sign read while the reply streams stops what the card shows (#798): an aside, the first
 * person or talk of the evidence; not a repeated opening, whose second occurrence is what is kept.
 */
internal fun SelfRevision?.freezes(): Boolean = this != null && this != SelfRevision.REPEATED_OPENING
