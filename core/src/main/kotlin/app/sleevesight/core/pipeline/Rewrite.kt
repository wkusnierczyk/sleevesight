package app.sleevesight.core.pipeline

/**
 * One thing a rewrite (*Rewrite insight*, #854) used that the insight it replaced did not, which the
 * footer names ("Rewritten with …", and the offer, "Tap Rewrite insight to have AI rewrite the
 * insight with …"), in the order of the entries. [key] is how the synthesis cache keeps it, with the
 * card: never renamed, since a card kept under an old key would lose the part.
 */
enum class RewritePart(
    val key: String,
) {
    /** What *Look again* (#617) found new in the sources. */
    NEW_FINDINGS("findings"),

    /** The evidence changed otherwise: a source's key, a source or the web search switched on or off. */
    SOURCE_SETTINGS("sources"),

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
 * track, written with [replaced], and whether the run looked again (#617) and found something new
 * ([foundNew] true), nothing new (false), or did not look again (null).
 */
data class Rewriting(
    val replaced: WrittenBy,
    val foundNew: Boolean? = null,
)

/**
 * What an insight written with [with] uses that one written with [replaced] did not (#854), in
 * [RewritePart]'s order. The evidence: what *Look again* found new when it did ([foundNew] true);
 * nothing when it looked and found nothing new (false), for the app counts no change then, though
 * lines may come in another order or spacing; else another evidence fingerprint, where both say one,
 * is a change of the source settings. The AI service, its model or effort; the model's own knowledge
 * switched on or off, where both say; and the prompt's version. Nothing that neither names (the style,
 * D14, not built).
 */
fun rewriteParts(
    replaced: WrittenBy,
    with: WrittenBy,
    foundNew: Boolean? = null,
): Set<RewritePart> =
    buildSet {
        val before = replaced.evidenceFingerprint
        val now = with.evidenceFingerprint
        when {
            foundNew == true -> add(RewritePart.NEW_FINDINGS)
            foundNew == null && before != null && now != null && before != now -> add(RewritePart.SOURCE_SETTINGS)
        }
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

/** What a card written under this key is written with ([InsightState.Written.writtenBy]). */
internal fun CardKey.writtenBy(): WrittenBy =
    WrittenBy(promptVersion, provider, model, effort, modelKnowledge, evidenceFingerprint)
