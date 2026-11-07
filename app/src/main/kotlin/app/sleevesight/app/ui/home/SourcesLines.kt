package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import kotlinx.coroutines.flow.flowOf

// The album's own lines in *Sources* (#522), and the artist's web lines (#912), so a citation of the
// album's or the artist's insight opens *Sources* at its line, as the key insight's do.

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
 * [evidence], the track's, with [album]'s lines (#522) and [artist]'s, the artist's insight's (its web
 * lines for an artist the sources say nothing about, #912), and [song]'s, the song's insight's (its web
 * lines for a song the sources only list, #958), mixed in by source and numbered on from the track's; a
 * line the track, or the album, has already (its source, scope and words) once. The artist's lines say
 * "artist" as their scope does. Not the key insight's evidence: a list to read.
 */
internal fun withAlbum(
    evidence: EvidenceSet?,
    album: EvidenceSet?,
    artist: EvidenceSet? = null,
    song: EvidenceSet? = null,
): SourcesLines {
    val seen = evidence?.lines.orEmpty().mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    val more =
        listOfNotNull(album, artist, song)
            .flatMap { it.lines }
            .filter { seen.add(Triple(it.source, it.scope, it.text)) }
            // A language read from the line's script by the item's set is read again with the track's (#949).
            .map {
                it.draft().let { draft ->
                    if (draft.scripted) draft.copy(language = null, scripted = false) else draft
                }
            }
    if (evidence == null || more.isEmpty()) return SourcesLines(evidence)
    val shown = evidence.with(more, JvmHasher)
    val albums = album?.lines.orEmpty().mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    val own = evidence.ids.toSet()
    val albumIds =
        shown.lines
            .filter { it.id !in own && Triple(it.source, it.scope, it.text) in albums }
            .mapTo(mutableSetOf()) { it.id }
    return SourcesLines(shown, albumIds)
}

/**
 * What *Sources* lists for [track]: its evidence, with its album's lines, its artist's web lines
 * (#912) and its song's (#958), once [notes] gathered them.
 */
@Composable
internal fun sourcesLines(
    notes: ItemNotes,
    track: TrackState?,
): SourcesLines {
    // Followed afresh for another track, or another album of it (the same song on a compilation).
    val album by remember(notes, track?.normalized, track?.nowPlaying?.album, track?.resolution, track?.sources) {
        track?.let(notes::albumEvidence) ?: flowOf(null)
    }.collectAsState(null)
    val artist by remember(notes, track?.normalized, track?.resolution, track?.sources) {
        track?.let(notes::artistEvidence) ?: flowOf(null)
    }.collectAsState(null)
    val song by remember(notes, track?.normalized, track?.resolution, track?.sources) {
        track?.let(notes::songEvidence) ?: flowOf(null)
    }.collectAsState(null)
    val evidence = track?.evidence
    return remember(evidence, album, artist, song) { withAlbum(evidence, album, artist, song) }
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
