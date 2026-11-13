package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.pipeline.linesBeyond
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.sameAddress
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

// The album's own lines in *Sources* (#522), and the artist's web lines (#912), so a citation of the
// album's or the artist's insight opens *Sources* at its line, as the key insight's do.

/**
 * What *Sources* lists ([evidence]): the track's evidence, then, once the album's insight has
 * gathered its own on this track, the album's lines mixed in by source, each after its source's track
 * lines and numbered on from them (W1, W2 the track's, W3 the album's; D1 the album's first Wikidata
 * line when the track has none). The track's lines keep their ids. The numbering is for showing alone:
 * the album's insight is written from, and keyed by, its own lines numbered on their own. [album]
 * holds the ids of the album's lines as shown, whose scope says "album"; [purposes], those of the web
 * lines named by what they were read for (#1205, [LinePurpose]: "genres"), in place of their scope.
 * [asked] is [evidence] before those pages joined it (#1205): what a genre's reason is asked and keyed
 * on (`reasonedOn`), so pages listed for showing never change a reason's key, nor cost one again.
 */
internal class SourcesLines(
    val evidence: EvidenceSet?,
    val album: Set<String> = emptySet(),
    val purposes: Map<String, LinePurpose> = emptyMap(),
    val asked: EvidenceSet? = evidence,
)

/**
 * What a web line in *Sources* was read for, named in place of its scope (#1205): *genres · host*
 * for a page the AI's genre list rests on; *vocals check · host* for the page the check of the
 * track's vocals cites (#1053). A few words each, [label], so another check's pages can be named the
 * same way.
 */
internal enum class LinePurpose(
    @param:StringRes val label: Int,
) {
    GENRES(R.string.home_line_purpose_genres),
    VOCALS_CHECK(R.string.home_line_purpose_vocals_check),
}

/**
 * What *Sources* names [line] by, before its host: what it was read for ([SourcesLines.purposes],
 * #1205), "album" for the album's own line (#522), else its scope ([scopeText]).
 */
@Composable
internal fun SourcesLines.what(line: EvidenceLine): String =
    purposes[line.id]?.let { stringResource(it.label) }
        ?: if (line.id in album) stringResource(R.string.home_scope_album) else scopeText(line.scope)

/**
 * [lines] with [pages] as web lines after them, each named [purpose] (#1205), numbered on from the
 * web lines they have (`S7` after `S6`); a page whose address a web line has already (`sameAddress`)
 * is that line, not listed twice, and keeps its own name. [lines] itself with no page to add, or no
 * evidence to add them to.
 */
internal fun withPages(
    lines: SourcesLines,
    pages: List<WebPage>,
    purpose: LinePurpose,
): SourcesLines {
    val evidence = lines.evidence
    val web =
        evidence
            ?.lines
            .orEmpty()
            .filter { it.source == SourceId.WEB }
            .mapNotNull { it.url }
    // Each address once, as `sameAddress` reads them (#1205): a page the list and a reason's search
    // both found, one with a trailing slash, is one line.
    val fresh =
        pages.fold(listOf<WebPage>()) { kept, page ->
            val known = (web + kept.map { it.url }).any { sameAddress(it, page.url) }
            if (known) kept else kept + page
        }
    if (evidence == null || fresh.isEmpty()) return lines
    val drafts = fresh.map { EvidenceDraft(SourceId.WEB, Scope.WEB, it.text, it.url, UNDATED) }
    val shown = evidence.with(drafts, JvmHasher)
    val own = evidence.ids.toSet()
    val named = shown.lines.filter { it.id !in own }.associate { it.id to purpose }
    return SourcesLines(shown, lines.album, lines.purposes + named, lines.asked)
}

/**
 * The id of the line [sources] (*Sources*' lines) shows for the line [id] of [evidence], a reason's
 * own (#1205): a web page's by its address ([webLineOf]), whatever its id in [evidence], else the same
 * source, scope and words ([shownId]); null for none. A reason keeps its own ids, as it was checked
 * and kept; only what shows them is mapped, so a citation lands on its page however *Sources*
 * numbers it.
 */
