package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet

/**
 * The lines of [items], the evidence gathered for the song's, the artist's and the album's insights
 * (#522: the album's own lines, the web's for an artist or a song the sources say nothing about,
 * #912, #958), that [own], a track's evidence, has not: each line once, by its source, scope and
 * words, the first set's first, in the order of [items]. What *Sources* lists after the track's
 * lines, and what a run of the track that found nothing to narrate takes into its evidence (#1131),
 * numbered on from its own. A language read from a line's script by the item's set is left to be read
 * again with the track's (#949).
 */
fun linesBeyond(
    own: List<EvidenceLine>,
    items: List<EvidenceSet?>,
): List<EvidenceDraft> {
    val seen = own.mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    return items
        .flatMap { it?.lines.orEmpty() }
        .filter { seen.add(Triple(it.source, it.scope, it.text)) }
        .map { line ->
            line.draft().let { draft ->
                if (draft.scripted) draft.copy(language = null, scripted = false) else draft
            }
        }
}
