package app.sleevesight.core.pipeline

/**
 * One thing a rewrite (*Rewrite insight*, #854; or a write by itself in place of the insight
 * another model kept, #972) used that the insight it replaced did not, which the footer names
 * ("Rewritten with …", and the offer, "Tap Rewrite insight to have AI rewrite the insight with …"),
 * in the order of the entries. [key] is how the synthesis cache keeps it, with the card: never
 * renamed, since a card kept under an old key would lose the part.
 */
enum class RewritePart(
    val key: String,
) {
    /** What *Look again* (#617) found new in the sources. */
    NEW_FINDINGS("findings"),

    /**
     * The evidence changed otherwise: a source's key, a source or the web search switched on or off,
     * or a source answering otherwise.
     */
    UPDATED_SOURCES("sources"),

    /** The AI service, its model or the reasoning effort, named by the model and effort written with. */
    MODEL("model"),

    /** The AI service's own knowledge (*Sources*), switched on. */
    KNOWLEDGE_ON("knowledge-on"),

    /** The AI service's own knowledge, switched off. */
    KNOWLEDGE_OFF("knowledge-off"),

    /** The insight prompt's version, after an app update. */
    INSTRUCTIONS("instructions"),
    ;

    companion object {
        /** [parts] as the cache keeps them: their keys in order, a space between; null for none. */
        fun encode(parts: Set<RewritePart>): String? =
            parts.sortedBy { it.ordinal }.joinToString(" ") { it.key }.ifEmpty { null }

        /** The parts [text] keeps ([encode]); a key no part has any more is left out. */
        fun decode(text: String?): Set<RewritePart> =
            text
                ?.split(' ')
                ?.mapNotNull { key -> entries.firstOrNull { it.key == key } }
                ?.toSet()
                .orEmpty()
    }
}

/**
 * What a model call of a run would replace (#854, [RunControl.rewriting]): the insight kept for its
 * track, written with [replaced]; whether the run looked again (#617) and found something new
 * ([foundNew] true), nothing new (false), or did not look again (null); and the fingerprint of the
 * evidence a look again since that insight found something new in ([foundIn]), null for none, which
 * holds though the run that looked again was replaced since (a settings change).
 */
data class Rewriting(
    val replaced: WrittenBy,
    val foundNew: Boolean? = null,
    val foundIn: String? = null,
)

/**
 * What an insight written with [with] uses that one written with [replaced] did not (#854), in
 * [RewritePart]'s order. The evidence: new findings whenever a look again since [replaced] found
 * something new ([foundIn] set, whatever run looked, or [foundNew] true for this run), and updated
 * sources besides when [with]'s evidence is not the evidence it found them in (another source
 * changed since: a key removed, a source switched off); nothing when the run looked and found nothing
 * new (false) and no look again found anything, for the app counts no change then, though lines may
 * come in another order or spacing; else another evidence fingerprint, where both say one, is
 * updated sources. Not per line: what the look again found may since have gone with a source. The
 * AI service, its model or effort; the model's own knowledge switched on or off, where both say; and
 * the prompt's version. Nothing that neither names (the style, D14, not built).
 */
fun rewriteParts(
    replaced: WrittenBy,
    with: WrittenBy,
    foundNew: Boolean? = null,
    foundIn: String? = null,
): Set<RewritePart> =
    buildSet {
        addAll(evidenceParts(replaced.evidenceFingerprint, with.evidenceFingerprint, foundNew, foundIn))
        if (replaced.provider != with.provider || replaced.model != with.model || replaced.effort != with.effort) {
            add(RewritePart.MODEL)
        }
        val knew = replaced.modelKnowledge
        val knows = with.modelKnowledge
        if (knew != null && knows != null && knew != knows) {
            add(if (knows) RewritePart.KNOWLEDGE_ON else RewritePart.KNOWLEDGE_OFF)
        }
        if (replaced.promptVersion != with.promptVersion) add(RewritePart.INSTRUCTIONS)
    }

// The evidence's parts of [rewriteParts], from the fingerprints [before] and [now].
private fun evidenceParts(
    before: String?,
    now: String?,
    foundNew: Boolean?,
    foundIn: String?,
): Set<RewritePart> =
    when {
        foundIn != null && now != null && foundIn != now -> setOf(RewritePart.NEW_FINDINGS, RewritePart.UPDATED_SOURCES)
        foundIn != null || foundNew == true -> setOf(RewritePart.NEW_FINDINGS)
        foundNew == null && before != null && now != null && before != now -> setOf(RewritePart.UPDATED_SOURCES)
        else -> emptySet()
    }

/**
 * What a card written under this key is written with ([InsightState.Written.writtenBy]): what a
 * rewrite under it would use too, which the song's, the artist's and the album's rows offer (#911).
 */
fun CardKey.writtenBy(): WrittenBy =
    WrittenBy(promptVersion, provider, model, effort, modelKnowledge, evidenceFingerprint)