internal fun shownLine(
    id: String,
    evidence: EvidenceSet?,
    sources: EvidenceSet?,
): String? {
    val line = evidence?.get(id) ?: return null
    val page = line.url?.takeIf { line.source == SourceId.WEB }?.let { webLineOf(it, sources) }
    return page ?: shownId(id, evidence, sources)
}

/**
 * The id of the line [lines] shows the page at [url] under (`sameAddress`), among its web lines; null
 * for none.
 */
internal fun webLineOf(
    url: String,
    lines: EvidenceSet?,
): String? =
    lines
        ?.lines
        ?.firstOrNull { it.source == SourceId.WEB && it.url?.let { at -> sameAddress(at, url) } == true }
        ?.id

/**
 * [evidence], the track's, with [album]'s lines (#522) and [artist]'s, the artist's insight's (its web
 * lines for an artist the sources say nothing about, #912), and [song]'s, the song's insight's (its web
 * lines for a song the sources only list, #958), mixed in by source and numbered on from the track's; a
 * line the track, or the album, has already (its source, scope and words) once (`linesBeyond`). The
 * artist's lines say "artist" as their scope does. [taken] are the ids of [evidence]'s lines its run
 * took from the album's (#1131, `TrackState.albumIds`): they say "album" from that alone, whatever
 * [album] holds now.
 */
internal fun withAlbum(
    evidence: EvidenceSet?,
    album: EvidenceSet?,
    artist: EvidenceSet? = null,
    song: EvidenceSet? = null,
    taken: Set<String> = emptySet(),
): SourcesLines {
    val more = linesBeyond(evidence?.lines.orEmpty(), listOf(album, artist, song))
    if (evidence == null || (more.isEmpty() && taken.isEmpty())) return SourcesLines(evidence)
    val shown = evidence.with(more, JvmHasher)
    val albums = album?.lines.orEmpty().mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    val own = evidence.ids.toSet()
    val albumIds =
        shown.lines
            .filter { it.id !in own && Triple(it.source, it.scope, it.text) in albums }
            .mapTo(taken.toMutableSet()) { it.id }
    return SourcesLines(shown, albumIds)
}

/**
 * What *Sources* lists for [track]: its evidence, with its album's lines, its artist's web lines
 * (#912) and its song's (#958), once [notes] gathered them; then the pages the AI's genre list from
 * [genres] rests on (#1205), once it lists any genre, and those the genres' reasons found on the web
 * (#801, [GenreNotes.searchedPages]), all named *genres* and numbered together, each address once;
 * last, the page the check of the track's vocals cites (#1053, [GenreNotes.vocals]), named *vocals
 * check*, only when its answer was taken (`instrumental` or `vocals`: an `unknown` cites none), and
 * not again when another line has its address. A reason's citations, and the check's, show these ids
 * by address ([shownLine], [webLineOf]), so they never point at another page.
 */
@Composable
internal fun sourcesLines(
    notes: ItemNotes,
    track: TrackState?,
    genres: GenreNotes = GenreNotes.None,
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
    val listed =
        track
            ?.let { aiGenres(it, genres) }
            ?.takeIf { it.genres.isNotEmpty() }
            ?.pages
            .orEmpty()
    // And the pages the genres' reasons found on the web (#801), numbered with the list's, after them.
    val searched by remember(genres, track?.normalized, track?.nowPlaying?.duration) {
        track?.let(genres::searchedPages) ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val pages = listed + searched
    val checked = listOfNotNull((vocalsOf(track, genres)?.state as? VocalsState.Checked)?.page)
    val evidence = track?.evidence
    val taken = track?.albumIds.orEmpty()
    return remember(evidence, album, artist, song, taken, pages, checked) {
        val genreLines = withPages(withAlbum(evidence, album, artist, song, taken), pages, LinePurpose.GENRES)
        withPages(genreLines, checked, LinePurpose.VOCALS_CHECK)
    }
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

// When a page the AI's genre list rests on was retrieved, which *Sources* does not show: not kept.
private val UNDATED = Instant.fromEpochMilliseconds(0)
