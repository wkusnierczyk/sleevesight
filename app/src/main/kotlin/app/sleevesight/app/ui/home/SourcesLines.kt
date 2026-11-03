package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import kotlinx.coroutines.flow.flowOf

// The album's own lines in *Sources* (#522), so a citation of the album's insight opens *Sources* at
// its line, as the key insight's do.

/**
 * What *Sources* lists ([evidence]): the track's evidence, then, once the album's insight has
 * gathered its own on this track, the album's lines mixed in by source, each after its source's track
 * lines and numbered on from them (W1, W2 the track's, W3 the album's; D1 the album's first Wikidata
 * line when the track has none). The track's lines keep their ids. The numbering is for showing alone:
 * the album's insight is written from, and keyed by, its own lines numbered on their own. [album]
 * holds the ids of the album's lines as shown, whose scope says "album".
 */
internal class SourcesLines(
    val evidence: EvidenceSet?,
    val album: Set<String> = emptySet(),
)

/**
 * [evidence], the track's, with [album]'s lines (#522) mixed in by source and numbered on from the
 * track's; a line the track has already (its source, scope and words) once. Not the key insight's
 * evidence: a list to read.
 */
internal fun withAlbum(
    evidence: EvidenceSet?,
    album: EvidenceSet?,
): SourcesLines {
    if (evidence == null || album == null || album.isEmpty()) return SourcesLines(evidence)
    val own = evidence.lines.mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    val more =
        album.lines
            .filter { Triple(it.source, it.scope, it.text) !in own }
            .map { EvidenceDraft(it.source, it.scope, it.text, it.url, it.retrievedAt, it.aggregate, it.about) }
    val shown = evidence.with(more, JvmHasher)
    return SourcesLines(shown, (shown.ids - evidence.ids.toSet()).toSet())
}

/** What *Sources* lists for [track]: its evidence, with its album's lines once [notes] gathered them. */
@Composable
internal fun sourcesLines(
    notes: ItemNotes,
    track: TrackState?,
): SourcesLines {
    val album by remember(notes, track?.normalized) { track?.let(notes::albumEvidence) ?: flowOf(null) }
        .collectAsState(null)
    val evidence = track?.evidence
    return remember(evidence, album) { withAlbum(evidence, album) }
}

/**
 * The id of the line [sources] shows for the line [id] of [evidence], one of an item's (#522): the
 * same source, scope and words; null for none.
 */
internal fun shownId(
    id: String,
    evidence: EvidenceSet,
    sources: EvidenceSet?,
): String? {
    val line = evidence[id] ?: return null
    return sources
        ?.lines
        ?.firstOrNull { it.source == line.source && it.scope == line.scope && it.text == line.text }
        ?.id
}
