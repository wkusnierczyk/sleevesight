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

/**
 * The ids of the lines of [evidence] that [items], lines a run took beyond its own (#1131), brought: by
 * source, scope and words. What *Sources* still marks as the album's, with whichever evidence is shown
 * ([ItemLines.albumIds]).
 */
fun takenIds(
    evidence: EvidenceSet,
    items: List<EvidenceDraft>,
): Set<String> {
    if (items.isEmpty()) return emptySet()
    val brought = items.mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    return evidence.lines.filter { Triple(it.source, it.scope, it.text) in brought }.mapTo(mutableSetOf()) { it.id }
}

/**
 * The lines a run takes beyond the track's own (#1131, [lines]), and which of them are the album's own
 * ([album]), as they were found: so *Sources* marks those as the album's from them alone, whatever the
 * album's evidence holds by then (a source switched off since, say).
 */
data class ItemLines(
    val lines: List<EvidenceDraft> = emptyList(),
    val album: List<EvidenceDraft> = emptyList(),
) {
    /** The ids of [evidence]'s lines that are [album]'s ([takenIds]). */
    fun albumIds(evidence: EvidenceSet): Set<String> = takenIds(evidence, album)

    companion object {
        /** The lines of [album], [artist] and [song] beyond [own] ([linesBeyond]), the album's marked. */
        fun beyond(
            own: List<EvidenceLine>,
            album: EvidenceSet?,
            artist: EvidenceSet?,
            song: EvidenceSet?,
        ): ItemLines {
            val lines = linesBeyond(own, listOf(album, artist, song))
            val albums = album?.lines.orEmpty().mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
            return ItemLines(lines, lines.filter { Triple(it.source, it.scope, it.text) in albums })
        }
    }
}
