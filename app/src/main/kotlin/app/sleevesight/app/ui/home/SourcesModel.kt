package app.sleevesight.app.ui.home

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId

// What *Sources* shows of each source by default (#440): the chips of the sources that gave lines,
// and a note only for what needs the listener; the rest is behind *Show grounding details*.

/**
 * Of [chips], those *Sources* shows by name (#440): a source that gave lines to [evidence], whatever
 * else it found, and never the model's own knowledge, which gives none. The rest, and every chip's
 * status, show only with *Show grounding details* on.
 */
internal fun chipsWithLines(
    chips: List<SourceChip>,
    evidence: EvidenceSet?,
): List<SourceChip> {
    val sources = evidence?.lines.orEmpty().mapTo(mutableSetOf()) { it.source }
    return chips.filter { chip -> chip.source != SourceId.MODEL && chip.filters.any { it in sources } }
}

/** What *Sources* tells the listener of a source (#440): only what needs them or went wrong. */
internal sealed interface SourceNote {
    val source: SourceId

    /** On, but its key is not saved: it was not asked. */
    data class NeedsKey(
        override val source: SourceId,
    ) : SourceNote

    /** It could not be reached. */
    data class Unreachable(
        override val source: SourceId,
    ) : SourceNote

    /** Its lookup went wrong. */
    data class Failed(
        override val source: SourceId,
    ) : SourceNote
}

/**
 * The notes of [chips], in their order (#440): a source that needs its key, one that could not be
 * reached, and one whose lookup failed; nothing for one that found nothing, was rejected or is off.
 * While the *Sources* section says the phone is [offline], which covers it, an unreachable source
 * has no note of its own.
 */
internal fun sourceNotes(
    chips: List<SourceChip>,
    offline: Boolean,
): List<SourceNote> =
    chips.mapNotNull { chip ->
        val status = chip.status
        when {
            status == ChipStatus.NeedsKey -> SourceNote.NeedsKey(chip.source)
            status == ChipStatus.Failed -> SourceNote.Failed(chip.source)
            status is ChipStatus.Done && status.outcome == Outcome.Unreachable && !offline ->
                SourceNote.Unreachable(chip.source)
            else -> null
        }
    }
